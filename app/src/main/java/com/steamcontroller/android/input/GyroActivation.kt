package com.steamcontroller.android.input

import com.steamcontroller.android.parser.Buttons

/** Physical gesture that enables gyro aiming. */
enum class GyroActivation(val id: Int, val displayName: String, val mask: Int) {
    RIGHT_PAD_TOUCH(0, "Right pad touch", Buttons.TP_RT),
    HOLD_R4(1, "Hold R4", Buttons.R4),
    HOLD_L4(2, "Hold L4", Buttons.L4),
    ALWAYS(3, "Always on", 0);

    companion object {
        val ALL: Array<GyroActivation> = values()

        fun fromId(id: Int): GyroActivation = ALL.firstOrNull { it.id == id } ?: RIGHT_PAD_TOUCH
    }
}
