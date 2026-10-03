package com.steamcontroller.android.input

import com.steamcontroller.android.parser.Buttons

/**
 * Bit positions in the sidecar `keys` mask sent to the native backends. The order MUST match
 * `MouseKeyBit` / `SIDECAR_KEYS` in `cpp/hid_common.h`; bits 16-18 are the mouse buttons
 * (`MK_BTN_*_BIT`). [XboxTarget.keyBit] refers to these, so this is the only Kotlin copy.
 */
enum class MouseTarget(
    val bit: Int,
) {
    // Navigation keys
    KEY_UP(0),
    KEY_DOWN(1),
    KEY_LEFT(2),
    KEY_RIGHT(3),

    KEY_ENTER(4),
    KEY_BACK(5),
    KEY_TAB(6),
    KEY_SPACE(7),

    KEY_HOME(8),
    KEY_ESC(9),

    KEY_VOLUME_UP(10),
    KEY_VOLUME_DOWN(11),
    KEY_PLAY_PAUSE(12),
    KEY_MENU(13),
    KEY_BACKSPACE(14),

    // bit 15 → Linux KEY_SELECT → AKEYCODE_DPAD_CENTER. Required to "click" a
    // focused key on the Android TV Leanback soft keyboard (ENTER alone is not
    // enough - the IME source-filters selection to DPAD_CENTER events).
    KEY_DPAD_CENTER(15),

    // Mouse buttons (bits 16-18 in the native frame)
    BTN_LEFT(16),
    BTN_RIGHT(17),
    BTN_MIDDLE(18),
}

/**
 * Source bits that route to *fixed* targets in mouse mode (not customisable):
 *  - DPAD → arrow keys
 *  - Left trackpad click → right mouse click
 *  - Right trackpad motion → cursor delta (handled separately, not a button)
 */
val MOUSE_MODE_FIXED_DPAD =
    mapOf(
        Buttons.DPAD_UP to MouseTarget.KEY_UP,
        Buttons.DPAD_DOWN to MouseTarget.KEY_DOWN,
        Buttons.DPAD_LEFT to MouseTarget.KEY_LEFT,
        Buttons.DPAD_RIGHT to MouseTarget.KEY_RIGHT,
    )
const val MOUSE_LEFT_PAD_CLICK_BIT = Buttons.TP_LT_CLICK

/** Right trackpad click. The right pad drives the cursor, so its click is the primary one. */
const val MOUSE_RIGHT_PAD_CLICK_BIT = Buttons.TP_RT_CLICK
