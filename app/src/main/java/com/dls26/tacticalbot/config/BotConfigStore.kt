package com.dls26.tacticalbot.config

import android.content.Context
import com.dls26.tacticalbot.model.AttackDirection
import com.dls26.tacticalbot.model.TacticalMode

class BotConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): BotConfig {
        val modeName = prefs.getString(KEY_MODE, TacticalMode.AUTO_FARM_CAREER.name)
        val dirName = prefs.getString(KEY_DIRECTION, AttackDirection.AUTO.name)
        return BotConfig(
            tacticalMode = runCatching { TacticalMode.valueOf(modeName ?: "") }
                .getOrDefault(TacticalMode.AUTO_FARM_CAREER),
            attackDirection = runCatching { AttackDirection.valueOf(dirName ?: "") }
                .getOrDefault(AttackDirection.AUTO),
            debugOverlayEnabled = prefs.getBoolean(KEY_DEBUG, true),
            autoFarmSkipMenus = prefs.getBoolean(KEY_AUTO_MENU, true),
            targetFps = prefs.getInt(KEY_FPS, 18).coerceIn(10, 24),
            minimapLeft = prefs.getFloat(KEY_MM_L, 0.36f),
            minimapTop = prefs.getFloat(KEY_MM_T, 0.72f),
            minimapRight = prefs.getFloat(KEY_MM_R, 0.64f),
            minimapBottom = prefs.getFloat(KEY_MM_B, 0.96f),
            joystickCenterX = prefs.getFloat(KEY_JOY_X, 0.14f),
            joystickCenterY = prefs.getFloat(KEY_JOY_Y, 0.74f),
            joystickRadiusNorm = prefs.getFloat(KEY_JOY_R, 0.085f),
            buttonAX = prefs.getFloat(KEY_BTN_AX, 0.80f),
            buttonAY = prefs.getFloat(KEY_BTN_AY, 0.80f),
            buttonBX = prefs.getFloat(KEY_BTN_BX, 0.87f),
            buttonBY = prefs.getFloat(KEY_BTN_BY, 0.67f),
            buttonCX = prefs.getFloat(KEY_BTN_CX, 0.93f),
            buttonCY = prefs.getFloat(KEY_BTN_CY, 0.79f),
            continueBtnX = prefs.getFloat(KEY_CONT_X, 0.88f),
            continueBtnY = prefs.getFloat(KEY_CONT_Y, 0.89f)
        )
    }

    fun save(config: BotConfig) {
        prefs.edit()
            .putString(KEY_MODE, config.tacticalMode.name)
            .putString(KEY_DIRECTION, config.attackDirection.name)
            .putBoolean(KEY_DEBUG, config.debugOverlayEnabled)
            .putBoolean(KEY_AUTO_MENU, config.autoFarmSkipMenus)
            .putInt(KEY_FPS, config.targetFps)
            .putFloat(KEY_MM_L, config.minimapLeft)
            .putFloat(KEY_MM_T, config.minimapTop)
            .putFloat(KEY_MM_R, config.minimapRight)
            .putFloat(KEY_MM_B, config.minimapBottom)
            .putFloat(KEY_JOY_X, config.joystickCenterX)
            .putFloat(KEY_JOY_Y, config.joystickCenterY)
            .putFloat(KEY_JOY_R, config.joystickRadiusNorm)
            .putFloat(KEY_BTN_AX, config.buttonAX)
            .putFloat(KEY_BTN_AY, config.buttonAY)
            .putFloat(KEY_BTN_BX, config.buttonBX)
            .putFloat(KEY_BTN_BY, config.buttonBY)
            .putFloat(KEY_BTN_CX, config.buttonCX)
            .putFloat(KEY_BTN_CY, config.buttonCY)
            .putFloat(KEY_CONT_X, config.continueBtnX)
            .putFloat(KEY_CONT_Y, config.continueBtnY)
            .apply()
    }

    fun resetDefaults(): BotConfig {
        val defaults = BotConfig()
        save(defaults)
        return defaults
    }

    companion object {
        private const val PREFS_NAME = "dls26_tactical_bot_prefs"
        private const val KEY_MODE = "tactical_mode"
        private const val KEY_DIRECTION = "attack_direction"
        private const val KEY_DEBUG = "debug_overlay"
        private const val KEY_AUTO_MENU = "auto_farm_menu"
        private const val KEY_FPS = "target_fps"
        private const val KEY_MM_L = "mm_left"
        private const val KEY_MM_T = "mm_top"
        private const val KEY_MM_R = "mm_right"
        private const val KEY_MM_B = "mm_bottom"
        private const val KEY_JOY_X = "joy_x"
        private const val KEY_JOY_Y = "joy_y"
        private const val KEY_JOY_R = "joy_r"
        private const val KEY_BTN_AX = "btn_ax"
        private const val KEY_BTN_AY = "btn_ay"
        private const val KEY_BTN_BX = "btn_bx"
        private const val KEY_BTN_BY = "btn_by"
        private const val KEY_BTN_CX = "btn_cx"
        private const val KEY_BTN_CY = "btn_cy"
        private const val KEY_CONT_X = "cont_x"
        private const val KEY_CONT_Y = "cont_y"
    }
}
