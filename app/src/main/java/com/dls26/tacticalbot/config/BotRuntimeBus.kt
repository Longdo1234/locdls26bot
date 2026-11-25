package com.dls26.tacticalbot.config

import com.dls26.tacticalbot.model.BotAction
import com.dls26.tacticalbot.model.GameState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class BotRuntimeSnapshot(
    val isCaptureRunning: Boolean = false,
    val isBotActive: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val lastGameState: GameState? = null,
    val lastAction: BotAction? = null
)

object BotRuntimeBus {
    /**
     * Global atomic safety flags — ALWAYS false by default until user explicitly presses RUN.
     */
    val isBotRunning: AtomicBoolean = AtomicBoolean(false)
    val isSettingsOpen: AtomicBoolean = AtomicBoolean(false)

    private val _config = MutableStateFlow(BotConfig())
    val config: StateFlow<BotConfig> = _config.asStateFlow()

    private val _snapshot = MutableStateFlow(BotRuntimeSnapshot())
    val snapshot: StateFlow<BotRuntimeSnapshot> = _snapshot.asStateFlow()

    fun updateConfig(newConfig: BotConfig) {
        _config.value = newConfig
    }

    fun setCaptureRunning(running: Boolean) {
        if (!running) {
            isBotRunning.set(false)
            isSettingsOpen.set(false)
        } else {
            // Never auto-start bot movement when capture service starts
            isBotRunning.set(false)
        }
        _snapshot.value = _snapshot.value.copy(
            isCaptureRunning = running,
            isBotActive = isBotRunning.get(),
            isSettingsOpen = isSettingsOpen.get()
        )
    }

    fun setBotActive(active: Boolean) {
        isBotRunning.set(active)
        _snapshot.value = _snapshot.value.copy(isBotActive = active)
    }

    fun setSettingsOpen(open: Boolean) {
        isSettingsOpen.set(open)
        _snapshot.value = _snapshot.value.copy(isSettingsOpen = open)
    }

    fun publishFrame(state: GameState, action: BotAction) {
        _snapshot.value = _snapshot.value.copy(
            lastGameState = state,
            lastAction = action
        )
    }
}
