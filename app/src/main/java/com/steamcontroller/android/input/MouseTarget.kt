package com.steamcontroller.android.input

import com.steamcontroller.android.parser.Buttons

/**
 * Targets available in Desktop / Mouse mode. The `bit` field MUST match the order
 * of the `MOUSE_KEYS` array in `uinput_jni.cpp`. Bits 15/16/17 are mouse buttons.
 *
 * NONE (bit = -1) means "no event emitted for this source".
 */
enum class MouseTarget(
    val bit: Int,
    val displayName: String,
) {
    NONE(-1, "(none)"),

    // Navigation keys
    KEY_UP(0, "↑ Up"),
    KEY_DOWN(1, "↓ Down"),
    KEY_LEFT(2, "← Left"),
    KEY_RIGHT(3, "→ Right"),

    KEY_ENTER(4, "Enter / OK"),
    KEY_BACK(5, "Back (Android)"),
    KEY_TAB(6, "Tab"),
    KEY_SPACE(7, "Space"),

    KEY_HOME(8, "Home"),
    KEY_ESC(9, "Escape"),

    KEY_VOLUME_UP(10, "Volume +"),
    KEY_VOLUME_DOWN(11, "Volume -"),
    KEY_PLAY_PAUSE(12, "Play/Pause"),
    KEY_MENU(13, "Menu (context)"),
    KEY_BACKSPACE(14, "Backspace"),

    // bit 15 → Linux KEY_SELECT → AKEYCODE_DPAD_CENTER. Required to "click" a
    // focused key on the Android TV Leanback soft keyboard (ENTER alone is not
    // enough - the IME source-filters selection to DPAD_CENTER events).
    KEY_DPAD_CENTER(15, "DPAD Center / select"),

    // Mouse buttons (bits 16-18 in the native frame)
    BTN_LEFT(16, "🖱 Left click"),
    BTN_RIGHT(17, "🖱 Right click"),
    BTN_MIDDLE(18, "🖱 Middle click"),
}

/**
 * Source bits that route to *fixed* targets in mouse mode (not customisable in V1.1):
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
