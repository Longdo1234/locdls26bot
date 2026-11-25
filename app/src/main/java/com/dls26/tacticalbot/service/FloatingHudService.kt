package com.dls26.tacticalbot.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.config.BotConfigStore
import com.dls26.tacticalbot.config.BotRuntimeState
import com.dls26.tacticalbot.model.BotAction
import com.dls26.tacticalbot.model.GameState
import com.dls26.tacticalbot.ui.DebugOverlayView
import com.dls26.tacticalbot.ui.FloatingControlView
import com.dls26.tacticalbot.ui.InHudSettingsOverlay
import com.dls26.tacticalbot.ui.RoiCalibrationOverlay

class FloatingHudService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var configStore: BotConfigStore
    private val mainHandler = Handler(Looper.getMainLooper())

    private var debugOverlayView: DebugOverlayView? = null
    private var floatingControlView: FloatingControlView? = null
    private var settingsOverlayView: InHudSettingsOverlay? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        configStore = BotConfigStore(this)
        BotRuntimeState.updateConfig(configStore.load())
        BotRuntimeState.setBotRunning(false)
        BotRuntimeState.setSettingsOpen(false)
        BotRuntimeState.setOnBotRunningChangedListener { running ->
            mainHandler.post {
                floatingControlView?.syncVisualFromState()
                if (!running) {
                    AutoTouchAccessibilityService.instance?.hardStopAndClear()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        BotRuntimeState.setBotRunning(false)
        if (Settings.canDrawOverlays(this)) {
            mainHandler.post {
                attachDebugOverlay()
                attachFloatingHudPill()
            }
        }
        return START_NOT_STICKY
    }

    fun updateDebugFrame(state: GameState, action: BotAction, config: BotConfig, active: Boolean) {
        mainHandler.post {
            val view = debugOverlayView ?: return@post
            view.visibility = if (config.debugOverlayEnabled) View.VISIBLE else View.GONE
            if (config.debugOverlayEnabled) {
                view.updateDebugData(state, action, config, active)
            }
        }
    }

    private fun attachDebugOverlay() {
        if (debugOverlayView != null) return
        val overlay = DebugOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params)
        overlay.visibility = if (BotRuntimeState.config.value.debugOverlayEnabled) View.VISIBLE else View.GONE
        debugOverlayView = overlay
    }

    private fun attachFloatingHudPill() {
        if (floatingControlView != null) return
        val pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 104
        }

        val pill = FloatingControlView(
            context = this,
            onTogglePlay = {
                val nextRunning = !BotRuntimeState.isBotRunning.get()
                BotRuntimeState.setBotRunning(nextRunning)
                if (!nextRunning) {
                    AutoTouchAccessibilityService.instance?.hardStopAndClear()
                }
            },
            onOpenSettings = { toggleInHudSettings() },
            onStopService = {
                BotRuntimeState.forceStopAll()
                AutoTouchAccessibilityService.instance?.hardStopAndClear()
                stopService(Intent(this, ScreenCaptureService::class.java))
                stopSelf()
            },
            onDragBy = { dx, dy ->
                pillParams.x += dx
                pillParams.y += dy
                runCatching { windowManager.updateViewLayout(floatingControlView, pillParams) }
            }
        )
        windowManager.addView(pill, pillParams)
        floatingControlView = pill
    }

    private fun toggleInHudSettings() {
        if (settingsOverlayView != null) {
            closeInHudSettings()
            return
        }

        BotRuntimeState.setSettingsOpen(true)
        AutoTouchAccessibilityService.instance?.hardStopAndClear()

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        val panel = InHudSettingsOverlay(
            context = this,
            initialConfig = BotRuntimeState.config.value,
            onConfigChanged = { updated ->
                configStore.save(updated)
                BotRuntimeState.updateConfig(updated)
                debugOverlayView?.visibility = if (updated.debugOverlayEnabled) View.VISIBLE else View.GONE
            },
            onOpenAnchorCalibration = {
                BotRuntimeState.setSettingsOpen(true)
                AutoTouchAccessibilityService.instance?.hardStopAndClear()
                RoiCalibrationOverlay.show(
                    context = this,
                    windowManager = windowManager,
                    initialConfig = BotRuntimeState.config.value,
                    onSaved = { calibrated ->
                        configStore.save(calibrated)
                        BotRuntimeState.updateConfig(calibrated)
                        BotRuntimeState.setSettingsOpen(false)
                    },
                    onDismissed = {
                        BotRuntimeState.setSettingsOpen(false)
                    }
                )
            },
            onClose = { closeInHudSettings() },
            onDragBy = { dx, dy ->
                panelParams.x += dx
                panelParams.y += dy
                runCatching { windowManager.updateViewLayout(settingsOverlayView, panelParams) }
            }
        )
        windowManager.addView(panel, panelParams)
        settingsOverlayView = panel
    }

    private fun closeInHudSettings() {
        settingsOverlayView?.let { runCatching { windowManager.removeView(it) } }
        settingsOverlayView = null
        BotRuntimeState.setSettingsOpen(false)
    }

    override fun onDestroy() {
        BotRuntimeState.setOnBotRunningChangedListener(null)
        BotRuntimeState.forceStopAll()
        AutoTouchAccessibilityService.instance?.hardStopAndClear()
        mainHandler.post {
            closeInHudSettings()
            debugOverlayView?.let { runCatching { windowManager.removeView(it) } }
            debugOverlayView = null
            floatingControlView?.let { runCatching { windowManager.removeView(it) } }
            floatingControlView = null
        }
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.dls26.tacticalbot.FLOATING_HUD_STOP"

        @Volatile
        var instance: FloatingHudService? = null
            private set
    }
}
