package com.dls26.tacticalbot.model

enum class TacticalMode(val displayName: String) {
    AUTO_FARM_CAREER("Auto Farm / Career"),
    AGGRESSIVE_ATTACK("Tấn công chủ động"),
    COUNTER_ATTACK("Phòng ngự phản công")
}

enum class AttackDirection(val displayName: String) {
    AUTO("Tự động nhận diện"),
    LEFT_TO_RIGHT("Trái -> Phải"),
    RIGHT_TO_LEFT("Phải -> Trái")
}

enum class GameScene {
    IN_MATCH,
    SET_PIECE,
    MENU_OR_POST_MATCH,
    UNKNOWN
}

enum class PossessionState {
    ALLY,
    OPPONENT,
    LOOSE_BALL
}

enum class TacticalState {
    IDLE,
    MENU_AUTO_ADVANCE,
    SET_PIECE_PASS,
    ATTACK_DRIBBLE,
    ATTACK_LOW_PASS,
    ATTACK_LOFTED_CROSS,
    ATTACK_SHOOT,
    DEFEND_PRESS,
    DEFEND_JOCKEY,
    DEFEND_SWITCH_PLAYER,
    DEFEND_TACKLE
}
