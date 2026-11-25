package com.dls26.tacticalbot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.config.BotRuntimeState
import com.dls26.tacticalbot.model.ActionButton
import com.dls26.tacticalbot.model.BotAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Continuous 1-Stroke Full-Radius Joystick + Multi-Touch Gesture Controller for DLS 26:
 * - Stroke 0: Anchors at (joyCenterX, joyCenterY) and drags to 100% outer joyRadius in 60ms (willContinue = true).
 * - Stroke N -> N+1: Continues directly from (currentX, currentY) to (newTargetX, newTargetY) on the 100% outer
 *   circle for 120ms (willContinue = true) inside onCompleted() — never lifting or resetting to center.
 * - Angle Pin (< 8 deg delta): Holds virtual thumb pinned at (currentX, currentY) -> (currentX + 0.5f, currentY)
 *   for 120ms so the player sprints smoothly in a straight line without micro-stutters.
 * - Multi-Touch: Combines continuing Joystick Stroke 1 + Action Button (A/B/C) Stroke 2 into one GestureDescription.
 * - Strictly locked behind BotRuntimeState.canDispatchTouch().
 */
class AutoTouchAccessibilityService : AccessibilityService() {

    private data class PendingCommand(
        val action: BotAction,
        val config: BotConfig,
        val isValidMinimap: Boolean,
        val screenWidth: Int,
        val screenHeight: Int,
        val updatedAtMs: Long
    )

    private data class PendingButton(
        val button: ActionButton,
        val holdMs: Long,
        val queuedAtMs: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isLoopActive = AtomicBoolean(false)
    private val latestCommand = AtomicReference<PendingCommand?>(null)
    private val queuedButton = AtomicReference<PendingButton?>(null)

    private var activeJoystickStroke: GestureDescription.StrokeDescription? = null
    private var currentJoyX: Float = 1f
    private var currentJoyY: Float = 1f
    private var lastJoyAngleRad: Float = 0f
    private var hasLastAngle: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BotRuntimeState.setBotRunning(false)
        Log.i(TAG, "AutoTouchAccessibilityService connected (PAUSED by default).")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        hardStopAndClear()
    }

    override fun onDestroy() {
        hardStopAndClear()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun submitAction(
        action: BotAction,
        config: BotConfig,
        isValidMinimap: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        @Suppress("UNUSED_PARAMETER") frameIntervalMs: Long
    ) {
        if (!BotRuntimeState.canDispatchTouch(isValidMinimap)) {
            hardStopAndClear()
            return
        }

        val now = System.currentTimeMillis()
        val landscapeW = max(screenWidth, screenHeight)
        val landscapeH = min(screenWidth, screenHeight)

        latestCommand.set(
            PendingCommand(
                action = action,
                config = config,
                isValidMinimap = true,
                screenWidth = landscapeW,
                screenHeight = landscapeH,
                updatedAtMs = now
            )
        )

        if (action.button != ActionButton.NONE && action.button != ActionButton.MENU_CONTINUE) {
            queuedButton.set(PendingButton(action.button, action.buttonHoldMs, now))
        }

        if (isLoopActive.compareAndSet(false, true)) {
            mainHandler.post { dispatchNextChainedStep() }
        }
    }

    fun hardStopAndClear() {
        isLoopActive.set(false)
        latestCommand.set(null)
        queuedButton.set(null)
        hasLastAngle = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            val stroke = activeJoystickStroke
            activeJoystickStroke = null
            if (stroke != null) {
                try {
                    val path = Path().apply {
                        moveTo(currentJoyX, currentJoyY)
                        lineTo(currentJoyX, currentJoyY)
                    }
                    val endStroke = stroke.continueStroke(path, 0L, 30L, false)
                    dispatchGesture(GestureDescription.Builder().addStroke(endStroke).build(), null, null)
                } catch (_: Exception) {
                    // Safe ignore on teardown
                }
            }
        }
    }

    private fun dispatchNextChainedStep() {
        val cmd = latestCommand.get()
        val now = System.currentTimeMillis()

        if (!isLoopActive.get() ||
            cmd == null ||
            !BotRuntimeState.canDispatchTouch(cmd.isValidMinimap) ||
            (now - cmd.updatedAtMs) > 420L
        ) {
            hardStopAndClear()
            return
        }

        val w = cmd.screenWidth.toFloat().coerceAtLeast(100f)
        val h = cmd.screenHeight.toFloat().coerceAtLeast(100f)
        val cfg = cmd.config
        val action = cmd.action

        val btnItem = queuedButton.getAndSet(null)?.takeIf { (now - it.queuedAtMs) <= 300L }
        val buttonHoldMs = btnItem?.holdMs?.coerceIn(60L, 350L) ?: 0L

        val builder = GestureDescription.Builder()
        var hasStroke = false

        // Stroke 1: Continuous Full-Radius (100% joyRadius) Joystick Drag
        val joyCenterX = (cfg.joystickCenterX * w).coerceIn(1f, w - 1f)
        val joyCenterY = (cfg.joystickCenterY * h).coerceIn(1f, h - 1f)
        val joyRadius = cfg.joystickRadiusNorm * min(w, h)

        if (action.joystickActive) {
            val targetAngle = action.joystickAngleRad
            val fullTargetX = (joyCenterX + cos(targetAngle) * joyRadius).coerceIn(1f, w - 1f)
            val fullTargetY = (joyCenterY + sin(targetAngle) * joyRadius).coerceIn(1f, h - 1f)

            val prev = activeJoystickStroke
            val path = Path()
            val nextStroke: GestureDescription.StrokeDescription

            if (prev == null) {
                // Stroke 0: Drag straight from Joystick center to 100% outer radius in 60ms (willContinue = true)
                val initDurationMs = max(INIT_STROKE_MS, buttonHoldMs)
                path.moveTo(joyCenterX, joyCenterY)
                path.lineTo(fullTargetX, fullTargetY)
                nextStroke = GestureDescription.StrokeDescription(path, 0L, initDurationMs, true)
                currentJoyX = fullTargetX
                currentJoyY = fullTargetY
                lastJoyAngleRad = targetAngle
                hasLastAngle = true
            } else {
                // Stroke N -> N+1: Continue seamlessly on outer circle for 120ms (or buttonHoldMs if longer)
                val contDurationMs = max(CONTINUE_STROKE_MS, buttonHoldMs)
                val startX = currentJoyX.coerceIn(1f, w - 1f)
                val startY = currentJoyY.coerceIn(1f, h - 1f)
                val angleDelta = if (hasLastAngle) abs(normalizeAngleDelta(targetAngle - lastJoyAngleRad)) else Float.MAX_VALUE

                if (angleDelta < SMALL_ANGLE_PIN_RAD) {
                    // Hold thumb pinned tightly in 1 continuous stroke (+0.5f micro-shift keeps gesture alive)
                    val pinX = (if (startX + 0.5f < w - 1f) startX + 0.5f else startX - 0.5f).coerceIn(1f, w - 1f)
                    path.moveTo(startX, startY)
                    path.lineTo(pinX, startY)
                    nextStroke = prev.continueStroke(path, 0L, contDurationMs, true)
                    currentJoyX = pinX
                    currentJoyY = startY
                } else {
                    path.moveTo(startX, startY)
                    path.lineTo(fullTargetX, fullTargetY)
                    nextStroke = prev.continueStroke(path, 0L, contDurationMs, true)
                    currentJoyX = fullTargetX
                    currentJoyY = fullTargetY
                    lastJoyAngleRad = targetAngle
                    hasLastAngle = true
                }
            }

            activeJoystickStroke = nextStroke
            builder.addStroke(nextStroke)
            hasStroke = true
        } else if (activeJoystickStroke != null) {
            val releasePath = Path().apply {
                moveTo(currentJoyX.coerceIn(1f, w - 1f), currentJoyY.coerceIn(1f, h - 1f))
                lineTo(joyCenterX, joyCenterY)
            }
            val endStroke = activeJoystickStroke!!.continueStroke(releasePath, 0L, 35L, false)
            activeJoystickStroke = null
            hasLastAngle = false
            builder.addStroke(endStroke)
            hasStroke = true
        }

        // Stroke 2: Simultaneous Multi-Touch Action Button (A = Shoot/Tackle, B = Low Pass/Press, C = Cross/Switch)
        if (btnItem != null && btnItem.button != ActionButton.NONE && btnItem.button != ActionButton.MENU_CONTINUE) {
            val (bxNorm, byNorm) = when (btnItem.button) {
                ActionButton.A_SHOOT_OR_TACKLE -> cfg.buttonAX to cfg.buttonAY
                ActionButton.B_LOW_PASS_OR_PRESS -> cfg.buttonBX to cfg.buttonBY
                ActionButton.C_LOFTED_PASS_OR_SWITCH -> cfg.buttonCX to cfg.buttonCY
                else -> 0.5f to 0.5f
            }
            val bx = (bxNorm * w).coerceIn(1f, w - 1f)
            val by = (byNorm * h).coerceIn(1f, h - 1f)
            val bxEnd = (bx + 1f).coerceIn(1f, w - 1f)
            val byEnd = (by + 1f).coerceIn(1f, h - 1f)

            val btnPath = Path().apply {
                moveTo(bx, by)
                lineTo(bxEnd, byEnd)
            }
            builder.addStroke(GestureDescription.StrokeDescription(btnPath, 0L, buttonHoldMs, false))
            hasStroke = true
        }

        if (!hasStroke) {
            isLoopActive.set(false)
            return
        }

        safeDispatchGesture(builder.build(), cmd.isValidMinimap)
    }

    private fun safeDispatchGesture(gesture: GestureDescription, isValidMinimap: Boolean) {
        if (!BotRuntimeState.canDispatchTouch(isValidMinimap)) {
            hardStopAndClear()
            return
        }

        try {
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (isLoopActive.get() && BotRuntimeState.canDispatchTouch()) {
                            mainHandler.post { dispatchNextChainedStep() }
                        } else {
                            hardStopAndClear()
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        activeJoystickStroke = null
                        hasLastAngle = false
                        if (isLoopActive.get() && BotRuntimeState.canDispatchTouch()) {
                            mainHandler.postDelayed({ dispatchNextChainedStep() }, 40L)
                        } else {
                            isLoopActive.set(false)
                        }
                    }
                },
                mainHandler
            )
            if (!ok) {
                activeJoystickStroke = null
                hasLastAngle = false
                isLoopActive.set(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "dispatchGesture exception: ${e.message}")
            activeJoystickStroke = null
            hasLastAngle = false
            isLoopActive.set(false)
        }
    }

    private fun normalizeAngleDelta(deltaRad: Float): Float {
        var d = deltaRad
        val twoPi = (2.0 * PI).toFloat()
        while (d > PI) d -= twoPi
        while (d < -PI) d += twoPi
        return d
    }

    companion object {
        private const val TAG = "AutoTouchService"
        private const val INIT_STROKE_MS = 60L
        private const val CONTINUE_STROKE_MS = 120L
        private const val SMALL_ANGLE_PIN_RAD = 0.13962634f // 8 degrees in radians

        @Volatile
        var instance: AutoTouchAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
