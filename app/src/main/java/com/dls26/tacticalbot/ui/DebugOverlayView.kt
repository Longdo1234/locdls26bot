package com.dls26.tacticalbot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.model.ActionButton
import com.dls26.tacticalbot.model.BotAction
import com.dls26.tacticalbot.model.GameState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class DebugOverlayView(context: Context) : View(context) {

    private var gameState: GameState? = null
    private var botAction: BotAction? = null
    private var botConfig: BotConfig = BotConfig()
    private var isBotActive: Boolean = false

    private val roiBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC10B981")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val allyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22C55E")
        style = Paint.Style.FILL
    }

    private val opponentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.FILL
    }

    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FACC15")
        style = Paint.Style.FILL
    }

    private val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val activeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBF59E0B")
        style = Paint.Style.FILL
    }

    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BB0F172A")
        style = Paint.Style.FILL
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun updateDebugData(
        state: GameState,
        action: BotAction,
        config: BotConfig,
        active: Boolean
    ) {
        this.gameState = state
        this.botAction = action
        this.botConfig = config
        this.isBotActive = active
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cfg = botConfig
        val state = gameState
        val action = botAction

        // 1. Minimap ROI Bounding Box
        val mmRect = RectF(
            cfg.minimapLeft * w,
            cfg.minimapTop * h,
            cfg.minimapRight * w,
            cfg.minimapBottom * h
        )
        canvas.drawRect(mmRect, roiBoxPaint)

        // 2. Minimap Detected Dots (Ally=Green, Opponent=Red, Ball=Yellow)
        if (state != null) {
            for (pt in state.minimap.rawAlliesScreen) {
                canvas.drawCircle(pt.x, pt.y, 7f, allyPaint)
            }
            for (pt in state.minimap.rawOpponentsScreen) {
                canvas.drawCircle(pt.x, pt.y, 7f, opponentPaint)
            }
            state.minimap.rawBallScreen?.let { ballPt ->
                canvas.drawCircle(ballPt.x, ballPt.y, 9f, ballPaint)
            }
        }

        // 3. Virtual Joystick Ring + Predicted Movement Vector
        val joyX = cfg.joystickCenterX * w
        val joyY = cfg.joystickCenterY * h
        val joyR = cfg.joystickRadiusNorm * min(w, h)
        canvas.drawCircle(joyX, joyY, joyR, controlPaint)

        if (action != null && action.joystickActive) {
            val endX = joyX + cos(action.joystickAngleRad) * joyR * action.joystickMagnitude
            val endY = joyY + sin(action.joystickAngleRad) * joyR * action.joystickMagnitude
            canvas.drawLine(joyX, joyY, endX, endY, vectorPaint)
            canvas.drawCircle(endX, endY, 10f, ballPaint)
        }

        // 4. Action Buttons A / B / C / Continue
        drawButtonMarker(canvas, cfg.buttonAX * w, cfg.buttonAY * h, "A", action?.button == ActionButton.A_SHOOT_OR_TACKLE)
        drawButtonMarker(canvas, cfg.buttonBX * w, cfg.buttonBY * h, "B", action?.button == ActionButton.B_LOW_PASS_OR_PRESS)
        drawButtonMarker(canvas, cfg.buttonCX * w, cfg.buttonCY * h, "C", action?.button == ActionButton.C_LOFTED_PASS_OR_SWITCH)
        if (action?.button == ActionButton.MENU_CONTINUE) {
            drawButtonMarker(canvas, cfg.continueBtnX * w, cfg.continueBtnY * h, "NEXT", true)
        }

        // 5. Top Tactical HUD Banner
        val statusStr = if (isBotActive) "BOT ON" else "PAUSED"
        val aiState = action?.state?.name ?: "IDLE"
        val sceneStr = state?.scene?.name ?: "WAIT"
        val possStr = state?.possession?.name ?: "-"
        val fps = state?.currentFps ?: 0f
        val lat = state?.processingLatencyMs ?: 0L
        val alliesCount = state?.minimap?.allies?.size ?: 0
        val oppsCount = state?.minimap?.opponents?.size ?: 0

        val hudLine1 = "[$statusStr] $aiState | $sceneStr ($possStr)"
        val hudLine2 = "FPS: ${"%.1f".format(fps)} | ${lat}ms | Ally:$alliesCount Opp:$oppsCount | ${action?.debugNote ?: ""}"

        val bannerWidth = min(w - 40f, 820f)
        val bannerLeft = (w - bannerWidth) / 2f
        val bannerRect = RectF(bannerLeft, 14f, bannerLeft + bannerWidth, 94f)
        canvas.drawRoundRect(bannerRect, 14f, 14f, hudBgPaint)
        canvas.drawText(hudLine1, bannerLeft + 18f, 46f, hudTextPaint)
        canvas.drawText(hudLine2, bannerLeft + 18f, 80f, hudTextPaint.apply { textSize = 22f })
        hudTextPaint.textSize = 28f
    }

    private fun drawButtonMarker(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        label: String,
        isPressed: Boolean
    ) {
        val radius = 30f
        if (isPressed) {
            canvas.drawCircle(cx, cy, radius, activeButtonPaint)
        }
        canvas.drawCircle(cx, cy, radius, controlPaint)
        canvas.drawText(label, cx, cy + 8f, labelTextPaint)
    }
}
