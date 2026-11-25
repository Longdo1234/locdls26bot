package com.dls26.tacticalbot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.dls26.tacticalbot.R
import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.model.AttackDirection
import com.dls26.tacticalbot.model.TacticalMode
import kotlin.math.abs

/**
 * Draggable In-HUD Settings panel rendered directly over DLS 26 so the user can:
 * - Adjust Minimap ROI / Joystick radius via sliders or open Anchor Drag Calibration
 * - Toggle Debug Overlay & Auto-Menu Skip
 * - Switch Tactical Mode & manually flip Attack Direction
 * - Save instantly to SharedPreferences without leaving the game.
 */
@SuppressLint("ViewConstructor")
class InHudSettingsOverlay(
    context: Context,
    initialConfig: BotConfig,
    private val onConfigChanged: (BotConfig) -> Unit,
    private val onOpenAnchorCalibration: () -> Unit,
    private val onClose: () -> Unit,
    private val onDragBy: (dx: Int, dy: Int) -> Unit
) : LinearLayout(context) {

    private var cfg: BotConfig = initialConfig
    private var lastRawX = 0f
    private var lastRawY = 0f

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(24, 18, 24, 18)

        // Header (Draggable)
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        val title = TextView(context).apply {
            text = "⠿ Cài đặt nhanh DLS26 Bot"
            setTextColor(Color.WHITE)
            textSize = 15f
            paint.isFakeBoldText = true
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 18f
            paint.isFakeBoldText = true
            setPadding(16, 4, 8, 4)
            setOnClickListener { onClose() }
        }
        header.addView(title)
        header.addView(closeBtn)
        header.setOnTouchListener { _, event -> handleHeaderDrag(event) }
        addView(header)

        // Scrollable Body
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(760, 620)
        }
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        // 1. Tactical Mode Selector Row
        val modeLabel = makeLabel("Chiến thuật: ${cfg.tacticalMode.displayName}")
        body.addView(modeLabel)
        val modeRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        TacticalMode.entries.forEach { mode ->
            val shortName = when (mode) {
                TacticalMode.AUTO_FARM_CAREER -> "Auto Farm"
                TacticalMode.AGGRESSIVE_ATTACK -> "Tấn công"
                TacticalMode.COUNTER_ATTACK -> "Phòng ngự"
            }
            val b = Button(context).apply {
                text = shortName
                textSize = 11f
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    update(cfg.copy(tacticalMode = mode))
                    modeLabel.text = "Chiến thuật: ${cfg.tacticalMode.displayName}"
                }
            }
            modeRow.addView(b)
        }
        body.addView(modeRow)

        // 2. Attack Direction Flip Button
        val dirLabel = makeLabel("Hướng tấn công: ${cfg.attackDirection.displayName}")
        body.addView(dirLabel)
        val btnFlipDir = Button(context).apply {
            text = "Đảo hướng tấn công (Auto / Trái->Phải / Phải->Trái)"
            textSize = 11f
            setOnClickListener {
                val nextDir = when (cfg.attackDirection) {
                    AttackDirection.AUTO -> AttackDirection.LEFT_TO_RIGHT
                    AttackDirection.LEFT_TO_RIGHT -> AttackDirection.RIGHT_TO_LEFT
                    AttackDirection.RIGHT_TO_LEFT -> AttackDirection.AUTO
                }
                update(cfg.copy(attackDirection = nextDir))
                dirLabel.text = "Hướng tấn công: ${cfg.attackDirection.displayName}"
            }
        }
        body.addView(btnFlipDir)

        // 3. Switches (Debug Overlay & Auto Farm Menu)
        val swDebug = Switch(context).apply {
            text = "Hiện Debug Overlay (Khung & Chấm đỏ)"
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = cfg.debugOverlayEnabled
            setOnCheckedChangeListener { _, checked ->
                update(cfg.copy(debugOverlayEnabled = checked))
            }
        }
        body.addView(swDebug)

        val swMenu = Switch(context).apply {
            text = "Tự động bấm Continue / Next"
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = cfg.autoFarmSkipMenus
            setOnCheckedChangeListener { _, checked ->
                update(cfg.copy(autoFarmSkipMenus = checked))
            }
        }
        body.addView(swMenu)

        // 4. Quick Sliders (Minimap Top, Joystick Radius, Button A/B/C offset)
        body.addView(
            makeSliderRow("Bán kính Joystick", (cfg.joystickRadiusNorm * 1000).toInt(), 45, 140) { v ->
                update(cfg.copy(joystickRadiusNorm = v / 1000f))
            }
        )
        body.addView(
            makeSliderRow("Minimap Mép Trên (Y)", (cfg.minimapTop * 100).toInt(), 50, 85) { v ->
                val newTop = (v / 100f).coerceAtMost(cfg.minimapBottom - 0.08f)
                update(cfg.copy(minimapTop = newTop))
            }
        )
        body.addView(
            makeSliderRow("Minimap Mép Trái (X)", (cfg.minimapLeft * 100).toInt(), 20, 48) { v ->
                val newLeft = (v / 100f).coerceAtMost(cfg.minimapRight - 0.12f)
                update(cfg.copy(minimapLeft = newLeft))
            }
        )

        // 5. Launch Anchor Drag Calibration directly over game
        val btnAnchorDrag = Button(context).apply {
            text = "🎯 Kéo thả chấm neo ROI & Nút A / B / C trên game"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                onClose()
                onOpenAnchorCalibration()
            }
        }
        body.addView(btnAnchorDrag)

        scroll.addView(body)
        addView(scroll)
    }

    private fun update(next: BotConfig) {
        cfg = next
        onConfigChanged(next)
    }

    private fun makeLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            setPadding(0, 10, 0, 4)
        }
    }

    private fun makeSliderRow(
        label: String,
        initial: Int,
        minVal: Int,
        maxVal: Int,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        val container = LinearLayout(context).apply { orientation = VERTICAL }
        val tv = makeLabel("$label: $initial")
        val seek = SeekBar(context).apply {
            max = maxVal - minVal
            progress = (initial - minVal).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val actual = minVal + progress
                    tv.text = "$label: $actual"
                    if (fromUser) onChanged(actual)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(tv)
        container.addView(seek)
        return container
    }

    private fun handleHeaderDrag(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastRawX).toInt()
                val dy = (event.rawY - lastRawY).toInt()
                if (abs(dx) > 2 || abs(dy) > 2) {
                    onDragBy(dx, dy)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
        }
        return false
    }
}
