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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Single-Chokepoint Multi-Touch Gesture Controller:
 * - ZERO local running flags — reads ONLY BotRuntimeState (Single Source of Truth).
 * - Every gesture passes through safeDispatchGesture(), which strictly blocks ANY touch
 *   unless BotRuntimeState.canDispatchGesture(isValidMinimap) == true.
 * - hardStopAndClear() cancels ALL pending Handler callbacks (removeCallbacksAndMessages(null)),
 *   clears queues, and lifts any held continueStroke with willContinue = false immediately.
 */
class AutoTouchAccessibilityService : AccessibilityService() {

    private data class PendingCommand(
        val action: BotAction,
        val config: BotConfig,
        val isValidMinimap: Boolean,
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
    private val isLoopActive = AtomicBoolean(false)
    private val latestCommand = AtomicReference<PendingCommand?>(null)
    private val queuedButton = AtomicReference<PendingButton?>(null)

    private var activeJoystickStroke: GestureDescription.StrokeDescription? = null
    private var lastJoyX: Float = 1f
    private var lastJoyY: Float = 1f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Guarantee bot starts strictly PAUSED on service connect
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
        frameIntervalMs: Long
    ) {
        if (!BotRuntimeState.canDispatchGesture(isValidMinimap)) {
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
                stepDurationMs = frameIntervalMs.coerceIn(50L, 80L),
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

    /**
     * Immediately purges all queued Handler callbacks & commands and lifts any held stroke.
     */
    fun hardStopAndClear() {
        isLoopActive.set(false)
        latestCommand.set(null)
        queuedButton.set(null)
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            val stroke = activeJoystickStroke
            activeJoystickStroke = null
            if (stroke != null) {
                try {
                    val path = Path().apply {
                        moveTo(lastJoyX, lastJoyY)
                        lineTo(lastJoyX, lastJoyY)
                    }
                    val endStroke = stroke.continueStroke(path, 0L, 30L, false)
                    dispatchGesture(GestureDescription.Builder().addStroke(endStroke).build(), null, null)
                } catch (_: Exception) {
                    // Ignore if service is tearing down
                }
            }
        }
    }

    private fun dispatchNextChainedStep() {
        val cmd = latestCommand.get()
        val now = System.currentTimeMillis()

        // Hard Gate right before building & dispatching gesture
        if (!isLoopActive.get() ||
            cmd == null ||
            !BotRuntimeState.canDispatchGesture(cmd.isValidMinimap) ||
            (now - cmd.updatedAtMs) > 380L
        ) {
            hardStopAndClear()
            return
        }

        val w = cmd.screenWidth.toFloat().coerceAtLeast(100f)
        val h = cmd.screenHeight.toFloat().coerceAtLeast(100f)
        val cfg = cmd.config
        val action = cmd.action

        val btnItem = queuedButton.getAndSet(null)?.takeIf { (now - it.queuedAtMs) <= 280L }
        val buttonHoldMs = btnItem?.holdMs?.coerceIn(50L, 380L) ?: 0L
        val stepMs = max(cmd.stepDurationMs, buttonHoldMs).coerceIn(50L, 380L)

        val builder = GestureDescription.Builder()
        var hasStroke = false

        // Stroke 1: Continuous Joystick Drag (willContinue = true)
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
                path.moveTo(joyCenterX, joyCenterY)
                path.lineTo(targetX, targetY)
                GestureDescription.StrokeDescription(path, 0L, stepMs, true)
            } else {
                val startX = lastJoyX.coerceIn(1f, w - 1f)
                val startY = lastJoyY.coerceIn(1f, h - 1f)
                val midX = (startX + (targetX - startX) * 0.6f).coerceIn(1f, w - 1f)
                val midY = (startY + (targetY - startY) * 0.6f).coerceIn(1f, h - 1f)
                path.moveTo(startX, startY)
                path.quadTo(midX, midY, targetX, targetY)
                prev.continueStroke(path, 0L, stepMs, true)
            }

            activeJoystickStroke = nextStroke
            lastJoyX = targetX
            lastJoyY = targetY
            builder.addStroke(nextStroke)
            hasStroke = true
        } else if (activeJoystickStroke != null) {
            val releasePath = Path().apply {
                moveTo(lastJoyX.coerceIn(1f, w - 1f), lastJoyY.coerceIn(1f, h - 1f))
                lineTo(joyCenterX, joyCenterY)
            }
            val endStroke = activeJoystickStroke!!.continueStroke(releasePath, 0L, 35L, false)
            activeJoystickStroke = null
            builder.addStroke(endStroke)
            hasStroke = true
        }

        // Stroke 2: Simultaneous Multi-Touch Action Button (A / B / C)
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

    /**
     * Single chokepoint for all active bot gestures.
     */
    private fun safeDispatchGesture(gesture: GestureDescription, isValidMinimap: Boolean) {
        if (!BotRuntimeState.canDispatchGesture(isValidMinimap)) {
            Log.w(TAG, "Blocked gesture dispatch: bot not running / settings open / invalid minimap.")
            hardStopAndClear()
            return
        }

        try {
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (isLoopActive.get() &&
                            BotRuntimeState.isBotRunning.get() &&
                            !BotRuntimeState.isSettingsOpen.get()
                        ) {
                            mainHandler.post { dispatchNextChainedStep() }
                        } else {
                            hardStopAndClear()
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        activeJoystickStroke = null
                        if (isLoopActive.get() &&
                            BotRuntimeState.isBotRunning.get() &&
                            !BotRuntimeState.isSettingsOpen.get()
                        ) {
                            mainHandler.postDelayed({ dispatchNextChainedStep() }, 45L)
                        } else {
                            isLoopActive.set(false)
                        }
                    }
                },
                mainHandler
            )
            if (!ok) {
                activeJoystickStroke = null
                isLoopActive.set(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "dispatchGesture exception: ${e.message}")
            activeJoystickStroke = null
            isLoopActive.set(false)
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
