package com.dls26.tacticalbot.vision

import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.model.AttackDirection
import com.dls26.tacticalbot.model.MinimapDetection
import com.dls26.tacticalbot.model.NormalizedPoint
import com.dls26.tacticalbot.model.ScreenPoint
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * High-precision OpenCV Minimap Scanner for DLS 26:
 * 1. Validates green grass pitch ratio (H in [35..85], S>=40, V>=40) >= 25% before emitting detections.
 * 2. Red Team (My Team + Red GK): Dual HSV H in [0..10] U [170..180], S>=100, V>=80 + circularity >= 0.55.
 * 3. Ball: White circle (H in [0..180], S<=45, V>=210) + circularity >= 0.70 + aspectRatio in [0.7..1.4]
 *    + small ball area (strictly rejecting white pitch lines).
 * 4. Opponents: Full ROI minus grass, red, and white masks + player dot size & circularity >= 0.55.
 * 5. Attack Direction: Locked opposite Red GK near horizontal edge (<0.18 or >0.82) with confirmFrames >= 15.
 */
class MinimapScanner {

    private var prevBallNorm: NormalizedPoint? = null
    private var prevBallTimestampMs: Long = 0L
    private var inferredAttackingRight: Boolean = true
    private var confirmFrames: Int = 0
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
        val roiArea = (roiW * roiH).toDouble().coerceAtLeast(1.0)

        val roiRgba = Mat(rgbaFrame, Rect(roiX, roiY, roiW, roiH))
        val bgr = Mat()
        val hsv = Mat()
        val grassMask = Mat()
        val redMaskLow = Mat()
        val redMaskHigh = Mat()
        val allyRedMask = Mat()
        val whiteMask = Mat()
        val excludeTemp = Mat()
        val excludeAll = Mat()
        val oppMask = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))

        try {
            Imgproc.cvtColor(roiRgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

            // 1. Validate Minimap via Green Grass Pitch Ratio (H in [35..85], S>=40, V>=40)
            Core.inRange(hsv, Scalar(35.0, 40.0, 40.0), Scalar(85.0, 255.0, 255.0), grassMask)
            val grassRatio = (Core.countNonZero(grassMask).toDouble() / roiArea).toFloat()

            if (grassRatio < MIN_GRASS_RATIO) {
                return invalidMinimap(grassRatio)
            }

            // 2. MY TEAM (RED OUTFIELD PLAYERS + RED GOALKEEPER)
            Core.inRange(hsv, Scalar(0.0, 100.0, 80.0), Scalar(10.0, 255.0, 255.0), redMaskLow)
            Core.inRange(hsv, Scalar(170.0, 100.0, 80.0), Scalar(180.0, 255.0, 255.0), redMaskHigh)
            Core.bitwise_or(redMaskLow, redMaskHigh, allyRedMask)
            Imgproc.morphologyEx(allyRedMask, allyRedMask, Imgproc.MORPH_OPEN, kernel)

            // 3. WHITE MASK (BALL + PITCH LINES)
            Core.inRange(hsv, Scalar(0.0, 0.0, 210.0), Scalar(180.0, 45.0, 255.0), whiteMask)

            // 4. OPPONENTS = FULL ROI - GRASS - RED - WHITE
            Core.bitwise_or(grassMask, allyRedMask, excludeTemp)
            Core.bitwise_or(excludeTemp, whiteMask, excludeAll)
            Core.bitwise_not(excludeAll, oppMask)
            Imgproc.morphologyEx(oppMask, oppMask, Imgproc.MORPH_OPEN, kernel)

            // Adaptive area bounds relative to ROI size
            val minDotArea = max(4.0, roiArea * 0.00018)
            val maxDotArea = roiArea * 0.012
            val minBallArea = max(3.0, roiArea * 0.00010)
            val maxBallArea = roiArea * 0.0055

            val rawAllies = extractPlayerDots(allyRedMask, minDotArea, maxDotArea, 0.55, 11)
            val rawBallCandidates = extractBallCircles(whiteMask, minBallArea, maxBallArea, 0.70, 0.7, 1.4)
            val rawBall = selectBestBall(rawBallCandidates, roiW, roiH)

            val rawOpps = extractPlayerDots(oppMask, minDotArea, maxDotArea, 0.55, 11)
                .filter { opp ->
                    val nearBall = rawBall != null && hypot(opp.first - rawBall.first, opp.second - rawBall.second) < 5f
                    !nearBall
                }

            // 5. Red Goalkeeper Detection & Hysteresis-Locked Attack Direction (confirmFrames >= 15)
            val rawRedGk = detectRedGoalkeeper(rawAllies, roiW, roiH)
            val attackingRight = resolveAttackDirection(config.attackDirection, rawRedGk, roiW)

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

            // Active player: Red ally dot closest to the ball
            val activePlayer = when {
                ballNorm != null && alliesNorm.isNotEmpty() -> alliesNorm.minByOrNull { it.distanceTo(ballNorm) }
                alliesNorm.isNotEmpty() -> alliesNorm.minByOrNull { it.distanceTo(NormalizedPoint(0f, 0f)) }
                else -> null
            }

            val totalDots = rawAllies.size + rawOpps.size
            val confidence = (0.5f * (grassRatio / 0.5f).coerceIn(0f, 1f) +
                0.5f * (totalDots.toFloat() / 10f).coerceIn(0f, 1f)).coerceIn(0f, 1f)

            return MinimapDetection(
                isValidMinimap = true,
                grassRatio = grassRatio,
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
            excludeAll.release()
            excludeTemp.release()
            whiteMask.release()
            allyRedMask.release()
            redMaskHigh.release()
            redMaskLow.release()
            grassMask.release()
            hsv.release()
            bgr.release()
            roiRgba.release()
        }
    }

    private fun invalidMinimap(grassRatio: Float): MinimapDetection {
        return MinimapDetection(
            isValidMinimap = false,
            grassRatio = grassRatio,
            allies = emptyList(),
            opponents = emptyList(),
            ball = null,
            ballVx = 0f,
            ballVy = 0f,
            activePlayer = null,
            redGoalkeeperNorm = null,
            rawAlliesScreen = emptyList(),
            rawOpponentsScreen = emptyList(),
            rawBallScreen = null,
            rawRedGoalkeeperScreen = null,
            attackingRight = inferredAttackingRight,
            minimapConfidence = 0f
        )
    }

    private fun extractPlayerDots(
        mask: Mat,
        minArea: Double,
        maxArea: Double,
        minCircularity: Double,
        maxCount: Int
    ): List<Pair<Float, Float>> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val results = ArrayList<Pair<Double, Pair<Float, Float>>>()
        try {
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area !in minArea..maxArea) continue

                val contour2f = MatOfPoint2f(*contour.toArray())
                val perimeter = try {
                    Imgproc.arcLength(contour2f, true)
                } finally {
                    contour2f.release()
                }
                if (perimeter <= 0.0) continue

                val circularity = (4.0 * PI * area) / (perimeter * perimeter)
                if (circularity < minCircularity) continue

                val m = Imgproc.moments(contour)
                if (m.m00 > 0.0) {
                    val cx = (m.m10 / m.m00).toFloat()
                    val cy = (m.m01 / m.m00).toFloat()
                    results.add(area to (cx to cy))
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

    /**
     * Strictly extracts only circular white ball blobs (circularity >= 0.70, aspectRatio in [0.7, 1.4],
     * small area) so white pitch touchlines/halfway lines/penalty box lines are completely eliminated.
     */
    private fun extractBallCircles(
        whiteMask: Mat,
        minBallArea: Double,
        maxBallArea: Double,
        minCircularity: Double,
        minAspectRatio: Double,
        maxAspectRatio: Double
    ): List<Pair<Float, Float>> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val candidates = ArrayList<Pair<Double, Pair<Float, Float>>>()
        try {
            Imgproc.findContours(whiteMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area !in minBallArea..maxBallArea) continue

                val rect = Imgproc.boundingRect(contour)
                if (rect.height <= 0) continue
                val aspectRatio = rect.width.toDouble() / rect.height.toDouble()
                if (aspectRatio !in minAspectRatio..maxAspectRatio) continue

                val contour2f = MatOfPoint2f(*contour.toArray())
                val perimeter = try {
                    Imgproc.arcLength(contour2f, true)
                } finally {
                    contour2f.release()
                }
                if (perimeter <= 0.0) continue

                val circularity = (4.0 * PI * area) / (perimeter * perimeter)
                if (circularity < minCircularity) continue

                val m = Imgproc.moments(contour)
                if (m.m00 > 0.0) {
                    val cx = (m.m10 / m.m00).toFloat()
                    val cy = (m.m01 / m.m00).toFloat()
                    candidates.add(circularity to (cx to cy))
                }
            }
        } finally {
            hierarchy.release()
            for (c in contours) c.release()
        }
        return candidates
            .sortedByDescending { it.first }
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
            toNormalized(cx, cy, roiW, roiH, inferredAttackingRight).distanceTo(prev)
        }
    }

    /**
     * Locates the Red Goalkeeper closest to either horizontal goal edge (x_norm < 0.18 or x_norm > 0.82).
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
            xn < 0.18f && yn in 0.18f..0.82f
        }
        val rightCandidates = rawAllies.filter { (x, y) ->
            val xn = x / roiW.toFloat()
            val yn = y / roiH.toFloat()
            xn > 0.82f && yn in 0.18f..0.82f
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

    /**
     * Locks attack direction opposite the Red Goalkeeper with >= 15 consecutive confirmation frames
     * to prevent false direction flips when the goalkeeper is momentarily occluded.
     */
    private fun resolveAttackDirection(
        setting: AttackDirection,
        rawRedGk: Pair<Float, Float>?,
        roiW: Int
    ): Boolean {
        return when (setting) {
            AttackDirection.LEFT_TO_RIGHT -> true
            AttackDirection.RIGHT_TO_LEFT -> false
            AttackDirection.AUTO -> {
                if (rawRedGk != null && roiW > 0) {
                    val gkXNorm = rawRedGk.first / roiW.toFloat()
                    val voteRight = gkXNorm < 0.5f // GK on Left -> attack Right; GK on Right -> attack Left
                    if (voteRight == pendingDirectionRight) {
                        confirmFrames = min(30, confirmFrames + 1)
                        if (confirmFrames >= CONFIRM_FRAMES_THRESHOLD) {
                            inferredAttackingRight = voteRight
                        }
                    } else {
                        pendingDirectionRight = voteRight
                        confirmFrames = 1
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
        confirmFrames = 0
    }

    companion object {
        private const val MIN_GRASS_RATIO = 0.25f
        private const val CONFIRM_FRAMES_THRESHOLD = 15
    }
}
