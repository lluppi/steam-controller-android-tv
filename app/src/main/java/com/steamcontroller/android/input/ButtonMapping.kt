package com.steamcontroller.android.input

import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.uinput.XboxButtons

enum class ButtonCategory(val title: String) {
    FACE("Face buttons"),
    BUMPERS("Bumpers"),
    TRIGGERS("Triggers (full press)"),
    STICKS("Stick clicks"),
    SYSTEM("System"),
    BACK_PADDLES("Back paddles"),
    GRIPS("Grips (capacitive)")
}

/**
 * Physical buttons on the Steam Controller 2026 that can be remapped.
 * `mask` is the bit position in `SteamControllerState.buttons`; triggers use their analog
 * values because the previously assumed LT-full bit is the verified right-pad-click bit.
 *
 * DPAD and trackpads are intentionally not here because they are routed as axes and pointers.
 */
enum class SteamButton(val mask: Int, val displayName: String, val category: ButtonCategory) {
    A(Buttons.A, "A", ButtonCategory.FACE),
    B(Buttons.B, "B", ButtonCategory.FACE),
    X(Buttons.X, "X", ButtonCategory.FACE),
    Y(Buttons.Y, "Y", ButtonCategory.FACE),
    LB(Buttons.LB, "L1 / LB", ButtonCategory.BUMPERS),
    RB(Buttons.RB, "R1 / RB", ButtonCategory.BUMPERS),

    // A near-full analog pull is the only trustworthy trigger-button signal on this firmware.
    LT(0, "L2 / LT (full press)", ButtonCategory.TRIGGERS),
    RT(0, "R2 / RT (full press)", ButtonCategory.TRIGGERS),
    LS(Buttons.LS, "L3 (stick click)", ButtonCategory.STICKS),
    RS(Buttons.RS, "R3 (stick click)", ButtonCategory.STICKS),
    MENU(Buttons.MENU, "Menu (Start)", ButtonCategory.SYSTEM),
    VIEW(Buttons.VIEW, "View (Select)", ButtonCategory.SYSTEM),
    STEAM(Buttons.STEAM, "Steam (Guide)", ButtonCategory.SYSTEM),
    QUICK_ACCESS(Buttons.QUICK_ACCESS, "Quick Access Menu", ButtonCategory.SYSTEM),
    L4(Buttons.L4, "L4 back paddle", ButtonCategory.BACK_PADDLES),
    R4(Buttons.R4, "R4 back paddle", ButtonCategory.BACK_PADDLES),
    L5(Buttons.L5, "L5 back paddle", ButtonCategory.BACK_PADDLES),
    R5(Buttons.R5, "R5 back paddle", ButtonCategory.BACK_PADDLES),
    GRIP_LT(Buttons.GRIP_LT, "Left grip", ButtonCategory.GRIPS),
    GRIP_RT(Buttons.GRIP_RT, "Right grip", ButtonCategory.GRIPS);

    fun isPressed(state: SteamControllerState): Boolean = when (this) {
        LT -> state.leftTrigger >= FULL_TRIGGER_THRESHOLD
        RT -> state.rightTrigger >= FULL_TRIGGER_THRESHOLD
        else -> state.isButtonPressed(mask)
    }

    /** Short label fits inside a chip badge (≤4 chars) */
    val shortLabel: String get() = when (this) {
        A -> "A"
        B -> "B"
        X -> "X"
        Y -> "Y"
        LB -> "L1"
        RB -> "R1"
        LT -> "L2"
        RT -> "R2"
        LS -> "L3"
        RS -> "R3"
        MENU -> "≡"
        VIEW -> "···"
        STEAM -> "◆"
        L4 -> "L4"
        R4 -> "R4"
        L5 -> "L5"
        R5 -> "R5"
        GRIP_LT -> "LG"
        GRIP_RT -> "RG"
        QUICK_ACCESS -> "QA"
    }

    private companion object {
        const val FULL_TRIGGER_THRESHOLD = 30_000
    }
}

/**
 * Target buttons exposed by the virtual gamepad (Xbox layout - Android maps
 * these consistently across all profiles).
 *
 * Four flavours coexist:
 *  - `mask > 0`            : regular Xbox button (OR into the gamepad button mask).
 *  - `keyBit >= 0`         : sidecar keyboard key - emitted via the mouse+kbd sidecar
 *                            device that always runs alongside a gamepad profile.
 *                            Bit values come from MouseTarget, the same JNI path desktop mode uses.
 *  - `triggerSide != 0`    : forces an analog trigger axis to max (1=LT, 2=RT) when the
 *                            source is pressed. OR'd with the real analog reading via max().
 *  - `mask < 0` (no other) : special action handled in Kotlin (e.g. SCREENSHOT).
 *
 * NONE (mask=0, keyBit=-1, triggerSide=0) means "disabled".
 */
enum class XboxTarget(
    val mask: Int,
    val displayName: String,
    val keyBit: Int = -1,
    val triggerSide: Int = 0
) {
    NONE(0, "(none)"),
    A(XboxButtons.A, "A"),
    B(XboxButtons.B, "B"),
    X(XboxButtons.X, "X"),
    Y(XboxButtons.Y, "Y"),
    LB(XboxButtons.LB, "LB"),
    RB(XboxButtons.RB, "RB"),
    SELECT(XboxButtons.SELECT, "View / Select"),
    START(XboxButtons.START, "Menu / Start"),
    MODE(XboxButtons.MODE, "Guide"),
    THUMBL(XboxButtons.THUMBL, "L3"),
    THUMBR(XboxButtons.THUMBR, "R3"),

    // ── Trigger axes (digital → forced max) ──────────────────────────────────
    // Lets the user remap any button (face, paddle, bumper…) to "trigger fully
    // pulled". Useful e.g. to remap a back paddle to LT so you can drive + sip a
    // potion at the same time. Mask is negative so it doesn't OR into face buttons.
    LT_TRIGGER(-30, "L2 / LT (full press)", triggerSide = 1),
    RT_TRIGGER(-31, "R2 / RT (full press)", triggerSide = 2),

    // ── Sidecar keyboard and pointer actions ─────────────────────────────────
    KB_UP(-32, "↑ Up", keyBit = MouseTarget.KEY_UP.bit),
    KB_DOWN(-33, "↓ Down", keyBit = MouseTarget.KEY_DOWN.bit),
    KB_LEFT(-34, "← Left", keyBit = MouseTarget.KEY_LEFT.bit),
    KB_RIGHT(-35, "→ Right", keyBit = MouseTarget.KEY_RIGHT.bit),
    KB_VOLUME_UP(-10, "🔊 Volume +", keyBit = MouseTarget.KEY_VOLUME_UP.bit),
    KB_VOLUME_DOWN(-11, "🔉 Volume -", keyBit = MouseTarget.KEY_VOLUME_DOWN.bit),
    KB_PLAY_PAUSE(-12, "⏯ Play / Pause", keyBit = MouseTarget.KEY_PLAY_PAUSE.bit),
    KB_BACK(-13, "⮌ Back", keyBit = MouseTarget.KEY_BACK.bit),
    KB_HOME(-14, "🏠 Home", keyBit = MouseTarget.KEY_HOME.bit),
    KB_ENTER(-15, "↵ Enter", keyBit = MouseTarget.KEY_ENTER.bit),
    KB_DPAD_CENTER(-16, "● DPAD Center", keyBit = MouseTarget.KEY_DPAD_CENTER.bit),
    KB_ESCAPE(-17, "ESC", keyBit = MouseTarget.KEY_ESC.bit),
    KB_TAB(-18, "⇥ Tab", keyBit = MouseTarget.KEY_TAB.bit),
    KB_SPACE(-19, "␣ Space", keyBit = MouseTarget.KEY_SPACE.bit),
    KB_BACKSPACE(-20, "⌫ Backspace", keyBit = MouseTarget.KEY_BACKSPACE.bit),
    KB_MENU(-21, "☰ Menu (context)", keyBit = MouseTarget.KEY_MENU.bit),
    MOUSE_LEFT(-36, "🖱 Left click", keyBit = MouseTarget.BTN_LEFT.bit),
    MOUSE_RIGHT(-37, "🖱 Right click", keyBit = MouseTarget.BTN_RIGHT.bit),
    MOUSE_MIDDLE(-38, "🖱 Middle click", keyBit = MouseTarget.BTN_MIDDLE.bit),

    // Special actions (mask < 0, no keyBit). Edge-triggered on press in Kotlin.
    SCREENSHOT(-1, "📸 Take screenshot"),
    GUIDE_LAYER(-2, "Guide tap / action layer hold");

    /** Handled in Kotlin rather than emitted as a button, key or trigger. */
    val isSpecialAction: Boolean get() = mask < 0 && keyBit < 0 && triggerSide == 0

    companion object {
        // Exact order used by releases that persisted enum ordinals. New entries must never be
        // added here: current releases persist enum names instead.
        private val LEGACY_ORDINAL_TARGETS =
            arrayOf(
                NONE, A, B, X, Y, LB, RB, SELECT, START, MODE, THUMBL, THUMBR,
                LT_TRIGGER, RT_TRIGGER, KB_VOLUME_UP, KB_VOLUME_DOWN, KB_PLAY_PAUSE,
                KB_BACK, KB_HOME, KB_ENTER, KB_DPAD_CENTER, KB_ESCAPE, KB_TAB, KB_SPACE,
                KB_BACKSPACE, KB_MENU, SCREENSHOT
            )

        /** Stable persistence ID. Enum names are an on-disk contract and must not be renamed. */
        fun fromPersisted(value: Any?): XboxTarget? = when (value) {
            is Number -> LEGACY_ORDINAL_TARGETS.getOrNull(value.toInt())

            is String ->
                values().firstOrNull { it.name == value }
                    ?: value.toIntOrNull()?.let { LEGACY_ORDINAL_TARGETS.getOrNull(it) }

            else -> null
        }
    }
}

/** Default mapping - reproduces the hardcoded mapping that existed before this feature. */
val DEFAULT_MAPPING: Map<SteamButton, XboxTarget> = mapOf(
    SteamButton.A to XboxTarget.A,
    SteamButton.B to XboxTarget.B,
    SteamButton.X to XboxTarget.X,
    SteamButton.Y to XboxTarget.Y,
    SteamButton.LB to XboxTarget.LB,
    SteamButton.RB to XboxTarget.RB,
    // Full-press trigger → itself by default (forces the axis fully to max on a hard
    // pull, on top of the analog 0..32767 that already routes to LT/RT via sendFrame()).
    SteamButton.LT to XboxTarget.LT_TRIGGER,
    SteamButton.RT to XboxTarget.RT_TRIGGER,
    SteamButton.LS to XboxTarget.THUMBL,
    SteamButton.RS to XboxTarget.THUMBR,
    SteamButton.MENU to XboxTarget.START,
    SteamButton.VIEW to XboxTarget.SELECT,
    SteamButton.STEAM to XboxTarget.MODE,
    SteamButton.QUICK_ACCESS to XboxTarget.NONE, // No default - user assigns
    // Back paddles default to the stick clicks. Those are what emulators use for save/load state
    // (RetroArch's BUTTON_THUMBL/THUMBR, keycodes 106/107), so the paddles do something useful out
    // of the box instead of nothing. L5/R5 stay free for the user.
    SteamButton.L4 to XboxTarget.THUMBL,
    SteamButton.L5 to XboxTarget.NONE,
    SteamButton.R4 to XboxTarget.THUMBR,
    SteamButton.R5 to XboxTarget.NONE,
    SteamButton.GRIP_LT to XboxTarget.NONE,
    SteamButton.GRIP_RT to XboxTarget.NONE
)

val DEFAULT_ACTION_LAYER: Map<SteamButton, XboxTarget> =
    mapOf(
        SteamButton.L4 to XboxTarget.KB_VOLUME_DOWN,
        SteamButton.R4 to XboxTarget.KB_VOLUME_UP,
        SteamButton.L5 to XboxTarget.KB_PLAY_PAUSE,
        SteamButton.R5 to XboxTarget.SCREENSHOT
    )

val DEFAULT_DESKTOP_MAPPING: Map<SteamButton, XboxTarget> =
    mapOf(
        SteamButton.A to XboxTarget.KB_DPAD_CENTER,
        SteamButton.B to XboxTarget.KB_BACK,
        SteamButton.X to XboxTarget.KB_SPACE,
        SteamButton.Y to XboxTarget.KB_TAB,
        SteamButton.LB to XboxTarget.MOUSE_RIGHT,
        SteamButton.RB to XboxTarget.MOUSE_LEFT,
        SteamButton.LT to XboxTarget.NONE,
        SteamButton.RT to XboxTarget.NONE,
        SteamButton.LS to XboxTarget.KB_HOME,
        SteamButton.RS to XboxTarget.MOUSE_MIDDLE,
        SteamButton.MENU to XboxTarget.KB_MENU,
        SteamButton.VIEW to XboxTarget.KB_ESCAPE,
        SteamButton.STEAM to XboxTarget.KB_HOME,
        SteamButton.QUICK_ACCESS to XboxTarget.KB_PLAY_PAUSE,
        SteamButton.L4 to XboxTarget.KB_VOLUME_DOWN,
        SteamButton.R4 to XboxTarget.KB_VOLUME_UP,
        SteamButton.L5 to XboxTarget.KB_BACKSPACE,
        SteamButton.R5 to XboxTarget.NONE,
        SteamButton.GRIP_LT to XboxTarget.NONE,
        SteamButton.GRIP_RT to XboxTarget.NONE
    )
