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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * High-Discipline Tactical FSM for DLS 26:
 * - PRIORITY #1 (ATTACK): Shooting with Button A immediately inside Shoot Zone (X >= 0.55, |Y| <= 0.60).
 *     Close (X >= 0.75) -> hold A 180ms; Mid (0.55 <= X < 0.75) -> hold A 280ms; shootCooldown = 900ms.
 * - PRIORITY #2 (ATTACK): Default Straight Dribble toward opponent goal (+1.0, 0.0) whenever forward
 *     ±35 deg cone is clear (nearestFrontOppDist > 0.18).
 * - PRIORITY #3 (ATTACK): Purposeful Passing ONLY when ALL 3 conditions hold:
 *     1) Front opponent <= 0.18 OR trapped on sideline (|Y| > 0.85)
 *     2) Valid forward teammate (ally.x > player.x - 0.05, dist 0.15..0.75, lane clearance > 0.08)
 *     3) passCooldown = 1100ms + 80ms pre-aim joystick windup before B (90ms) or wing cross C (180ms).
 *     If no safe pass exists -> evade ±45 deg toward open flank instead of passing blindly.
 */
class TacticalDecisionEngine {

    private var lastPassMs: Long = 0L
    private var lastShootMs: Long = 0L
    private var lastSwitchMs: Long = 0L
    private var lastTackleMs: Long = 0L
    private var lastPressMs: Long = 0L

    // 80ms pre-aim windup tracker before pressing B/C
    private var pendingPassStartMs: Long = 0L
    private var pendingPassTarget: NormalizedPoint? = null

    // Circular EMA smoothing for steady joystick direction
    private var hasEma: Boolean = false
    private var emaCos: Float = 1f
    private var emaSin: Float = 0f

    // Anti-stuck tracker
    private var stuckAnchor: NormalizedPoint? = null
    private var stuckStartMs: Long = 0L

    fun decide(state: GameState, config: BotConfig): BotAction {
        val now = state.timestampMs

        if (!state.isValidMinimap) {
            resetTracking()
            return BotAction(
                state = TacticalState.IDLE,
                joystickActive = false,
                joystickAngleRad = 0f,
                joystickMagnitude = 0f,
                button = ActionButton.NONE,
                buttonHoldMs = 0L,
                debugNote = "No Minimap (Grass ${(state.minimap.grassRatio * 100).toInt()}%)"
            )
        }

        if (state.scene == GameScene.MENU_OR_POST_MATCH || state.scene == GameScene.UNKNOWN) {
            resetTracking()
            return BotAction(
                state = TacticalState.IDLE,
                joystickActive = false,
                joystickAngleRad = 0f,
                joystickMagnitude = 0f,
                debugNote = "Standby"
            )
        }

        val mm = state.minimap
        val active = mm.activePlayer
        val ball = mm.ball

        // Set Piece (Throw-in / Corner / Goal Kick) -> Safe quick low pass B
        if (state.scene == GameScene.SET_PIECE) {
            clearPendingPass()
            val origin = active ?: ball ?: NormalizedPoint(0f, 0f)
            val bestMate = findBestSafePassTarget(origin, mm.allies, mm.opponents)
            val targetAnglePitch = if (bestMate != null) {
                atan2(bestMate.y - origin.y, bestMate.x - origin.x)
            } else {
                atan2(-origin.y * 0.5f, 1f - origin.x)
            }
            val screenAngle = smoothScreenAngle(pitchToScreenAngle(targetAnglePitch, mm.attackingRight))
            val btn = if (now - lastPassMs >= PASS_COOLDOWN_MS) {
                lastPassMs = now
                ActionButton.B_LOW_PASS_OR_PRESS
            } else {
                ActionButton.NONE
            }
            return BotAction(
                state = TacticalState.SET_PIECE_PASS,
                joystickActive = true,
                joystickAngleRad = screenAngle,
                joystickMagnitude = 1.0f,
                button = btn,
                buttonHoldMs = if (btn != ActionButton.NONE) 90L else 0L,
                debugNote = "Set Piece B"
            )
        }

        // Anti-stuck watchdog during IN_MATCH
        val referencePos = ball ?: active
        if (referencePos != null) {
            val anchor = stuckAnchor
            if (anchor == null || anchor.distanceTo(referencePos) > 0.03f) {
                stuckAnchor = referencePos
                stuckStartMs = now
            } else if (now - stuckStartMs > 2400L && now - lastPassMs >= PASS_COOLDOWN_MS) {
                stuckStartMs = now
                lastPassMs = now
                clearPendingPass()
                val escapePitchAngle = if (referencePos.y > 0f) -0.78f else 0.78f
                val screenAngle = smoothScreenAngle(pitchToScreenAngle(escapePitchAngle, mm.attackingRight))
                return BotAction(
                    state = TacticalState.ATTACK_LOW_PASS,
                    joystickActive = true,
                    joystickAngleRad = screenAngle,
                    joystickMagnitude = 1.0f,
                    button = ActionButton.B_LOW_PASS_OR_PRESS,
                    buttonHoldMs = 90L,
                    debugNote = "Anti-Stuck B"
                )
            }
        }

        return when (state.possession) {
            PossessionState.ALLY -> decideAttack(state)
            PossessionState.OPPONENT, PossessionState.LOOSE_BALL -> {
                clearPendingPass()
                decideDefend(state, config)
            }
        }
    }

    private fun decideAttack(state: GameState): BotAction {
        val now = state.timestampMs
        val mm = state.minimap
        val carrier = mm.activePlayer ?: mm.ball ?: NormalizedPoint(0f, 0f)
        val ballPos = mm.ball ?: carrier

        // Use the furthest-forward coordinate between active carrier and ball inside attacking third
        val effectiveX = maxOf(carrier.x, ballPos.x)
        val effectiveY = carrier.y

        // =========================================================================
        // PRIORITY #1: SHOOT ZONE (X >= 0.55 AND |Y| <= 0.60) -> OVERRIDES ALL!
        // =========================================================================
        if (effectiveX >= SHOOT_ZONE_MIN_X && abs(effectiveY) <= SHOOT_ZONE_MAX_ABS_Y) {
            clearPendingPass()
            // Aim at far corner away from goalkeeper
            val aimY = if (effectiveY < 0f) -0.22f else 0.22f
            val goalTarget = NormalizedPoint(1.0f, aimY)
            val shootPitchAngle = atan2(goalTarget.y - effectiveY, goalTarget.x - effectiveX)
            val screenAngle = pitchToScreenAngle(shootPitchAngle, mm.attackingRight)
            // Snap EMA directly toward goal for instant accurate shot aim
            snapEmaTo(screenAngle)

            val holdMs = if (effectiveX >= CLOSE_RANGE_SHOOT_X) SHOOT_HOLD_CLOSE_MS else SHOOT_HOLD_MID_MS
            val fireShot = (now - lastShootMs) >= SHOOT_COOLDOWN_MS
            if (fireShot) {
                lastShootMs = now
            }

            return BotAction(
                state = TacticalState.ATTACK_SHOOT,
                joystickActive = true,
                joystickAngleRad = screenAngle,
                joystickMagnitude = 1.0f,
                button = if (fireShot) ActionButton.A_SHOOT_OR_TACKLE else ActionButton.NONE,
                buttonHoldMs = if (fireShot) holdMs else 0L,
                debugNote = "SHOOTING (A) ${holdMs}ms"
            )
        }

        // =========================================================================
        // PRIORITY #2: FORWARD SPACE ANALYSIS (±35° cone toward opponent goal)
        // =========================================================================
        val nearestFrontOppDist = findNearestForwardConeThreat(carrier, mm.opponents, FRONT_CONE_HALF_RAD)
        val trappedOnSideline = abs(carrier.y) > SIDELINE_TRAP_Y
        val underDangerPressure = nearestFrontOppDist <= FRONT_DANGER_DIST || trappedOnSideline

        // DEFAULT BEHAVIOR: If front cone is clear (> 0.18) and not trapped -> NEVER PASS, DRIBBLE STRAIGHT!
        if (!underDangerPressure) {
            clearPendingPass()
            val goalPitchAngle = atan2(0f - carrier.y, 1.0f - carrier.x)
            val screenAngle = smoothScreenAngle(pitchToScreenAngle(goalPitchAngle, mm.attackingRight))
            return BotAction(
                state = TacticalState.ATTACK_DRIBBLE,
                joystickActive = true,
                joystickAngleRad = screenAngle,
                joystickMagnitude = 1.0f,
                button = ActionButton.NONE,
                debugNote = "Sprint Goal (front=%.2f)".format(nearestFrontOppDist)
            )
        }

        // =========================================================================
        // PRIORITY #3: PURPOSEFUL PASSING (ALL 3 STRICT CONDITIONS)
        // =========================================================================
        val cooldownReady = (now - lastPassMs) >= PASS_COOLDOWN_MS
        val bestMate = if (cooldownReady) findBestSafePassTarget(carrier, mm.allies, mm.opponents) else null

        if (bestMate != null) {
            val passPitchAngle = atan2(bestMate.y - carrier.y, bestMate.x - carrier.x)
            val screenAngle = pitchToScreenAngle(passPitchAngle, mm.attackingRight)
            snapEmaTo(screenAngle)

            // Condition 3: Pre-aim joystick toward teammate for at least 80ms before pressing B or C
            if (pendingPassStartMs == 0L || pendingPassTarget == null || pendingPassTarget!!.distanceTo(bestMate) > 0.18f) {
                pendingPassStartMs = now
                pendingPassTarget = bestMate
                return BotAction(
                    state = TacticalState.ATTACK_LOW_PASS,
                    joystickActive = true,
                    joystickAngleRad = screenAngle,
                    joystickMagnitude = 1.0f,
                    button = ActionButton.NONE,
                    debugNote = "Aim Pass (${now - pendingPassStartMs}ms)"
                )
            }

            if (now - pendingPassStartMs >= PASS_PRE_AIM_MS) {
                val isWingCross = abs(carrier.y) > 0.65f && carrier.x > 0.45f
                lastPassMs = now
                clearPendingPass()
                return BotAction(
                    state = if (isWingCross) TacticalState.ATTACK_LOFTED_CROSS else TacticalState.ATTACK_LOW_PASS,
                    joystickActive = true,
                    joystickAngleRad = screenAngle,
                    joystickMagnitude = 1.0f,
                    button = if (isWingCross) ActionButton.C_LOFTED_PASS_OR_SWITCH else ActionButton.B_LOW_PASS_OR_PRESS,
                    buttonHoldMs = if (isWingCross) CROSS_HOLD_MS else LOW_PASS_HOLD_MS,
                    debugNote = if (isWingCross) "Wing Cross C 180ms" else "Low Pass B 90ms"
                )
            } else {
                return BotAction(
                    state = TacticalState.ATTACK_LOW_PASS,
                    joystickActive = true,
                    joystickAngleRad = screenAngle,
                    joystickMagnitude = 1.0f,
                    button = ActionButton.NONE,
                    debugNote = "Aiming Pass..."
                )
            }
        }

        // =========================================================================
        // FALLBACK: NO SAFE TEAMMATE -> DO NOT PASS BLINDLY; EVADE ±45° TO OPEN FLANK
        // =========================================================================
        clearPendingPass()
        val upperOpps = mm.opponents.count { it.x > carrier.x - 0.10f && it.y < carrier.y }
        val lowerOpps = mm.opponents.count { it.x > carrier.x - 0.10f && it.y >= carrier.y }
        val evadePitchAngle = when {
            carrier.y > 0.72f -> -0.7854f // -45 deg (cut inward from bottom sideline)
            carrier.y < -0.72f -> 0.7854f // +45 deg (cut inward from top sideline)
            upperOpps <= lowerOpps -> -0.7854f
            else -> 0.7854f
        }
        val evadeScreenAngle = smoothScreenAngle(pitchToScreenAngle(evadePitchAngle, mm.attackingRight))
        return BotAction(
            state = TacticalState.ATTACK_DRIBBLE,
            joystickActive = true,
            joystickAngleRad = evadeScreenAngle,
            joystickMagnitude = 1.0f,
            button = ActionButton.NONE,
            debugNote = "Evade ±45°"
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
                joystickMagnitude = 1.0f,
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

        // Switch Player (C) if beaten
        if (isBeaten && betterDefenderExists && now - lastSwitchMs >= SWITCH_COOLDOWN_MS) {
            lastSwitchMs = now
            return BotAction(
                state = TacticalState.DEFEND_SWITCH_PLAYER,
                joystickActive = true,
                joystickAngleRad = screenAngle,
                joystickMagnitude = 1.0f,
                button = ActionButton.C_LOFTED_PASS_OR_SWITCH,
                buttonHoldMs = 70L,
                debugNote = "Switch C"
            )
        }

        // Slide Tackle (A) only when close, outside own box, and not beaten
        val outsideOwnBox = active.x > -0.66f
        if (distToBall < 0.12f && outsideOwnBox && !isBeaten && now - lastTackleMs >= TACKLE_COOLDOWN_MS) {
            lastTackleMs = now
            return BotAction(
                state = TacticalState.DEFEND_TACKLE,
                joystickActive = true,
                joystickAngleRad = screenAngle,
                joystickMagnitude = 1.0f,
                button = ActionButton.A_SHOOT_OR_TACKLE,
                buttonHoldMs = 95L,
                debugNote = "Tackle A"
            )
        }

        // Press (B) when within pressing radius
        if (distToBall < 0.26f && now - lastPressMs >= PRESS_COOLDOWN_MS) {
            lastPressMs = now
            return BotAction(
                state = TacticalState.DEFEND_PRESS,
                joystickActive = true,
                joystickAngleRad = screenAngle,
                joystickMagnitude = 1.0f,
                button = ActionButton.B_LOW_PASS_OR_PRESS,
                buttonHoldMs = 200L,
                debugNote = "Press B"
            )
        }

        return BotAction(
            state = TacticalState.DEFEND_JOCKEY,
            joystickActive = true,
            joystickAngleRad = screenAngle,
            joystickMagnitude = 1.0f,
            button = ActionButton.NONE,
            debugNote = "Jockey"
        )
    }

    private fun findNearestForwardConeThreat(
        carrier: NormalizedPoint,
        opponents: List<NormalizedPoint>,
        halfConeRad: Float
    ): Float {
        var nearest = 2.0f
        for (opp in opponents) {
            val dx = opp.x - carrier.x
            if (dx <= 0f) continue
            val dy = opp.y - carrier.y
            val dist = hypot(dx, dy)
            val angle = abs(atan2(dy, dx))
            if (angle <= halfConeRad && dist < nearest) {
                nearest = dist
            }
        }
        return nearest
    }

    /**
     * Condition 2: Only considers forward teammates (ally.x > player.x - 0.05 unless trapped in own box < -0.65),
     * distance in [0.15, 0.75], and strictly unblocked passing lane (> 0.08 clearance from all opponents).
     */
    private fun findBestSafePassTarget(
        carrier: NormalizedPoint,
        allies: List<NormalizedPoint>,
        opponents: List<NormalizedPoint>
    ): NormalizedPoint? {
        val allowBackPass = carrier.x < -0.65f
        var bestMate: NormalizedPoint? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (mate in allies) {
            val dist = carrier.distanceTo(mate)
            if (dist < 0.15f || dist > 0.75f) continue
            if (!allowBackPass && mate.x <= carrier.x - 0.05f) continue
            if (isLaneBlocked(carrier, mate, opponents, PASS_LANE_CLEARANCE)) continue

            val progress = mate.x - carrier.x
            val openness = opponents.minOfOrNull { it.distanceTo(mate) } ?: 0.5f
            val centerBonus = (1f - abs(mate.y)) * 0.25f
            val score = progress * 1.8f + openness * 1.5f + centerBonus

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
        minClearance: Float
    ): Boolean {
        val segDx = to.x - from.x
        val segDy = to.y - from.y
        val segLenSq = segDx * segDx + segDy * segDy
        if (segLenSq < 1e-5f) return false

        for (opp in opponents) {
            val t = (((opp.x - from.x) * segDx + (opp.y - from.y) * segDy) / segLenSq)
            if (t <= 0.05f || t >= 0.98f) continue
            val projX = from.x + t * segDx
            val projY = from.y + t * segDy
            if (hypot(opp.x - projX, opp.y - projY) <= minClearance) {
                return true
            }
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
            val alpha = 0.35f
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

    private fun snapEmaTo(angleRad: Float) {
        emaCos = cos(angleRad)
        emaSin = sin(angleRad)
        hasEma = true
    }

    private fun clearPendingPass() {
        pendingPassStartMs = 0L
        pendingPassTarget = null
    }

    fun resetTracking() {
        hasEma = false
        stuckAnchor = null
        stuckStartMs = 0L
        clearPendingPass()
    }

    companion object {
        // Shoot Zone & Priority #1 parameters
        private const val SHOOT_ZONE_MIN_X = 0.55f
        private const val CLOSE_RANGE_SHOOT_X = 0.75f
        private const val SHOOT_ZONE_MAX_ABS_Y = 0.60f
        private const val SHOOT_HOLD_CLOSE_MS = 180L
        private const val SHOOT_HOLD_MID_MS = 280L
        private const val SHOOT_COOLDOWN_MS = 900L

        // Dribble & Strict Passing parameters
        private const val FRONT_CONE_HALF_RAD = 0.61086524f // ±35 degrees
        private const val FRONT_DANGER_DIST = 0.18f
        private const val SIDELINE_TRAP_Y = 0.85f
        private const val PASS_LANE_CLEARANCE = 0.08f
        private const val PASS_COOLDOWN_MS = 1100L
        private const val PASS_PRE_AIM_MS = 80L
        private const val LOW_PASS_HOLD_MS = 90L
        private const val CROSS_HOLD_MS = 180L

        // Defense cooldowns
        private const val SWITCH_COOLDOWN_MS = 550L
        private const val TACKLE_COOLDOWN_MS = 750L
        private const val PRESS_COOLDOWN_MS = 350L
    }
}
