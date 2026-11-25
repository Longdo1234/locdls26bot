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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Minimap Radar Scanner tailored for DLS 26:
 * - ALL red dots (including Red Goalkeeper + outfield players) = My Team (Allies).
 * - Non-red player dots (excluding red mask & ball) = Opponents.
 * - Auto-detects Red Goalkeeper near left (x < 0.15) or right (x > 0.85) goal edge
 *   and sets attack direction opposite the Red Goalkeeper.
 */
class MinimapScanner {

    private var prevBallNorm: NormalizedPoint? = null
    private var prevBallTimestampMs: Long = 0L
    private var inferredAttackingRight: Boolean = true
    private var gkVoteStreak: Int = 0
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
        val redMaskLow = Mat()
        val redMaskHigh = Mat()
        val allyRedMask = Mat()
        val ballMask = Mat()
        val nonRedBlueYellow = Mat()
        val nonRedWhite = Mat()
        val oppCandidateMask = Mat()
        val excludeAllyAndBall = Mat()
        val notExclude = Mat()
        val oppMask = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))

        try {
            Imgproc.cvtColor(roiRgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

            // Minimap pitch background confidence
            Core.inRange(hsv, Scalar(30.0, 20.0, 25.0), Scalar(95.0, 255.0, 200.0), pitchMask)
            val pitchRatio = Core.countNonZero(pitchMask).toFloat() / (roiW * roiH).toFloat()

            // 1. MY TEAM (ALLY + RED GOALKEEPER): Dual HSV red range H in [0..10] U [170..180], S>=90, V>=80
            Core.inRange(hsv, Scalar(0.0, 90.0, 80.0), Scalar(10.0, 255.0, 255.0), redMaskLow)
            Core.inRange(hsv, Scalar(170.0, 90.0, 80.0), Scalar(180.0, 255.0, 255.0), redMaskHigh)
            Core.bitwise_or(redMaskLow, redMaskHigh, allyRedMask)
            Imgproc.morphologyEx(allyRedMask, allyRedMask, Imgproc.MORPH_OPEN, kernel)

            // 2. BALL MARKER: Small very bright white/yellow-white dot
            Core.inRange(hsv, Scalar(0.0, 0.0, 232.0), Scalar(45.0, 65.0, 255.0), ballMask)
            Imgproc.morphologyEx(ballMask, ballMask, Imgproc.MORPH_OPEN, kernel)

            // 3. OPPONENT DOTS: Non-red colorful or bright dots (blue, cyan, yellow, orange, white) excluding red allies & ball
            Core.inRange(hsv, Scalar(12.0, 95.0, 100.0), Scalar(165.0, 255.0, 255.0), nonRedBlueYellow)
            Core.inRange(hsv, Scalar(0.0, 0.0, 175.0), Scalar(180.0, 70.0, 231.0), nonRedWhite)
            Core.bitwise_or(nonRedBlueYellow, nonRedWhite, oppCandidateMask)

            Core.bitwise_or(allyRedMask, ballMask, excludeAllyAndBall)
            Core.bitwise_not(excludeAllyAndBall, notExclude)
            Core.bitwise_and(oppCandidateMask, notExclude, oppMask)
            Imgproc.morphologyEx(oppMask, oppMask, Imgproc.MORPH_OPEN, kernel)

            val minDotArea = max(3.0, (roiW * roiH) * 0.00012)
            val maxDotArea = (roiW * roiH) * 0.015

            val rawAllies = extractCentroids(allyRedMask, minDotArea, maxDotArea, 11)
            val rawBalls = extractCentroids(ballMask, max(2.0, minDotArea * 0.55), maxDotArea * 0.55, 3)
            val rawBall = selectBestBall(rawBalls, roiW, roiH)

            // Filter out opponent blobs too close to ball or pitch line edges
            val rawOpps = extractCentroids(oppMask, minDotArea, maxDotArea, 11)
                .filter { opp ->
                    val nearBall = rawBall != null &&
                        kotlin.math.hypot(opp.first - rawBall.first, opp.second - rawBall.second) < 4.5f
                    !nearBall
                }

            // 4. Identify Red Goalkeeper & Auto Attack Direction (opposite Red GK)
            val rawRedGk = detectRedGoalkeeper(rawAllies, roiW, roiH)
            val attackingRight = resolveAttackDirectionFromRedGk(
                setting = config.attackDirection,
                rawRedGk = rawRedGk,
                rawAllies = rawAllies,
                rawOpps = rawOpps,
                roiW = roiW
            )

            val scaleX = screenWidth.toFloat() / frameW.toFloat()
            val scaleY = screenHeight.toFloat() / frameH.toFloat()

            val alliesNorm = rawAllies.map { toNormalized(it.first, it.second, roiW, roiH, attackingRight) }
            val oppsNorm = rawOpps.map { toNormalized(it.first, it.second, roiW, roiH, attackingRight) }
            val ballNorm = rawBall?.let { toNormalized(it.first, it.second, roiW, roiH, attackingRight) }
            val redGkNorm = rawRedGk?.let { toNormalized(it.first, it.second, roiW, roiH, attackingRight) }

            val alliesScreen = rawAllies.map { ScreenPoint((roiX + it.first) * scaleX, (roiY + it.second) * scaleY) }
            val oppsScreen = rawOpps.map { ScreenPoint((roiX + it.first) * scaleX, (roiY + it.second) * scaleY) }
            val ballScreen = rawBall?.let { ScreenPoint((roiX + it.first) * scaleX, (roiY + it.second) * scaleY) }
            val redGkScreen = rawRedGk?.let { ScreenPoint((roiX + it.first) * scaleX, (roiY + it.second) * scaleY) }

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

            // Active player: red ally dot closest to the ball
            val activePlayer = when {
                ballNorm != null && alliesNorm.isNotEmpty() -> alliesNorm.minByOrNull { it.distanceTo(ballNorm) }
                alliesNorm.isNotEmpty() -> alliesNorm.minByOrNull { it.distanceTo(NormalizedPoint(0f, 0f)) }
                else -> null
            }

            val totalDots = rawAllies.size + rawOpps.size
            val dotScore = (totalDots.toFloat() / 10f).coerceIn(0f, 1f)
            val pitchScore = (pitchRatio / 0.22f).coerceIn(0f, 1f)
            val confidence = (0.55f * dotScore + 0.45f * pitchScore).coerceIn(0f, 1f)

            return MinimapDetection(
                allies = alliesNorm,
                opponents = oppsNorm,
                ball = ballNorm,
                ballVx = vx,
                ballVy = vy,
                activePlayer = activePlayer,
                redGoalkeeperNorm = redGkNorm,
                rawAlliesScreen = alliesScreen,
                rawOpponentsScreen = oppsScreen,
                rawBallScreen = ballScreen,
                rawRedGoalkeeperScreen = redGkScreen,
                attackingRight = attackingRight,
                minimapConfidence = confidence
            )
        } finally {
            kernel.release()
            oppMask.release()
            notExclude.release()
            excludeAllyAndBall.release()
            oppCandidateMask.release()
            nonRedWhite.release()
            nonRedBlueYellow.release()
            ballMask.release()
            allyRedMask.release()
            redMaskHigh.release()
            redMaskLow.release()
            pitchMask.release()
            hsv.release()
            bgr.release()
            roiRgba.release()
        }
    }

    /**
     * Finds the Red Goalkeeper dot sitting deep near either left goal line (x_norm < 0.15)
     * or right goal line (x_norm > 0.85) around the vertical center of the minimap.
     */
    private fun detectRedGoalkeeper(
        rawAllies: List<Pair<Float, Float>>,
        roiW: Int,
        roiH: Int
    ): Pair<Float, Float>? {
        if (rawAllies.isEmpty() || roiW <= 0 || roiH <= 0) return null

        val leftCandidates = rawAllies.filter { (x, y) ->
            val xn = x / roiW.toFloat()
            val yn = y / roiH.toFloat()
            xn < 0.15f && yn in 0.22f..0.78f
        }
        val rightCandidates = rawAllies.filter { (x, y) ->
            val xn = x / roiW.toFloat()
            val yn = y / roiH.toFloat()
            xn > 0.85f && yn in 0.22f..0.78f
        }

        val leftGk = leftCandidates.minByOrNull { it.first }
        val rightGk = rightCandidates.maxByOrNull { it.first }

        return when {
            leftGk != null && rightGk == null -> leftGk
            rightGk != null && leftGk == null -> rightGk
            leftGk != null && rightGk != null -> {
                val leftEdgeDist = leftGk.first / roiW.toFloat()
                val rightEdgeDist = 1f - (rightGk.first / roiW.toFloat())
                if (leftEdgeDist <= rightEdgeDist) leftGk else rightGk
            }
            else -> null
        }
    }

    private fun resolveAttackDirectionFromRedGk(
        setting: AttackDirection,
        rawRedGk: Pair<Float, Float>?,
        rawAllies: List<Pair<Float, Float>>,
        rawOpps: List<Pair<Float, Float>>,
        roiW: Int
    ): Boolean {
        return when (setting) {
            AttackDirection.LEFT_TO_RIGHT -> true
            AttackDirection.RIGHT_TO_LEFT -> false
            AttackDirection.AUTO -> {
                val voteRight: Boolean? = when {
                    rawRedGk != null && roiW > 0 -> {
                        val gkXNorm = rawRedGk.first / roiW.toFloat()
                        // Red GK on Left -> attack Right (+1); Red GK on Right -> attack Left (-1)
                        gkXNorm < 0.5f
                    }
                    rawAllies.size >= 4 && rawOpps.size >= 4 && roiW > 0 -> {
                        val allyAvgX = rawAllies.map { it.first / roiW.toFloat() }.average()
                        val oppAvgX = rawOpps.map { it.first / roiW.toFloat() }.average()
                        if (abs(allyAvgX - oppAvgX) > 0.08) allyAvgX < oppAvgX else null
                    }
                    else -> null
                }

                if (voteRight != null) {
                    if (voteRight == pendingDirectionRight) {
                        gkVoteStreak = min(20, gkVoteStreak + 1)
                        // Faster confirmation when Red GK is directly detected
                        val neededStreak = if (rawRedGk != null) 6 else 14
                        if (gkVoteStreak >= neededStreak) {
                            inferredAttackingRight = voteRight
                        }
                    } else {
                        pendingDirectionRight = voteRight
                        gkVoteStreak = 1
                    }
                }
                inferredAttackingRight
            }
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
        gkVoteStreak = 0
    }
}
