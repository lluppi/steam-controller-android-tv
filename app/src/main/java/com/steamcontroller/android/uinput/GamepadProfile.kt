package com.steamcontroller.android.uinput

/**
 * What the app emulates.
 *
 * Two entries on purpose. The uhid backend hands the kernel **one** HID report descriptor (an
 * Xbox 360 pad) and only varies the advertised VID/PID and name, so the Xbox One / DualShock 4 /
 * DualSense options could never actually behave like those devices: they would advertise Sony or
 * Microsoft IDs while reporting an Xbox layout, with no touchpad and none of the vendor-specific
 * reporting those pads are matched on. Games and RetroArch both match on identity, so offering
 * them was misleading rather than useful — and for the uinput backend they only ever changed which
 * keylayout file matched.
 *
 * [MOUSE] is not a variant: it is a different device set (mouse + keyboard, no gamepad) and has its
 * own toggle in the UI.
 *
 * `fromId` falls back to [XBOX_360], so a stored preference from before this change still lands on
 * a working profile.
 */
enum class GamepadProfile(
    val id: Int,
    val displayName: String,
    val vid: Int,
    val pid: Int,
    val isMouseMode: Boolean = false
) {
    XBOX_360(0, "Xbox 360 Controller", 0x045E, 0x028E),
    MOUSE(4, "Desktop (mouse + keyboard)", 0x046D, 0xC077, isMouseMode = true);

    companion object {
        fun fromId(id: Int): GamepadProfile = values().firstOrNull { it.id == id } ?: XBOX_360
    }
}
