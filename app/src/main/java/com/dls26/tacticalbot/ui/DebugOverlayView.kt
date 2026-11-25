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

    // My Team = Red Kit
    private val allyRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.FILL
    }

    private val gkRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FACC15")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val opponentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.FILL
    }

    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FDE047")
        style = Paint.Style.FILL
    }

    private val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22C55E")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val attackArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.STROKE
        strokeWidth = 5f
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
        textSize = 26f
        isFakeBoldText = true
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
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

        // 2. Minimap Detected Entities
        if (state != null) {
            for (pt in state.minimap.rawAlliesScreen) {
                canvas.drawCircle(pt.x, pt.y, 7f, allyRedPaint)
            }
            state.minimap.rawRedGoalkeeperScreen?.let { gk ->
                canvas.drawCircle(gk.x, gk.y, 13f, gkRingPaint)
                canvas.drawText("GK", gk.x, gk.y - 16f, labelTextPaint)
            }
            for (pt in state.minimap.rawOpponentsScreen) {
                canvas.drawCircle(pt.x, pt.y, 7f, opponentPaint)
            }
            state.minimap.rawBallScreen?.let { ballPt ->
                canvas.drawCircle(ballPt.x, ballPt.y, 9f, ballPaint)
            }

            // Attack Direction Arrow above Minimap (opposite Red Goalkeeper)
            val arrowY = (mmRect.top - 18f).coerceAtLeast(110f)
            val arrowLen = 84f
            val cx = mmRect.centerX()
            if (state.minimap.attackingRight) {
                canvas.drawLine(cx - arrowLen / 2f, arrowY, cx + arrowLen / 2f, arrowY, attackArrowPaint)
                canvas.drawLine(cx + arrowLen / 2f - 14f, arrowY - 10f, cx + arrowLen / 2f, arrowY, attackArrowPaint)
                canvas.drawLine(cx + arrowLen / 2f - 14f, arrowY + 10f, cx + arrowLen / 2f, arrowY, attackArrowPaint)
            } else {
                canvas.drawLine(cx + arrowLen / 2f, arrowY, cx - arrowLen / 2f, arrowY, attackArrowPaint)
                canvas.drawLine(cx - arrowLen / 2f + 14f, arrowY - 10f, cx - arrowLen / 2f, arrowY, attackArrowPaint)
                canvas.drawLine(cx - arrowLen / 2f + 14f, arrowY + 10f, cx - arrowLen / 2f, arrowY, attackArrowPaint)
            }
        }

        // 3. Virtual Joystick Ring + Predicted Movement Vector
        val joyX = cfg.joystickCenterX * w
        val joyY = cfg.joystickCenterY * h
        val joyR = cfg.joystickRadiusNorm * min(w, h)
        canvas.drawCircle(joyX, joyY, joyR, controlPaint)

        if (isBotActive && action != null && action.joystickActive) {
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
        val statusStr = if (isBotActive) "BOT ON" else "PAUSED (NHẤN RUN)"
        val aiState = action?.state?.name ?: "IDLE"
        val sceneStr = state?.scene?.name ?: "WAIT"
        val possStr = state?.possession?.name ?: "-"
        val dirStr = if (state?.minimap?.attackingRight == false) "ATK ←" else "ATK →"
        val fps = state?.currentFps ?: 0f
        val lat = state?.processingLatencyMs ?: 0L
        val alliesCount = state?.minimap?.allies?.size ?: 0
        val oppsCount = state?.minimap?.opponents?.size ?: 0

        val hudLine1 = "[$statusStr] $aiState | $dirStr | $sceneStr ($possStr)"
        val hudLine2 = "FPS:${"%.1f".format(fps)} ${lat}ms | Red:$alliesCount Opp:$oppsCount | ${action?.debugNote ?: ""}"

        val bannerWidth = min(w - 40f, 860f)
        val bannerLeft = (w - bannerWidth) / 2f
        val bannerRect = RectF(bannerLeft, 12f, bannerLeft + bannerWidth, 92f)
        canvas.drawRoundRect(bannerRect, 14f, 14f, hudBgPaint)
        hudTextPaint.textSize = 25f
        canvas.drawText(hudLine1, bannerLeft + 18f, 44f, hudTextPaint)
        hudTextPaint.textSize = 21f
        canvas.drawText(hudLine2, bannerLeft + 18f, 78f, hudTextPaint)
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
