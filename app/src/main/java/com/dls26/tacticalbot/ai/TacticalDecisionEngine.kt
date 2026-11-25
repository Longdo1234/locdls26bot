package com.dls26.tacticalbot.ai

import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.model.ActionButton
import com.dls26.tacticalbot.model.BotAction
import com.dls26.tacticalbot.model.GameScene
import com.dls26.tacticalbot.model.GameState
import com.dls26.tacticalbot.model.NormalizedPoint
import com.dls26.tacticalbot.model.PossessionState
import com.dls26.tacticalbot.model.TacticalMode
import com.dls26.tacticalbot.model.TacticalState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Tactical FSM aligned with standard DLS 26 controls:
 * - A: Shoot (Attack) / Slide Tackle (Defend)
 * - B: Low Pass (Attack) / Press (Defend)
 * - C: Lofted Pass & Cross (Attack) / Switch Player (Defend)
 */
class TacticalDecisionEngine {

    // Cooldowns (ms) to prevent AccessibilityService overlapping gesture spam
    private var lastPassMs: Long = 0L
    private var lastShootMs: Long = 0L
    private var lastSwitchMs: Long = 0L
    private var lastTackleMs: Long = 0L
    private var lastMenuClickMs: Long = 0L

    // Circular EMA state for smooth joystick direction
    private var hasEma: Boolean = false
    private var emaCos: Float = 1f
    private var emaSin: Float = 0f

    // Anti-stuck tracker
    private var stuckAnchor: NormalizedPoint? = null
    private var stuckStartMs: Long = 0L

    fun decide(state: GameState, config: BotConfig): BotAction {
        val now = state.timestampMs

        // 1. Menu / Post-match Auto-Farm handling
        if (state.scene == GameScene.MENU_OR_POST_MATCH) {
            resetTracking()
            if (config.autoFarmSkipMenus && state.hasContinueButton && now - lastMenuClickMs >= MENU_COOLDOWN_MS) {
                lastMenuClickMs = now
                return BotAction(
                    state = TacticalState.MENU_AUTO_ADVANCE,
                    joystickActive = false,
                    joystickAngleRad = 0f,
                    joystickMagnitude = 0f,
                    button = ActionButton.MENU_CONTINUE,
                    buttonHoldMs = 80L,
                    debugNote = "Auto-Continue"
                )
            }
            return BotAction(
                state = TacticalState.IDLE,
                joystickActive = false,
                joystickAngleRad = 0f,
                joystickMagnitude = 0f,
                debugNote = "Menu Idle"
            )
        }

        if (state.scene == GameScene.UNKNOWN) {
            return BotAction(
                state = TacticalState.IDLE,
                joystickActive = false,
                joystickAngleRad = 0f,
                joystickMagnitude = 0f,
                debugNote = "Scanning..."
            )
        }

        val mm = state.minimap
        val active = mm.activePlayer
        val ball = mm.ball

        // 2. Set Piece (Throw-in, Corner, Goal Kick) -> Safe quick low pass (B)
        if (state.scene == GameScene.SET_PIECE) {
            val origin = active ?: ball ?: NormalizedPoint(0f, 0f)
            val bestMate = findBestPassTarget(origin, mm.allies, mm.opponents, config.tacticalMode)
            val targetAnglePitch = if (bestMate != null) {
                atan2(bestMate.y - origin.y, bestMate.x - origin.x)
            } else {
                atan2(-origin.y * 0.5f, 1f - origin.x)
            }
            val screenAngle = smoothScreenAngle(pitchToScreenAngle(targetAnglePitch, mm.attackingRight))
            val btn = if (now - lastPassMs >= 650L) {
                lastPassMs = now
                ActionButton.B_LOW_PASS_OR_PRESS
            } else {
                ActionButton.NONE
            }
            return BotAction(
                state = TacticalState.SET_PIECE_PASS,
                joystickActive = true,
                joystickAngleRad = screenAngle,
                joystickMagnitude = 0.85f,
                button = btn,
                buttonHoldMs = if (btn != ActionButton.NONE) 140L else 0L,
                debugNote = "Set Piece Pass B"
            )
        }

        // 3. Anti-Stuck Watchdog during IN_MATCH
        val referencePos = ball ?: active
        if (referencePos != null) {
            val anchor = stuckAnchor
            if (anchor == null || anchor.distanceTo(referencePos) > 0.03f) {
                stuckAnchor = referencePos
                stuckStartMs = now
            } else if (now - stuckStartMs > 2200L && now - lastPassMs >= PASS_COOLDOWN_MS) {
                stuckStartMs = now
                lastPassMs = now
                val escapePitchAngle = if (referencePos.y > 0f) -0.9f else 0.9f
                val screenAngle = smoothScreenAngle(pitchToScreenAngle(escapePitchAngle, mm.attackingRight))
                return BotAction(
                    state = TacticalState.ATTACK_LOW_PASS,
                    joystickActive = true,
                    joystickAngleRad = screenAngle,
                    joystickMagnitude = 1.0f,
                    button = ActionButton.B_LOW_PASS_OR_PRESS,
                    buttonHoldMs = 150L,
                    debugNote = "Anti-Stuck B"
                )
            }
        }

        // 4. Route to Attacking vs Defending FSM
        return when (state.possession) {
            PossessionState.ALLY -> decideAttack(state, config)
            PossessionState.OPPONENT, PossessionState.LOOSE_BALL -> decideDefend(state, config)
        }
    }

    private fun decideAttack(state: GameState, config: BotConfig): BotAction {
        val now = state.timestampMs
        val mm = state.minimap
        val carrier = mm.activePlayer ?: mm.ball ?: NormalizedPoint(0f, 0f)

        val shootXThreshold = when (config.tacticalMode) {
            TacticalMode.AGGRESSIVE_ATTACK -> 0.58f
            TacticalMode.AUTO_FARM_CAREER -> 0.65f
            TacticalMode.COUNTER_ATTACK -> 0.66f
        }
        val pressureRadius = when (config.tacticalMode) {
            TacticalMode.AGGRESSIVE_ATTACK -> 0.16f
            TacticalMode.AUTO_FARM_CAREER -> 0.19f
            TacticalMode.COUNTER_ATTACK -> 0.22f
        }
        val safeRadius = pressureRadius + 0.07f

        // A. Final Third Finishing -> Shoot with Button A
        if (carrier.x > shootXThreshold && abs(carrier.y) < 0.62f) {
            val targetPostY = if (carrier.y >= 0f) -0.22f else 0.22f
            val goalTarget = NormalizedPoint(1.0f, targetPostY)
            if (!isLaneBlocked(carrier, goalTarget, mm.opponents, 0.085f)) {
                val shootPitchAngle = atan2(goalTarget.y - carrier.y, goalTarget.x - carrier.x)
                val screenAngle = smoothScreenAngle(pitchToScreenAngle(shootPitchAngle, mm.attackingRight))
                val distToGoal = carrier.distanceTo(goalTarget)
                val holdMs = (180f + ((distToGoal - 0.15f).coerceIn(0f, 0.55f) / 0.55f) * 170f)
                    .toLong().coerceIn(180L, 350L)

                val btn = if (now - lastShootMs >= SHOOT_COOLDOWN_MS) {
                    lastShootMs = now
                    ActionButton.A_SHOOT_OR_TACKLE
                } else {
                    ActionButton.NONE
                }
                return BotAction(
                    state = TacticalState.ATTACK_SHOOT,
                    joystickActive = true,
                    joystickAngleRad = screenAngle,
                    joystickMagnitude = 1.0f,
                    button = btn,
                    buttonHoldMs = if (btn != ActionButton.NONE) holdMs else 0L,
                    debugNote = "Shoot A ${holdMs}ms"
                )
            }
        }

        // B. Wing Crossing -> Lofted Cross with Button C into penalty box
        val onWingInFinalThird = carrier.x > 0.55f && abs(carrier.y) > 0.48f
        if (onWingInFinalThird && now - lastPassMs >= PASS_COOLDOWN_MS) {
            val boxTarget = findBestCrossTarget(carrier, mm.allies)
            if (boxTarget != null) {
                val crossPitchAngle = atan2(boxTarget.y - carrier.y, boxTarget.x - carrier.x)
                val screenAngle = smoothScreenAngle(pitchToScreenAngle(crossPitchAngle, mm.attackingRight))
                val crossDist = carrier.distanceTo(boxTarget)
                val holdMs = (160L + (crossDist * 110f).toLong()).coerceIn(160L, 260L)
                lastPassMs = now
                return BotAction(
                    state = TacticalState.ATTACK_LOFTED_CROSS,
                    joystickActive = true,
                    joystickAngleRad = screenAngle,
                    joystickMagnitude = 0.95f,
                    button = ActionButton.C_LOFTED_PASS_OR_SWITCH,
                    buttonHoldMs = holdMs,
                    debugNote = "Wing Cross C"
                )
            }
        }

        // C. Space Analysis (Forward cone toward opponent goal)
        val nearestOppDist = mm.opponents.minOfOrNull { it.distanceTo(carrier) } ?: 2.0f
        val forwardConeThreat = findNearestForwardConeThreat(carrier, mm.opponents)

        // D. Smart Passing: B (Low Pass) vs C (Lofted Long Pass) when under pressure or lane blocked
        val underPressure = nearestOppDist <= pressureRadius || forwardConeThreat <= pressureRadius + 0.04f
        if (underPressure && now - lastPassMs >= PASS_COOLDOWN_MS) {
            val bestMate = findBestPassTarget(carrier, mm.allies, mm.opponents, config.tacticalMode)
            if (bestMate != null) {
                val dx = bestMate.x - carrier.x
                val dy = bestMate.y - carrier.y
                val passDist = hypot(dx, dy)
                val groundBlocked = isLaneBlocked(carrier, bestMate, mm.opponents, 0.065f)
                val mateSpaceAhead = mm.opponents.minOfOrNull {
                    it.distanceTo(NormalizedPoint((bestMate.x + 0.16f).coerceAtMost(1f), bestMate.y))
                } ?: 1.0f

                // Use Lofted Pass (C) for long forward switches or when ground lane is blocked
                val useLoftedPass = (passDist > 0.36f && dx > 0.20f && mateSpaceAhead > 0.18f) ||
                    (groundBlocked && passDist > 0.28f && dx > 0.12f)

                val aimX = if (useLoftedPass) (bestMate.x + 0.10f).coerceAtMost(0.98f) else bestMate.x
                val passPitchAngle = atan2(bestMate.y - carrier.y, aimX - carrier.x)
                val screenAngle = smoothScreenAngle(pitchToScreenAngle(passPitchAngle, mm.attackingRight))

                lastPassMs = now
                val holdMs = if (useLoftedPass) {
                    (150L + (passDist * 130f).toLong()).coerceIn(150L, 270L)
                } else {
                    (100L + (passDist * 90f).toLong()).coerceIn(100L, 190L)
                }

                return BotAction(
                    state = if (useLoftedPass) TacticalState.ATTACK_LOFTED_CROSS else TacticalState.ATTACK_LOW_PASS,
                    joystickActive = true,
                    joystickAngleRad = screenAngle,
                    joystickMagnitude = 0.95f,
                    button = if (useLoftedPass) ActionButton.C_LOFTED_PASS_OR_SWITCH else ActionButton.B_LOW_PASS_OR_PRESS,
                    buttonHoldMs = holdMs,
                    debugNote = if (useLoftedPass) "Lofted Pass C" else "Low Pass B"
                )
            }
        }

        // E. Dribble / Sprint into Open Space toward Opponent Goal
        var targetY = carrier.y * 0.45f
        if (nearestOppDist < safeRadius) {
            val closestOpp = mm.opponents.minByOrNull { it.distanceTo(carrier) }
            if (closestOpp != null) {
                targetY += if (closestOpp.y > carrier.y) -0.28f else 0.28f
            }
        }
        targetY = targetY.coerceIn(-0.75f, 0.75f)
        val dribblePitchAngle = atan2(targetY - carrier.y, 1.0f - carrier.x)
        val screenAngle = smoothScreenAngle(pitchToScreenAngle(dribblePitchAngle, mm.attackingRight))

        return BotAction(
            state = TacticalState.ATTACK_DRIBBLE,
            joystickActive = true,
            joystickAngleRad = screenAngle,
            joystickMagnitude = 1.0f,
            button = ActionButton.NONE,
            debugNote = "Dribble (opp=%.2f)".format(nearestOppDist)
        )
    }

    private fun decideDefend(state: GameState, config: BotConfig): BotAction {
        val now = state.timestampMs
        val mm = state.minimap
        val ball = mm.ball ?: NormalizedPoint(0f, 0f)
        val active = mm.activePlayer

        val predictedBall = NormalizedPoint(
            (ball.x + mm.ballVx * 0.12f).coerceIn(-1f, 1f),
            (ball.y + mm.ballVy * 0.12f).coerceIn(-1f, 1f)
        )

        val goalWeight = when (config.tacticalMode) {
            TacticalMode.COUNTER_ATTACK -> 0.34f
            TacticalMode.AUTO_FARM_CAREER -> 0.26f
            TacticalMode.AGGRESSIVE_ATTACK -> 0.18f
        }
        val intercept = NormalizedPoint(
            predictedBall.x * (1f - goalWeight) + (-1.0f) * goalWeight,
            predictedBall.y * (1f - goalWeight)
        )

        if (active == null) {
            val fallbackAngle = smoothScreenAngle(pitchToScreenAngle(atan2(intercept.y, intercept.x), mm.attackingRight))
            return BotAction(
                state = TacticalState.DEFEND_JOCKEY,
                joystickActive = true,
                joystickAngleRad = fallbackAngle,
                joystickMagnitude = 0.9f,
                debugNote = "Defend Track"
            )
        }

        val distToBall = active.distanceTo(predictedBall)
        val isBeaten = active.x > predictedBall.x + 0.08f
        val betterDefenderExists = mm.allies.any { mate ->
            mate != active && mate.x <= predictedBall.x + 0.04f && mate.distanceTo(intercept) + 0.05f < active.distanceTo(intercept)
        }

        val moveAnglePitch = atan2(intercept.y - active.y, intercept.x - active.x)
        val screenAngle = smoothScreenAngle(pitchToScreenAngle(moveAnglePitch, mm.attackingRight))

        // Switch player (C) if active defender is beaten
        if (isBeaten && betterDefenderExists && now - lastSwitchMs >= SWITCH_COOLDOWN_MS) {
            lastSwitchMs = now
            return BotAction(
                state = TacticalState.DEFEND_SWITCH_PLAYER,
                joystickActive = true,
                joystickAngleRad = screenAngle,
                joystickMagnitude = 0.9f,
                button = ActionButton.C_LOFTED_PASS_OR_SWITCH,
                buttonHoldMs = 70L,
                debugNote = "Switch Defender C"
            )
        }

        // Slide Tackle (A) when close to opponent carrier & outside own penalty box
        val tackleRadius = 0.12f
        val pressRadius = 0.26f
        val outsideOwnBox = active.x > -0.66f

        if (distToBall < tackleRadius && outsideOwnBox && !isBeaten && now - lastTackleMs >= TACKLE_COOLDOWN_MS) {
            lastTackleMs = now
            return BotAction(
                state = TacticalState.DEFEND_TACKLE,
                joystickActive = true,
                joystickAngleRad = screenAngle,
                joystickMagnitude = 1.0f,
                button = ActionButton.A_SHOOT_OR_TACKLE,
                buttonHoldMs = 95L,
                debugNote = "Slide Tackle A"
            )
        }

        // Press (B) when within pressing radius
        if (distToBall < pressRadius && now - lastPassMs >= 320L) {
            lastPassMs = now
            return BotAction(
                state = TacticalState.DEFEND_PRESS,
                joystickActive = true,
                joystickAngleRad = screenAngle,
                joystickMagnitude = 1.0f,
                button = ActionButton.B_LOW_PASS_OR_PRESS,
                buttonHoldMs = 210L,
                debugNote = "Press B (d=%.2f)".format(distToBall)
            )
        }

        return BotAction(
            state = TacticalState.DEFEND_JOCKEY,
            joystickActive = true,
            joystickAngleRad = screenAngle,
            joystickMagnitude = 0.95f,
            button = ActionButton.NONE,
            debugNote = "Jockey Intercept"
        )
    }

    private fun findBestCrossTarget(
        carrier: NormalizedPoint,
        allies: List<NormalizedPoint>
    ): NormalizedPoint? {
        return allies
            .filter { mate ->
                mate != carrier &&
                    mate.x in 0.55f..0.95f &&
                    abs(mate.y) < 0.42f &&
                    carrier.distanceTo(mate) > 0.25f
            }
            .maxByOrNull { it.x - abs(it.y) * 0.4f }
    }

    private fun findNearestForwardConeThreat(
        carrier: NormalizedPoint,
        opponents: List<NormalizedPoint>
    ): Float {
        var nearest = 2.0f
        for (opp in opponents) {
            val dx = opp.x - carrier.x
            if (dx <= 0f) continue
            val dy = opp.y - carrier.y
            val dist = hypot(dx, dy)
            val angle = abs(atan2(dy, dx))
            if (angle <= 0.72f && dist < nearest) {
                nearest = dist
            }
        }
        return nearest
    }

    private fun findBestPassTarget(
        carrier: NormalizedPoint,
        allies: List<NormalizedPoint>,
        opponents: List<NormalizedPoint>,
        mode: TacticalMode
    ): NormalizedPoint? {
        var bestMate: NormalizedPoint? = null
        var bestScore = Float.NEGATIVE_INFINITY

        val forwardWeight = when (mode) {
            TacticalMode.AGGRESSIVE_ATTACK -> 1.8f
            TacticalMode.COUNTER_ATTACK -> 1.5f
            TacticalMode.AUTO_FARM_CAREER -> 1.35f
        }

        for (mate in allies) {
            val dist = carrier.distanceTo(mate)
            if (dist < 0.08f || dist > 0.95f) continue

            val progress = mate.x - carrier.x
            val openness = opponents.minOfOrNull { it.distanceTo(mate) } ?: 0.5f
            val lanePenalty = if (isLaneBlocked(carrier, mate, opponents, 0.07f)) -0.85f else 0f
            val centerBonus = (1f - abs(mate.y)) * 0.25f

            val score = progress * forwardWeight + openness * 1.65f + centerBonus + lanePenalty
            if (score > bestScore) {
                bestScore = score
                bestMate = mate
            }
        }
        return bestMate
    }

    private fun isLaneBlocked(
        from: NormalizedPoint,
        to: NormalizedPoint,
        opponents: List<NormalizedPoint>,
        corridorHalfWidth: Float
    ): Boolean {
        val segDx = to.x - from.x
        val segDy = to.y - from.y
        val segLenSq = segDx * segDx + segDy * segDy
        if (segLenSq < 1e-5f) return false

        for (opp in opponents) {
            val t = (((opp.x - from.x) * segDx + (opp.y - from.y) * segDy) / segLenSq)
            if (t <= 0.1f || t >= 0.95f) continue
            val projX = from.x + t * segDx
            val projY = from.y + t * segDy
            val perpDist = hypot(opp.x - projX, opp.y - projY)
            if (perpDist < corridorHalfWidth) return true
        }
        return false
    }

    private fun pitchToScreenAngle(pitchAngleRad: Float, attackingRight: Boolean): Float {
        val vx = cos(pitchAngleRad)
        val vy = sin(pitchAngleRad)
        val screenVx = if (attackingRight) vx else -vx
        return atan2(vy, screenVx)
    }

    private fun smoothScreenAngle(targetRad: Float): Float {
        val c = cos(targetRad)
        val s = sin(targetRad)
        if (!hasEma) {
            emaCos = c
            emaSin = s
            hasEma = true
        } else {
            val alpha = 0.38f
            emaCos = emaCos * (1f - alpha) + c * alpha
            emaSin = emaSin * (1f - alpha) + s * alpha
            val norm = hypot(emaCos, emaSin)
            if (norm > 1e-4f) {
                emaCos /= norm
                emaSin /= norm
            }
        }
        return atan2(emaSin, emaCos)
    }

    fun resetTracking() {
        hasEma = false
        stuckAnchor = null
        stuckStartMs = 0L
    }

    companion object {
        private const val PASS_COOLDOWN_MS = 400L
        private const val SHOOT_COOLDOWN_MS = 600L
        private const val SWITCH_COOLDOWN_MS = 500L
        private const val TACKLE_COOLDOWN_MS = 700L
        private const val MENU_COOLDOWN_MS = 1500L
    }
}
