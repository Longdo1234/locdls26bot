package com.dls26.tacticalbot.vision

import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.model.AttackDirection
import com.dls26.tacticalbot.model.MinimapDetection
import com.dls26.tacticalbot.model.NormalizedPoint
import com.dls26.tacticalbot.model.ScreenPoint
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

class MinimapScanner {

    private var prevBallNorm: NormalizedPoint? = null
    private var prevBallTimestampMs: Long = 0L
    private var inferredAttackingRight: Boolean = true
    private var directionVoteStreak: Int = 0
    private var pendingDirectionRight: Boolean = true

    fun scan(
        rgbaFrame: Mat,
        config: BotConfig,
        screenWidth: Int,
        screenHeight: Int,
        timestampMs: Long
    ): MinimapDetection {
        val frameW = rgbaFrame.cols()
        val frameH = rgbaFrame.rows()

        val roiX = (config.minimapLeft * frameW).toInt().coerceIn(0, max(0, frameW - 2))
        val roiY = (config.minimapTop * frameH).toInt().coerceIn(0, max(0, frameH - 2))
        val roiW = ((config.minimapRight - config.minimapLeft) * frameW).toInt().coerceIn(2, frameW - roiX)
        val roiH = ((config.minimapBottom - config.minimapTop) * frameH).toInt().coerceIn(2, frameH - roiY)

        val roiRect = Rect(roiX, roiY, roiW, roiH)
        val roiRgba = Mat(rgbaFrame, roiRect)
        val bgr = Mat()
        val hsv = Mat()
        val pitchMask = Mat()
        val allyMask1 = Mat()
        val allyMask2 = Mat()
        val allyMask = Mat()
        val oppMask1 = Mat()
        val oppMask2 = Mat()
        val oppMask = Mat()
        val ballMask = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))

        try {
            Imgproc.cvtColor(roiRgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

            // Estimate minimap presence: DLS 26 radar has a translucent dark/green pitch backdrop
            Core.inRange(hsv, Scalar(30.0, 20.0, 25.0), Scalar(95.0, 255.0, 200.0), pitchMask)
            val pitchRatio = Core.countNonZero(pitchMask).toFloat() / (roiW * roiH).toFloat()

            // Ally dots: Cyan/Blue OR Bright Yellow-Green home radar markers
            Core.inRange(hsv, Scalar(85.0, 110.0, 120.0), Scalar(132.0, 255.0, 255.0), allyMask1)
            Core.inRange(hsv, Scalar(22.0, 130.0, 150.0), Scalar(36.0, 255.0, 255.0), allyMask2)
            Core.bitwise_or(allyMask1, allyMask2, allyMask)
            Imgproc.morphologyEx(allyMask, allyMask, Imgproc.MORPH_OPEN, kernel)

            // Opponent dots: Red / Orange-Red contrasting markers (wrap-around Hue 0..12 & 168..180)
            Core.inRange(hsv, Scalar(0.0, 120.0, 120.0), Scalar(14.0, 255.0, 255.0), oppMask1)
            Core.inRange(hsv, Scalar(166.0, 120.0, 120.0), Scalar(180.0, 255.0, 255.0), oppMask2)
            Core.bitwise_or(oppMask1, oppMask2, oppMask)
            Imgproc.morphologyEx(oppMask, oppMask, Imgproc.MORPH_OPEN, kernel)

            // Ball marker: Small very bright white/light-yellow dot
            Core.inRange(hsv, Scalar(0.0, 0.0, 225.0), Scalar(45.0, 75.0, 255.0), ballMask)
            Imgproc.morphologyEx(ballMask, ballMask, Imgproc.MORPH_OPEN, kernel)

            val minDotArea = max(3.0, (roiW * roiH) * 0.00012)
            val maxDotArea = (roiW * roiH) * 0.015

            val rawAllies = extractCentroids(allyMask, minDotArea, maxDotArea, 12)
            val rawOpps = extractCentroids(oppMask, minDotArea, maxDotArea, 12)
            val rawBalls = extractCentroids(ballMask, max(2.0, minDotArea * 0.6), maxDotArea * 0.65, 3)

            // Choose ball candidate closest to previous ball or smallest bright circular blob
            val rawBall = selectBestBall(rawBalls, roiW, roiH)

            // Determine attack direction (Left->Right vs Right->Left)
            val attackingRight = resolveAttackDirection(config.attackDirection, rawAllies, rawOpps, roiW)

            val scaleX = screenWidth.toFloat() / frameW.toFloat()
            val scaleY = screenHeight.toFloat() / frameH.toFloat()

            val alliesNorm = rawAllies.map { toNormalized(it.first, it.second, roiW, roiH, attackingRight) }
            val oppsNorm = rawOpps.map { toNormalized(it.first, it.second, roiW, roiH, attackingRight) }
            val ballNorm = rawBall?.let { toNormalized(it.first, it.second, roiW, roiH, attackingRight) }

            val alliesScreen = rawAllies.map { ScreenPoint((roiX + it.first) * scaleX, (roiY + it.second) * scaleY) }
            val oppsScreen = rawOpps.map { ScreenPoint((roiX + it.first) * scaleX, (roiY + it.second) * scaleY) }
            val ballScreen = rawBall?.let { ScreenPoint((roiX + it.first) * scaleX, (roiY + it.second) * scaleY) }

            // Ball velocity vector across consecutive frames
            var vx = 0f
            var vy = 0f
            val prevBall = prevBallNorm
            if (ballNorm != null && prevBall != null && prevBallTimestampMs > 0L) {
                val dtSec = ((timestampMs - prevBallTimestampMs).coerceAtLeast(16L)) / 1000f
                if (dtSec < 0.5f) {
                    vx = (ballNorm.x - prevBall.x) / dtSec
                    vy = (ballNorm.y - prevBall.y) / dtSec
                }
            }
            if (ballNorm != null) {
                prevBallNorm = ballNorm
                prevBallTimestampMs = timestampMs
            }

            // Active player: ally closest to the ball (or most central ally if ball briefly occluded)
            val activePlayer = when {
                ballNorm != null && alliesNorm.isNotEmpty() -> alliesNorm.minByOrNull { it.distanceTo(ballNorm) }
                alliesNorm.isNotEmpty() -> alliesNorm.minByOrNull { it.distanceTo(NormalizedPoint(0f, 0f)) }
                else -> null
            }

            val totalDots = rawAllies.size + rawOpps.size
            val dotScore = (totalDots.toFloat() / 10f).coerceIn(0f, 1f)
            val pitchScore = (pitchRatio / 0.25f).coerceIn(0f, 1f)
            val confidence = (0.55f * dotScore + 0.45f * pitchScore).coerceIn(0f, 1f)

            return MinimapDetection(
                allies = alliesNorm,
                opponents = oppsNorm,
                ball = ballNorm,
                ballVx = vx,
                ballVy = vy,
                activePlayer = activePlayer,
                rawAlliesScreen = alliesScreen,
                rawOpponentsScreen = oppsScreen,
                rawBallScreen = ballScreen,
                attackingRight = attackingRight,
                minimapConfidence = confidence
            )
        } finally {
            kernel.release()
            ballMask.release()
            oppMask.release()
            oppMask2.release()
            oppMask1.release()
            allyMask.release()
            allyMask2.release()
            allyMask1.release()
            pitchMask.release()
            hsv.release()
            bgr.release()
            roiRgba.release()
        }
    }

    private fun extractCentroids(
        mask: Mat,
        minArea: Double,
        maxArea: Double,
        maxCount: Int
    ): List<Pair<Float, Float>> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val results = ArrayList<Pair<Double, Pair<Float, Float>>>()
        try {
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area in minArea..maxArea) {
                    val m = Imgproc.moments(contour)
                    if (m.m00 > 0.0) {
                        val cx = (m.m10 / m.m00).toFloat()
                        val cy = (m.m01 / m.m00).toFloat()
                        results.add(area to (cx to cy))
                    }
                }
            }
        } finally {
            hierarchy.release()
            for (c in contours) c.release()
        }
        return results
            .sortedByDescending { it.first }
            .take(maxCount)
            .map { it.second }
    }

    private fun selectBestBall(
        candidates: List<Pair<Float, Float>>,
        roiW: Int,
        roiH: Int
    ): Pair<Float, Float>? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        val prev = prevBallNorm ?: return candidates[0]
        return candidates.minByOrNull { (cx, cy) ->
            val norm = toNormalized(cx, cy, roiW, roiH, inferredAttackingRight)
            norm.distanceTo(prev)
        }
    }

    private fun resolveAttackDirection(
        setting: AttackDirection,
        rawAllies: List<Pair<Float, Float>>,
        rawOpps: List<Pair<Float, Float>>,
        roiW: Int
    ): Boolean {
        return when (setting) {
            AttackDirection.LEFT_TO_RIGHT -> true
            AttackDirection.RIGHT_TO_LEFT -> false
            AttackDirection.AUTO -> {
                if (rawAllies.size >= 4 && rawOpps.size >= 4 && roiW > 0) {
                    val allyAvgX = rawAllies.map { it.first / roiW.toFloat() }.average()
                    val oppAvgX = rawOpps.map { it.first / roiW.toFloat() }.average()
                    if (kotlin.math.abs(allyAvgX - oppAvgX) > 0.08) {
                        val voteRight = allyAvgX < oppAvgX
                        if (voteRight == pendingDirectionRight) {
                            directionVoteStreak = min(30, directionVoteStreak + 1)
                            if (directionVoteStreak >= 15) {
                                inferredAttackingRight = voteRight
                            }
                        } else {
                            pendingDirectionRight = voteRight
                            directionVoteStreak = 1
                        }
                    }
                }
                inferredAttackingRight
            }
        }
    }

    private fun toNormalized(
        px: Float,
        py: Float,
        roiW: Int,
        roiH: Int,
        attackingRight: Boolean
    ): NormalizedPoint {
        val rawX = ((px / roiW.toFloat()) * 2f - 1f).coerceIn(-1f, 1f)
        val normX = if (attackingRight) rawX else -rawX
        val normY = ((py / roiH.toFloat()) * 2f - 1f).coerceIn(-1f, 1f)
        return NormalizedPoint(normX, normY)
    }

    fun reset() {
        prevBallNorm = null
        prevBallTimestampMs = 0L
        directionVoteStreak = 0
    }
}
