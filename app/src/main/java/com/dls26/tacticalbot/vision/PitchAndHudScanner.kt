package com.dls26.tacticalbot.vision

import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.model.GameScene
import com.dls26.tacticalbot.model.MinimapDetection
import com.dls26.tacticalbot.model.PossessionState
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

data class PitchHudScanResult(
    val scene: GameScene,
    val possession: PossessionState,
    val hasContinueButton: Boolean
)

class PitchAndHudScanner {

    private var stationarySetPieceFrames: Int = 0

    fun scan(
        rgbaFrame: Mat,
        minimap: MinimapDetection,
        config: BotConfig
    ): PitchHudScanResult {
        val frameW = rgbaFrame.cols()
        val frameH = rgbaFrame.rows()

        if (!minimap.isValidMinimap) {
            stationarySetPieceFrames = 0
            val hasContinue = detectContinueButton(rgbaFrame, config, frameW, frameH)
            return PitchHudScanResult(
                scene = if (hasContinue) GameScene.MENU_OR_POST_MATCH else GameScene.UNKNOWN,
                possession = PossessionState.LOOSE_BALL,
                hasContinueButton = hasContinue
            )
        }

        val ball = minimap.ball
        val ballSpeed = hypot(minimap.ballVx, minimap.ballVy)
        val nearBoundary = ball != null && (abs(ball.y) > 0.89f || abs(ball.x) > 0.86f)
        if (nearBoundary && ballSpeed < 0.035f) {
            stationarySetPieceFrames++
        } else {
            stationarySetPieceFrames = 0
        }

        val scene = if (stationarySetPieceFrames >= 8) GameScene.SET_PIECE else GameScene.IN_MATCH
        val possession = detectPossession(rgbaFrame, minimap, frameW, frameH)

        return PitchHudScanResult(
            scene = scene,
            possession = possession,
            hasContinueButton = false
        )
    }

    private fun detectContinueButton(
        rgbaFrame: Mat,
        config: BotConfig,
        frameW: Int,
        frameH: Int
    ): Boolean {
        val cx = (config.continueBtnX * frameW).toInt()
        val cy = (config.continueBtnY * frameH).toInt()
        val halfW = max(8, (frameW * 0.06f).toInt())
        val halfH = max(6, (frameH * 0.045f).toInt())

        val left = (cx - halfW).coerceIn(0, max(0, frameW - 4))
        val top = (cy - halfH).coerceIn(0, max(0, frameH - 4))
        val width = (halfW * 2).coerceAtMost(frameW - left)
        val height = (halfH * 2).coerceAtMost(frameH - top)
        if (width < 4 || height < 4) return false

        val roi = Mat(rgbaFrame, Rect(left, top, width, height))
        val bgr = Mat()
        val hsv = Mat()
        val ctaMask = Mat()
        return try {
            Imgproc.cvtColor(roi, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            Core.inRange(hsv, Scalar(18.0, 110.0, 130.0), Scalar(125.0, 255.0, 255.0), ctaMask)
            val ratio = Core.countNonZero(ctaMask).toFloat() / (width * height).toFloat()
            ratio >= 0.28f
        } finally {
            ctaMask.release()
            hsv.release()
            bgr.release()
            roi.release()
        }
    }

    private fun detectPossession(
        rgbaFrame: Mat,
        minimap: MinimapDetection,
        frameW: Int,
        frameH: Int
    ): PossessionState {
        val ball = minimap.ball ?: return PossessionState.LOOSE_BALL
        val nearestAllyDist = minimap.allies.minOfOrNull { it.distanceTo(ball) } ?: Float.MAX_VALUE
        val nearestOppDist = minimap.opponents.minOfOrNull { it.distanceTo(ball) } ?: Float.MAX_VALUE

        val clearThreshold = 0.095f
        if (nearestAllyDist < clearThreshold && nearestAllyDist + 0.025f < nearestOppDist) {
            return PossessionState.ALLY
        }
        if (nearestOppDist < clearThreshold && nearestOppDist + 0.025f < nearestAllyDist) {
            return PossessionState.OPPONENT
        }

        val hasAllyRingOnPitch = detectCenterPitchSelectionRing(rgbaFrame, frameW, frameH)
        return when {
            nearestAllyDist < 0.14f && hasAllyRingOnPitch -> PossessionState.ALLY
            nearestOppDist < 0.14f && nearestOppDist < nearestAllyDist -> PossessionState.OPPONENT
            nearestAllyDist < 0.14f -> PossessionState.ALLY
            else -> PossessionState.LOOSE_BALL
        }
    }

    private fun detectCenterPitchSelectionRing(
        rgbaFrame: Mat,
        frameW: Int,
        frameH: Int
    ): Boolean {
        val left = (frameW * 0.28f).toInt().coerceIn(0, max(0, frameW - 4))
        val top = (frameH * 0.24f).toInt().coerceIn(0, max(0, frameH - 4))
        val width = (frameW * 0.44f).toInt().coerceAtMost(frameW - left)
        val height = (frameH * 0.42f).toInt().coerceAtMost(frameH - top)
        if (width < 8 || height < 8) return false

        val roi = Mat(rgbaFrame, Rect(left, top, width, height))
        val bgr = Mat()
        val hsv = Mat()
        val ringMask = Mat()
        return try {
            Imgproc.cvtColor(roi, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            Core.inRange(hsv, Scalar(85.0, 140.0, 170.0), Scalar(115.0, 255.0, 255.0), ringMask)
            val ratio = Core.countNonZero(ringMask).toFloat() / (width * height).toFloat()
            ratio in 0.0008f..0.045f
        } finally {
            ringMask.release()
            hsv.release()
            bgr.release()
            roi.release()
        }
    }

    fun reset() {
        stationarySetPieceFrames = 0
    }
}
