package com.dls26.tacticalbot.model

enum class ActionButton {
    NONE,
    A_SHOOT_OR_TACKLE,
    B_LOW_PASS_OR_PRESS,
    C_LOFTED_PASS_OR_SWITCH,
    MENU_CONTINUE
}

data class BotAction(
    val state: TacticalState,
    val joystickActive: Boolean,
    val joystickAngleRad: Float,
    val joystickMagnitude: Float,
    val button: ActionButton = ActionButton.NONE,
    val buttonHoldMs: Long = 0L,
    val debugNote: String = ""
)
