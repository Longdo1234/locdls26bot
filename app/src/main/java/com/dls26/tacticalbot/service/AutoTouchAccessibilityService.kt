package com.dls26.tacticalbot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.model.ActionButton
import com.dls26.tacticalbot.model.BotAction
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class AutoTouchAccessibilityService : AccessibilityService() {

    private val isDispatching = AtomicBoolean(false)
    private var activeJoystickStroke: GestureDescription.StrokeDescription? = null
    private var lastJoyX: Float = 0f
    private var lastJoyY: Float = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AutoTouchAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed; gestures are driven directly by ScreenCaptureService vision loop.
    }

    override fun onInterrupt() {
        releaseJoystick()
    }

    override fun onDestroy() {
        releaseJoystick()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun executeAction(
        action: BotAction,
        config: BotConfig,
        screenWidth: Int,
        screenHeight: Int,
        frameIntervalMs: Long
    ) {
        if (!isDispatching.compareAndSet(false, true)) {
            return
        }

        try {
            val builder = GestureDescription.Builder()
            var hasAnyStroke = false

            // 1. Continuous Smooth Joystick Stroke
            val centerX = (config.joystickCenterX * screenWidth).coerceIn(10f, screenWidth - 10f)
            val centerY = (config.joystickCenterY * screenHeight).coerceIn(10f, screenHeight - 10f)
            val radiusPx = config.joystickRadiusNorm * min(screenWidth, screenHeight)

            if (action.joystickActive) {
                val targetX = (centerX + cos(action.joystickAngleRad) * radiusPx * action.joystickMagnitude)
                    .coerceIn(5f, screenWidth - 5f)
                val targetY = (centerY + sin(action.joystickAngleRad) * radiusPx * action.joystickMagnitude)
                    .coerceIn(5f, screenHeight - 5f)

                val strokeDuration = frameIntervalMs.coerceIn(45L, 90L)
                val prevStroke = activeJoystickStroke
                val path = Path()
                val nextStroke = if (prevStroke == null) {
                    path.moveTo(centerX, centerY)
                    path.lineTo(targetX, targetY)
                    GestureDescription.StrokeDescription(path, 0L, strokeDuration, true)
                } else {
                    // Smooth interpolation from last thumb coordinate to new target
                    val midX = lastJoyX + (targetX - lastJoyX) * 0.65f
                    val midY = lastJoyY + (targetY - lastJoyY) * 0.65f
                    path.moveTo(lastJoyX, lastJoyY)
                    path.quadTo(midX, midY, targetX, targetY)
                    prevStroke.continueStroke(path, 0L, strokeDuration, true)
                }
                activeJoystickStroke = nextStroke
                lastJoyX = targetX
                lastJoyY = targetY
                builder.addStroke(nextStroke)
                hasAnyStroke = true
            } else if (activeJoystickStroke != null) {
                // Cleanly lift virtual thumb
                val path = Path().apply {
                    moveTo(lastJoyX, lastJoyY)
                    lineTo(centerX, centerY)
                }
                val endStroke = activeJoystickStroke!!.continueStroke(path, 0L, 40L, false)
                activeJoystickStroke = null
                builder.addStroke(endStroke)
                hasAnyStroke = true
            }

            // 2. Action Button Tap / Hold (A, B, C, or Menu Continue)
            if (action.button != ActionButton.NONE) {
                val (bxNorm, byNorm) = when (action.button) {
                    ActionButton.A_SHOOT_OR_TACKLE -> config.buttonAX to config.buttonAY
                    ActionButton.B_LOW_PASS_OR_PRESS -> config.buttonBX to config.buttonBY
                    ActionButton.C_LOFTED_PASS_OR_SWITCH -> config.buttonCX to config.buttonCY
                    ActionButton.MENU_CONTINUE -> config.continueBtnX to config.continueBtnY
                    ActionButton.NONE -> 0f to 0f
                }
                val bx = (bxNorm * screenWidth).coerceIn(5f, screenWidth - 5f)
                val by = (byNorm * screenHeight).coerceIn(5f, screenHeight - 5f)
                val holdMs = action.buttonHoldMs.coerceIn(45L, 420L)

                val btnPath = Path().apply {
                    moveTo(bx, by)
                    lineTo(bx + 1f, by + 1f)
                }
                val btnStroke = GestureDescription.StrokeDescription(btnPath, 0L, holdMs, false)
                builder.addStroke(btnStroke)
                hasAnyStroke = true
            }

            if (!hasAnyStroke) {
                isDispatching.set(false)
                return
            }

            val dispatched = dispatchGesture(
                builder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        isDispatching.set(false)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        activeJoystickStroke = null
                        isDispatching.set(false)
                    }
                },
                null
            )
            if (!dispatched) {
                activeJoystickStroke = null
                isDispatching.set(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gesture dispatch error: ${e.message}")
            activeJoystickStroke = null
            isDispatching.set(false)
        }
    }

    fun releaseJoystick() {
        val stroke = activeJoystickStroke ?: return
        activeJoystickStroke = null
        try {
            val path = Path().apply {
                moveTo(lastJoyX, lastJoyY)
                lineTo(lastJoyX, lastJoyY)
            }
            val endStroke = stroke.continueStroke(path, 0L, 30L, false)
            val gesture = GestureDescription.Builder().addStroke(endStroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            // Safe ignore if service already disconnected
        }
    }

    companion object {
        private const val TAG = "AutoTouchService"

        @Volatile
        var instance: AutoTouchAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
