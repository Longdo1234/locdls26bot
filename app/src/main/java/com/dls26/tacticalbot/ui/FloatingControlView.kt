package com.dls26.tacticalbot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.dls26.tacticalbot.R
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class FloatingControlView(
    context: Context,
    private val onTogglePlay: () -> Unit,
    private val onToggleDebug: () -> Unit,
    private val onStopService: () -> Unit,
    private val onDragBy: (dx: Int, dy: Int) -> Unit
) : LinearLayout(context) {

    private var isPlaying = false
    private val playButton: TextView
    private val debugButton: TextView
    private val stopButton: TextView

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_pill)
        setPadding(18, 10, 18, 10)

        val dragHandle = TextView(context).apply {
            text = "⠿"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 18f
            setPadding(4, 0, 14, 0)
        }

        playButton = createChip("▶ RUN", "#10B981")
        debugButton = createChip("HUD", "#06B6D4")
        stopButton = createChip("✕", "#EF4444")

        playButton.setOnClickListener {
            isPlaying = !isPlaying
            playButton.text = if (isPlaying) "⏸ PAUSE" else "▶ RUN"
            playButton.setTextColor(
                if (isPlaying) Color.parseColor("#F59E0B") else Color.parseColor("#10B981")
            )
            onTogglePlay()
        }

        debugButton.setOnClickListener { onToggleDebug() }
        stopButton.setOnClickListener { onStopService() }

        addView(dragHandle)
        addView(playButton)
        addView(debugButton)
        addView(stopButton)

        dragHandle.setOnTouchListener { _, event -> handleDrag(event) }
    }

    private fun createChip(label: String, colorHex: String): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.parseColor(colorHex))
            textSize = 13f
            paint.isFakeBoldText = true
            setPadding(14, 6, 14, 6)
        }
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastRawX).toInt()
                val dy = (event.rawY - lastRawY).toInt()
                if (abs(dx) > 2 || abs(dy) > 2) {
                    isDragging = true
                    onDragBy(dx, dy)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> return isDragging
        }
        return false
    }
}
