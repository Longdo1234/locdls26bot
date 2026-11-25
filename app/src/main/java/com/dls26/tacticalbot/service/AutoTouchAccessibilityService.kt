package com.dls26.tacticalbot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.model.ActionButton
import com.dls26.tacticalbot.model.BotAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Callback-chained Multi-Touch Gesture Controller for DLS 26:
 * - Keeps the virtual thumb continuously pressed on the joystick via `continueStroke(..., willContinue = true)`
 *   chained inside `GestureResultCallback.onCompleted()` at 55-75ms steps so DLS 26 never resets the stick.
 * - Combines Joystick Stroke + Action Button Stroke (A/B/C) into ONE `GestureDescription`
 *   so simultaneous movement + pass/shoot/press/cross works without gesture cancellation.
 * - Strictly clamps every coordinate inside [1f, screenWidth - 1f] x [1f, screenHeight - 1f].
 */
class AutoTouchAccessibilityService : AccessibilityService() {

    private data class PendingCommand(
        val action: BotAction,
        val config: BotConfig,
        val screenWidth: Int,
        val screenHeight: Int,
        val stepDurationMs: Long,
        val updatedAtMs: Long
    )

    private data class PendingButton(
        val button: ActionButton,
        val holdMs: Long,
        val queuedAtMs: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isLoopRunning = AtomicBoolean(false)
    private val latestCommand = AtomicReference<PendingCommand?>(null)
    private val queuedButton = AtomicReference<PendingButton?>(null)

    private var activeJoystickStroke: GestureDescription.StrokeDescription? = null
    private var lastJoyX: Float = 0f
    private var lastJoyY: Float = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AutoTouchAccessibilityService connected (canPerformGestures=true).")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopAndReleaseJoystick()
    }

    override fun onDestroy() {
        stopAndReleaseJoystick()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun submitAction(
        action: BotAction,
        config: BotConfig,
        screenWidth: Int,
        screenHeight: Int,
        frameIntervalMs: Long
    ) {
        val now = System.currentTimeMillis()
        val safeW = max(screenWidth, screenHeight)
        val safeH = min(screenWidth, screenHeight)

        latestCommand.set(
            PendingCommand(
                action = action,
                config = config,
                screenWidth = safeW,
                screenHeight = safeH,
                stepDurationMs = frameIntervalMs.coerceIn(50L, 80L),
                updatedAtMs = now
            )
        )

        if (action.button != ActionButton.NONE) {
            queuedButton.set(PendingButton(action.button, action.buttonHoldMs, now))
        }

        if (isLoopRunning.compareAndSet(false, true)) {
            mainHandler.post { dispatchNextChainedStep() }
        }
    }

    fun stopAndReleaseJoystick() {
        latestCommand.set(null)
        queuedButton.set(null)
        mainHandler.post {
            val stroke = activeJoystickStroke
            activeJoystickStroke = null
            if (stroke != null) {
                try {
                    val path = Path().apply {
                        moveTo(lastJoyX, lastJoyY)
                        lineTo(lastJoyX, lastJoyY)
                    }
                    val endStroke = stroke.continueStroke(path, 0L, 35L, false)
                    val gesture = GestureDescription.Builder().addStroke(endStroke).build()
                    dispatchGesture(gesture, null, null)
                } catch (_: Exception) {
                    // Service may already be tearing down
                }
            }
            isLoopRunning.set(false)
        }
    }

    private fun dispatchNextChainedStep() {
        val cmd = latestCommand.get()
        val now = System.currentTimeMillis()

        // If no fresh command for > 450ms, cleanly lift virtual thumb and pause loop
        if (cmd == null || (now - cmd.updatedAtMs) > 450L) {
            releaseActiveStrokeAndStopLoop()
            return
        }

        val w = cmd.screenWidth.toFloat().coerceAtLeast(100f)
        val h = cmd.screenHeight.toFloat().coerceAtLeast(100f)
        val cfg = cmd.config
        val action = cmd.action

        // Pop any fresh queued button press (discard stale > 300ms)
        val btnItem = queuedButton.getAndSet(null)?.takeIf { (now - it.queuedAtMs) <= 300L }

        val buttonHoldMs = btnItem?.holdMs?.coerceIn(50L, 380L) ?: 0L
        val stepMs = max(cmd.stepDurationMs, buttonHoldMs).coerceIn(50L, 380L)

        val builder = GestureDescription.Builder()
        var hasStroke = false

        // 1. Continuous Joystick Stroke (Stroke 1)
        val joyCenterX = (cfg.joystickCenterX * w).coerceIn(1f, w - 1f)
        val joyCenterY = (cfg.joystickCenterY * h).coerceIn(1f, h - 1f)
        val joyRadius = cfg.joystickRadiusNorm * min(w, h)

        if (action.joystickActive) {
            val targetX = (joyCenterX + cos(action.joystickAngleRad) * joyRadius * action.joystickMagnitude)
                .coerceIn(1f, w - 1f)
            val targetY = (joyCenterY + sin(action.joystickAngleRad) * joyRadius * action.joystickMagnitude)
                .coerceIn(1f, h - 1f)

            val prev = activeJoystickStroke
            val path = Path()
            val nextStroke = if (prev == null) {
                // Anchor virtual thumb at joystick center, then drag smoothly to target
                path.moveTo(joyCenterX, joyCenterY)
                path.lineTo(targetX, targetY)
                GestureDescription.StrokeDescription(path, 0L, stepMs, true)
            } else {
                // Continue seamlessly from previous thumb coordinate without lifting
                val smoothMidX = (lastJoyX + (targetX - lastJoyX) * 0.6f).coerceIn(1f, w - 1f)
                val smoothMidY = (lastJoyY + (targetY - lastJoyY) * 0.6f).coerceIn(1f, h - 1f)
                path.moveTo(lastJoyX.coerceIn(1f, w - 1f), lastJoyY.coerceIn(1f, h - 1f))
                path.quadTo(smoothMidX, smoothMidY, targetX, targetY)
                prev.continueStroke(path, 0L, stepMs, true)
            }

            activeJoystickStroke = nextStroke
            lastJoyX = targetX
            lastJoyY = targetY
            builder.addStroke(nextStroke)
            hasStroke = true
        } else if (activeJoystickStroke != null) {
            // Gracefully release thumb when entering MENU / IDLE
            val releasePath = Path().apply {
                moveTo(lastJoyX.coerceIn(1f, w - 1f), lastJoyY.coerceIn(1f, h - 1f))
                lineTo(joyCenterX, joyCenterY)
            }
            val endStroke = activeJoystickStroke!!.continueStroke(releasePath, 0L, 40L, false)
            activeJoystickStroke = null
            builder.addStroke(endStroke)
            hasStroke = true
        }

        // 2. Simultaneous Multi-Touch Action Button Stroke (Stroke 2)
        if (btnItem != null && btnItem.button != ActionButton.NONE) {
            val (bxNorm, byNorm) = when (btnItem.button) {
                ActionButton.A_SHOOT_OR_TACKLE -> cfg.buttonAX to cfg.buttonAY
                ActionButton.B_LOW_PASS_OR_PRESS -> cfg.buttonBX to cfg.buttonBY
                ActionButton.C_LOFTED_PASS_OR_SWITCH -> cfg.buttonCX to cfg.buttonCY
                ActionButton.MENU_CONTINUE -> cfg.continueBtnX to cfg.continueBtnY
                ActionButton.NONE -> 0.5f to 0.5f
            }
            val bx = (bxNorm * w).coerceIn(1f, w - 1f)
            val by = (byNorm * h).coerceIn(1f, h - 1f)
            val bxEnd = (bx + 1f).coerceIn(1f, w - 1f)
            val byEnd = (by + 1f).coerceIn(1f, h - 1f)

            val btnPath = Path().apply {
                moveTo(bx, by)
                lineTo(bxEnd, byEnd)
            }
            val btnStroke = GestureDescription.StrokeDescription(btnPath, 0L, buttonHoldMs, false)
            builder.addStroke(btnStroke)
            hasStroke = true
        }

        if (!hasStroke) {
            isLoopRunning.set(false)
            return
        }

        try {
            val ok = dispatchGesture(
                builder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        // Immediately chain the next stroke while virtual thumb remains held down
                        if (isLoopRunning.get()) {
                            mainHandler.post { dispatchNextChainedStep() }
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        // Gesture interrupted by user touch or system; reset anchor and continue cleanly
                        activeJoystickStroke = null
                        if (isLoopRunning.get()) {
                            mainHandler.postDelayed({ dispatchNextChainedStep() }, 40L)
                        }
                    }
                },
                mainHandler
            )
            if (!ok) {
                activeJoystickStroke = null
                isLoopRunning.set(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Multi-touch dispatchGesture failed: ${e.message}")
            activeJoystickStroke = null
            isLoopRunning.set(false)
        }
    }

    private fun releaseActiveStrokeAndStopLoop() {
        val stroke = activeJoystickStroke
        activeJoystickStroke = null
        if (stroke != null) {
            try {
                val path = Path().apply {
                    moveTo(lastJoyX, lastJoyY)
                    lineTo(lastJoyX, lastJoyY)
                }
                val endStroke = stroke.continueStroke(path, 0L, 35L, false)
                dispatchGesture(GestureDescription.Builder().addStroke(endStroke).build(), null, null)
            } catch (_: Exception) {
                // Ignore
            }
        }
        isLoopRunning.set(false)
    }

    companion object {
        private const val TAG = "AutoTouchService"

        @Volatile
        var instance: AutoTouchAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
