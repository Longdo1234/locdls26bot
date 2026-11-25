package com.dls26.tacticalbot.config

import android.graphics.RectF
import com.dls26.tacticalbot.model.AttackDirection
import com.dls26.tacticalbot.model.TacticalMode

/**
 * All coordinates are normalized to [0f..1f] relative to the landscape screen dimensions
 * so the bot works seamlessly across 16:9, 19.5:9, and 20:9 aspect ratios.
 */
data class BotConfig(
    val tacticalMode: TacticalMode = TacticalMode.AUTO_FARM_CAREER,
    val attackDirection: AttackDirection = AttackDirection.AUTO,
    val debugOverlayEnabled: Boolean = true,
    val autoFarmSkipMenus: Boolean = true,
    val targetFps: Int = 18,

    // ROI 1: Minimap radar at bottom-center of DLS 26 HUD
    val minimapLeft: Float = 0.36f,
    val minimapTop: Float = 0.72f,
    val minimapRight: Float = 0.64f,
    val minimapBottom: Float = 0.96f,

    // Virtual Joystick (left thumb zone)
    val joystickCenterX: Float = 0.14f,
    val joystickCenterY: Float = 0.74f,
    val joystickRadiusNorm: Float = 0.085f,

    // Action Buttons (right thumb cluster: A = Shoot/Slide Tackle, B = Low Pass/Press, C = Lofted Pass-Cross/Switch)
    val buttonAX: Float = 0.80f,
    val buttonAY: Float = 0.80f,
    val buttonBX: Float = 0.87f,
    val buttonBY: Float = 0.67f,
    val buttonCX: Float = 0.93f,
    val buttonCY: Float = 0.79f,

    // Post-match / Menu Continue / Next / Play CTA button (bottom-right)
    val continueBtnX: Float = 0.88f,
    val continueBtnY: Float = 0.89f
) {
    fun minimapRectF(): RectF = RectF(minimapLeft, minimapTop, minimapRight, minimapBottom)
}
