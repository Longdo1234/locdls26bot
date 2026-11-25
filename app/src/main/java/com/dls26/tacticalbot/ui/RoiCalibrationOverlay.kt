package com.dls26.tacticalbot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dls26.tacticalbot.config.BotConfig
import kotlin.math.hypot
import kotlin.math.min

/**
 * Interactive full-screen ROI Calibration overlay so users can drag Minimap corners,
 * Joystick center, and A/B/C/Continue buttons directly over DLS 26 on any aspect ratio.
 */
@SuppressLint("ViewConstructor")
class RoiCalibrationOverlay(
    context: Context,
    initialConfig: BotConfig,
    private val onSave: (BotConfig) -> Unit,
    private val onCancel: () -> Unit
) : FrameLayout(context) {

    private var currentConfig = initialConfig
    private val canvasView = CalibrationCanvasView(context)

    private enum class DragTarget {
        NONE, MM_TL, MM_BR, JOYSTICK, BTN_A, BTN_B, BTN_C, BTN_CONT
    }

    init {
        setBackgroundColor(Color.parseColor("#66000000"))
        addView(
            canvasView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E60F172A"))
            setPadding(20, 14, 20, 14)
        }

        val hint = TextView(context).apply {
            text = "Kéo các điểm tròn để căn chỉnh Minimap & Nút A/B/C"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, 20, 0)
        }

        val btnReset = Button(context).apply {
            text = "Mặc định"
            setOnClickListener {
                currentConfig = BotConfig(
                    tacticalMode = currentConfig.tacticalMode,
                    attackDirection = currentConfig.attackDirection,
                    debugOverlayEnabled = currentConfig.debugOverlayEnabled,
                    autoFarmSkipMenus = currentConfig.autoFarmSkipMenus
                )
                canvasView.invalidate()
            }
        }

        val btnCancel = Button(context).apply {
            text = "Hủy"
            setOnClickListener { onCancel() }
        }

        val btnSave = Button(context).apply {
            text = "Lưu ROI"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.WHITE)
            setOnClickListener { onSave(currentConfig) }
        }

        toolbar.addView(hint)
        toolbar.addView(btnReset)
        toolbar.addView(btnCancel)
        toolbar.addView(btnSave)

        addView(
            toolbar,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = 20
            }
        )
    }

    private inner class CalibrationCanvasView(context: Context) : View(context) {
        private var dragTarget = DragTarget.NONE

        private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#10B981")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F59E0B")
            style = Paint.Style.FILL
        }
        private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC06B6D4")
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val mm = RectF(
                currentConfig.minimapLeft * w,
                currentConfig.minimapTop * h,
                currentConfig.minimapRight * w,
                currentConfig.minimapBottom * h
            )
            canvas.drawRect(mm, rectPaint)
            canvas.drawText("MINIMAP ROI", mm.centerX(), mm.top - 12f, textPaint)

            // Corner handles for Minimap
            canvas.drawCircle(mm.left, mm.top, 24f, handlePaint)
            canvas.drawCircle(mm.right, mm.bottom, 24f, handlePaint)

            // Joystick handle
            val jx = currentConfig.joystickCenterX * w
            val jy = currentConfig.joystickCenterY * h
            val jr = currentConfig.joystickRadiusNorm * min(w, h)
            canvas.drawCircle(jx, jy, jr, rectPaint)
            canvas.drawCircle(jx, jy, 28f, buttonPaint)
            canvas.drawText("JOY", jx, jy + 9f, textPaint)

            // Action Buttons
            drawHandle(canvas, currentConfig.buttonAX * w, currentConfig.buttonAY * h, "A")
            drawHandle(canvas, currentConfig.buttonBX * w, currentConfig.buttonBY * h, "B")
            drawHandle(canvas, currentConfig.buttonCX * w, currentConfig.buttonCY * h, "C")
            drawHandle(canvas, currentConfig.continueBtnX * w, currentConfig.continueBtnY * h, "NEXT")
        }

        private fun drawHandle(canvas: Canvas, x: Float, y: Float, label: String) {
            canvas.drawCircle(x, y, 32f, buttonPaint)
            canvas.drawText(label, x, y + 9f, textPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            val x = event.x
            val y = event.y

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragTarget = pickNearestTarget(x, y, w, h)
                    return dragTarget != DragTarget.NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (x / w).coerceIn(0.02f, 0.98f)
                    val ny = (y / h).coerceIn(0.02f, 0.98f)
                    currentConfig = when (dragTarget) {
                        DragTarget.MM_TL -> currentConfig.copy(
                            minimapLeft = nx.coerceAtMost(currentConfig.minimapRight - 0.08f),
                            minimapTop = ny.coerceAtMost(currentConfig.minimapBottom - 0.08f)
                        )
                        DragTarget.MM_BR -> currentConfig.copy(
                            minimapRight = nx.coerceAtLeast(currentConfig.minimapLeft + 0.08f),
                            minimapBottom = ny.coerceAtLeast(currentConfig.minimapTop + 0.08f)
                        )
                        DragTarget.JOYSTICK -> currentConfig.copy(joystickCenterX = nx, joystickCenterY = ny)
                        DragTarget.BTN_A -> currentConfig.copy(buttonAX = nx, buttonAY = ny)
                        DragTarget.BTN_B -> currentConfig.copy(buttonBX = nx, buttonBY = ny)
                        DragTarget.BTN_C -> currentConfig.copy(buttonCX = nx, buttonCY = ny)
                        DragTarget.BTN_CONT -> currentConfig.copy(continueBtnX = nx, continueBtnY = ny)
                        DragTarget.NONE -> currentConfig
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragTarget = DragTarget.NONE
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun pickNearestTarget(x: Float, y: Float, w: Float, h: Float): DragTarget {
            val candidates = listOf(
                DragTarget.MM_TL to (currentConfig.minimapLeft * w to currentConfig.minimapTop * h),
                DragTarget.MM_BR to (currentConfig.minimapRight * w to currentConfig.minimapBottom * h),
                DragTarget.JOYSTICK to (currentConfig.joystickCenterX * w to currentConfig.joystickCenterY * h),
                DragTarget.BTN_A to (currentConfig.buttonAX * w to currentConfig.buttonAY * h),
                DragTarget.BTN_B to (currentConfig.buttonBX * w to currentConfig.buttonBY * h),
                DragTarget.BTN_C to (currentConfig.buttonCX * w to currentConfig.buttonCY * h),
                DragTarget.BTN_CONT to (currentConfig.continueBtnX * w to currentConfig.continueBtnY * h)
            )
            val nearest = candidates.minByOrNull { hypot(it.second.first - x, it.second.second - y) }
            return if (nearest != null && hypot(nearest.second.first - x, nearest.second.second - y) < 85f) {
                nearest.first
            } else {
                DragTarget.NONE
            }
        }
    }

    companion object {
        fun show(
            context: Context,
            windowManager: WindowManager,
            initialConfig: BotConfig,
            onSaved: (BotConfig) -> Unit,
            onDismissed: () -> Unit = {}
        ): RoiCalibrationOverlay {
            var overlayRef: RoiCalibrationOverlay? = null
            val overlay = RoiCalibrationOverlay(
                context = context,
                initialConfig = initialConfig,
                onSave = { updated ->
                    onSaved(updated)
                    overlayRef?.let { runCatching { windowManager.removeView(it) } }
                },
                onCancel = {
                    onDismissed()
                    overlayRef?.let { runCatching { windowManager.removeView(it) } }
                }
            )
            overlayRef = overlay
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(overlay, params)
            return overlay
        }
    }
}
