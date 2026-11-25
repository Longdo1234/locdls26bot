package com.dls26.tacticalbot.config

import com.dls26.tacticalbot.model.BotAction
import com.dls26.tacticalbot.model.GameState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * SINGLE SOURCE OF TRUTH for the entire app (all Activities & Services share 1 process).
 * Default state is ALWAYS false (PAUSED) until the user explicitly taps RUN on the Floating HUD.
 */
object BotRuntimeState {

    val isBotRunning: AtomicBoolean = AtomicBoolean(false)
    val isSettingsOpen: AtomicBoolean = AtomicBoolean(false)
    val isCaptureRunning: AtomicBoolean = AtomicBoolean(false)

    private val _config = MutableStateFlow(BotConfig())
    val config: StateFlow<BotConfig> = _config.asStateFlow()

    val lastGameState: AtomicReference<GameState?> = AtomicReference(null)
    val lastAction: AtomicReference<BotAction?> = AtomicReference(null)

    @Volatile
    private var onBotRunningChangedListener: ((Boolean) -> Unit)? = null

    fun setOnBotRunningChangedListener(listener: ((Boolean) -> Unit)?) {
        onBotRunningChangedListener = listener
    }

    fun updateConfig(newConfig: BotConfig) {
        _config.value = newConfig
    }

    fun setCaptureRunning(running: Boolean) {
        isCaptureRunning.set(running)
        // NEVER auto-start bot when capture service starts or stops
        setBotRunning(false)
        if (!running) {
            isSettingsOpen.set(false)
        }
    }

    fun setBotRunning(running: Boolean) {
        val allowed = running && isCaptureRunning.get() && !isSettingsOpen.get()
        isBotRunning.set(allowed)
        onBotRunningChangedListener?.invoke(allowed)
    }

    fun setSettingsOpen(open: Boolean) {
        isSettingsOpen.set(open)
        if (open) {
            // Opening Settings/Calibration pauses gesture output immediately
            onBotRunningChangedListener?.invoke(false)
        } else {
            onBotRunningChangedListener?.invoke(isBotRunning.get())
        }
    }

    fun forceStopAll() {
        isBotRunning.set(false)
        isSettingsOpen.set(false)
        onBotRunningChangedListener?.invoke(false)
    }

    /**
     * Master permission gate checked before EVERY gesture dispatch.
     */
    fun canDispatchGesture(isValidMinimap: Boolean): Boolean {
        return isCaptureRunning.get() &&
            isBotRunning.get() &&
            !isSettingsOpen.get() &&
            isValidMinimap
    }

    fun publishFrame(state: GameState, action: BotAction) {
        lastGameState.set(state)
        lastAction.set(action)
    }
}
