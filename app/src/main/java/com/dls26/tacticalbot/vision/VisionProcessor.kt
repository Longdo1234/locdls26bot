package com.dls26.tacticalbot.vision

import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.model.GameState
import org.opencv.core.Mat

class VisionProcessor {

    private val minimapScanner = MinimapScanner()
    private val pitchAndHudScanner = PitchAndHudScanner()

    private var lastFrameTsMs: Long = 0L
    private var smoothedFps: Float = 0f

    fun processFrame(
        rgbaFrame: Mat,
        config: BotConfig,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long
    ): GameState {
        val startNs = System.nanoTime()

        val minimap = minimapScanner.scan(
            rgbaFrame = rgbaFrame,
            config = config,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            timestampMs = nowMs
        )

        val hudResult = pitchAndHudScanner.scan(
            rgbaFrame = rgbaFrame,
            minimap = minimap,
            config = config
        )

        val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
        if (lastFrameTsMs > 0L) {
            val deltaMs = (nowMs - lastFrameTsMs).coerceAtLeast(1L)
            val instantFps = 1000f / deltaMs.toFloat()
            smoothedFps = if (smoothedFps == 0f) instantFps else smoothedFps * 0.8f + instantFps * 0.2f
        }
        lastFrameTsMs = nowMs

        return GameState(
            timestampMs = nowMs,
            scene = hudResult.scene,
            possession = hudResult.possession,
            minimap = minimap,
            hasContinueButton = hudResult.hasContinueButton,
            processingLatencyMs = latencyMs,
            currentFps = smoothedFps
        )
    }

    fun reset() {
        minimapScanner.reset()
        pitchAndHudScanner.reset()
        lastFrameTsMs = 0L
        smoothedFps = 0f
    }
}
