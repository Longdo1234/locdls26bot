package com.dls26.tacticalbot.model

import kotlin.math.hypot

data class NormalizedPoint(
    val x: Float,
    val y: Float
) {
    fun distanceTo(other: NormalizedPoint): Float = hypot(x - other.x, y - other.y)
}

data class ScreenPoint(
    val x: Float,
    val y: Float
)

data class MinimapDetection(
    val allies: List<NormalizedPoint>,
    val opponents: List<NormalizedPoint>,
    val ball: NormalizedPoint?,
    val ballVx: Float,
    val ballVy: Float,
    val activePlayer: NormalizedPoint?,
    val rawAlliesScreen: List<ScreenPoint>,
    val rawOpponentsScreen: List<ScreenPoint>,
    val rawBallScreen: ScreenPoint?,
    val attackingRight: Boolean,
    val minimapConfidence: Float
)

data class GameState(
    val timestampMs: Long,
    val scene: GameScene,
    val possession: PossessionState,
    val minimap: MinimapDetection,
    val hasContinueButton: Boolean,
    val processingLatencyMs: Long,
    val currentFps: Float
)
