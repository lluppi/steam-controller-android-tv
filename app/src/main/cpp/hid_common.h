// Shared vocabulary for the virtual-output backends.
//
// Two backends exist (see output_backend.h):
//   * uinput — /dev/uinput. Upstream's implementation: one evdev device per virtual
//              device, needs the shell UID to be allowed to open /dev/uinput.
//   * uhid   — /dev/uhid. Presents a real HID device; the kernel binds hid-generic
//              and produces a genuine InputDevice. Used where /dev/uinput is closed
//              to the shell UID (e.g. NVIDIA Shield TV, where /dev/uinput belongs to
//              system:bluetooth and /dev/uhid is open to the shell).
//
// Every value the Kotlin side sends is expressed in the ranges and bit orders below,
// so each backend translates from one shared contract instead of defining its own.
//
// Define LOG_TAG before including this header to change the logcat tag.
#pragma once

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#ifndef LOG_TAG
#define LOG_TAG "virtual_input"
#endif
#ifndef LOGI
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#endif
#ifndef LOGE
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

// ── Emulated controller identities ──────────────────────────────────────────
// `id` MUST match GamepadProfile.kt. VID/PID are what Android matches its
// Vendor_xxxx_Product_yyyy.kl keylayouts on, so they are part of the contract too.
struct gamepad_profile {
    int id;
    uint16_t vid;
    uint16_t pid;
    const char* name;
    bool mouse_mode;  // true = Desktop profile: mouse + keyboard, no gamepad
};

// Emulated identities. One gamepad plus the Desktop device set, deliberately: the uhid backend
// sends a single Xbox 360 HID report descriptor and only varies VID/PID/name, so other pad
// identities could never actually behave like the device they claimed to be. See GamepadProfile.kt.
inline const gamepad_profile PROFILES[] = {
    { 0, 0x045E, 0x028E, "Microsoft X-Box 360 pad", false },
    { 4, 0x046D, 0xC077, "Steam Controller Desktop", true },
};

// Sidecar device identities, shared by both backends so a profile looks the same to Android
// whichever backend created it. Desktop has no gamepad, so its mouse takes the profile's own
// identity (a real mouse) and the keyboard the next PID. Gamepad mode keeps the sidecars well
// clear of the pad's PID so no gamepad keylayout ever matches them.
inline uint16_t sidecar_mouse_pid(const gamepad_profile& p) {
    return p.mouse_mode ? p.pid : (uint16_t)(p.pid + 0x100);
}
inline uint16_t sidecar_keyboard_pid(const gamepad_profile& p) {
    return p.mouse_mode ? (uint16_t)(p.pid + 1) : (uint16_t)(p.pid + 0x200);
}

inline const gamepad_profile& find_profile(int id) {
    for (const auto& p : PROFILES) {
        if (p.id == id) return p;
    }
    return PROFILES[0];  // fallback: Xbox 360
}

// Looks up `profileId` and logs it - the first step of every backend's createDevices().
inline const gamepad_profile& find_and_log_profile(int profileId) {
    const gamepad_profile& prof = find_profile(profileId);
    LOGI("createDevices: profile=%d (VID=0x%04X PID=0x%04X name=\"%s\")",
         prof.id, prof.vid, prof.pid, prof.name);
    return prof;
}

inline constexpr const char* SIDECAR_MOUSE_NAME = "Steam Controller Mouse";
inline constexpr const char* SIDECAR_KEYBOARD_NAME = "Steam Controller Keyboard";

// Backend probe: can `path` be opened with `flags` right now? Writes "ok" or the errno
// text to `detail` for the UI and never leaves anything open.
inline bool probe_node(const char* path, int flags, char* detail, size_t detail_size) {
    const int fd = ::open(path, flags | O_CLOEXEC);
    if (fd < 0) {
        snprintf(detail, detail_size, "%s", strerror(errno));
        return false;
    }
    ::close(fd);
    snprintf(detail, detail_size, "ok");
    return true;
}

// Both backends destroy and recreate devices on every profile switch. Give the
// kernel and Android's InputReader a moment to release the old input device records:
// without it, rapid switching can hit transient failures where device creation
// reports success but Android never sees the new device, leaving the UI convinced
// everything is healthy while no events flow.
inline void wait_for_input_records_to_settle() {
    const struct timespec ts = { 0, 80 * 1000 * 1000 };  // 80ms
    nanosleep(&ts, nullptr);
}

// ── Frame ranges ────────────────────────────────────────────────────────────
// The Xbox 360 layout is the common denominator: a frame is always expressed in
// these ranges regardless of which profile name the backend advertises.
constexpr int STICK_MIN = -32768;
constexpr int STICK_MAX = 32767;
constexpr int TRIG_MIN = 0;
constexpr int TRIG_MAX = 255;
constexpr int HAT_MIN = -1;
constexpr int HAT_MAX = 1;

// ── Gamepad buttons ─────────────────────────────────────────────────────────
// Bit positions MUST match XboxButtons in XboxDescriptor.kt.
enum XboxBit {
    XB_A = 0, XB_B, XB_X, XB_Y, XB_LB, XB_RB,
    XB_SELECT, XB_START, XB_MODE, XB_THUMBL, XB_THUMBR,
    XB_COUNT,
};

// Both encodings of each button, in XboxBit order — the single source of truth for
// "which bit is which button".
//
//   evdev     evdev code for the uinput backend. (Not named `linux`: that is a
//             preprocessor macro in the Linux input header chain.)
//   hid_index HID Button usage number for the uhid backend. Linux maps gamepad
//             button N to BTN_GAMEPAD + N - 1, so index 3 lands on BTN_C, 6 on
//             BTN_Z, 9 on BTN_TL2 and 10 on BTN_TR2. Those are skipped: the HID
//             descriptor declares 15 buttons so the indexes line up with the
//             BTN_* codes Android's gamepad keylayouts actually use.
struct xbox_button {
    int evdev;
    uint8_t hid_index;
};

inline constexpr xbox_button XBOX_BUTTONS[XB_COUNT] = {
    { BTN_A,       1  },  // XB_A
    { BTN_B,       2  },  // XB_B
    { BTN_X,       4  },  // XB_X
    { BTN_Y,       5  },  // XB_Y
    { BTN_TL,      7  },  // XB_LB
    { BTN_TR,      8  },  // XB_RB
    { BTN_SELECT, 11  },  // XB_SELECT
    { BTN_START,  12  },  // XB_START
    { BTN_MODE,   13  },  // XB_MODE
    { BTN_THUMBL, 14  },  // XB_THUMBL
    { BTN_THUMBR, 15  },  // XB_THUMBR
};

// ── Sidecar keys ────────────────────────────────────────────────────────────
// Used by Desktop mode and by back-paddle key mappings in gamepad mode.
// Bit positions MUST match MouseTarget.kt `bit` values: 0..15 are keys, 16..18 are
// mouse buttons (see MK_BTN_*_BIT below).
enum MouseKeyBit {
    MK_UP = 0, MK_DOWN, MK_LEFT, MK_RIGHT,
    MK_ENTER, MK_BACK, MK_TAB, MK_SPACE,
    MK_HOME, MK_ESC, MK_VOLUME_UP, MK_VOLUME_DOWN,
    MK_PLAY_PAUSE, MK_MENU, MK_BACKSPACE, MK_DPAD_CENTER,
    MK_COUNT,
};

// Consumer-page (0x0C) bits, in HID descriptor declaration order: the index IS the
// report bit. hid_descriptors.h must declare them in this exact order.
enum ConsumerBit {
    CB_PLAY_PAUSE = 0, CB_MENU, CB_SELECT, CB_VOLUME_UP, CB_VOLUME_DOWN, CB_BACK, CB_HOME,
    CB_COUNT,
};

// One entry per key bit, carrying both encodings:
//   evdev    evdev code for the uinput keyboard device.
//   consumer false: Keyboard page (0x07), `code` is the HID usage. The uhid keyboard
//                     reports these through its key-array field.
//            true:  Consumer page (0x0C), `code` is a ConsumerBit index. The uhid
//                     keyboard reports these as individual bits in report id 2.
//
// The trailing comments are the evdev code the uhid path is expected to produce on
// the Android side; they are what the on-device parity check compares against.
inline constexpr uint8_t MK_BTN_LEFT_BIT   = 16;
inline constexpr uint8_t MK_BTN_RIGHT_BIT  = 17;
inline constexpr uint8_t MK_BTN_MIDDLE_BIT = 18;

struct sidecar_key {
    int evdev;
    bool consumer;
    uint8_t code;
};

inline constexpr sidecar_key SIDECAR_KEYS[MK_COUNT] = {
    /* MK_UP          */ { KEY_UP,         false, 0x52 },        // → KEY_UP
    /* MK_DOWN        */ { KEY_DOWN,       false, 0x51 },        // → KEY_DOWN
    /* MK_LEFT        */ { KEY_LEFT,       false, 0x50 },        // → KEY_LEFT
    /* MK_RIGHT       */ { KEY_RIGHT,      false, 0x4F },        // → KEY_RIGHT
    /* MK_ENTER       */ { KEY_ENTER,      false, 0x28 },        // → KEY_ENTER
    /* MK_BACK        */ { KEY_BACK,       true,  CB_BACK },     // AC Back    → KEY_BACK
    /* MK_TAB         */ { KEY_TAB,        false, 0x2B },        // → KEY_TAB
    /* MK_SPACE       */ { KEY_SPACE,      false, 0x2C },        // → KEY_SPACE
    /* MK_HOME        */ { KEY_HOME,       true,  CB_HOME },     // AC Home    → KEY_HOMEPAGE
    /* MK_ESC         */ { KEY_ESC,        false, 0x29 },        // → KEY_ESC
    /* MK_VOLUME_UP   */ { KEY_VOLUMEUP,   true,  CB_VOLUME_UP },   // Volume Inc → KEY_VOLUMEUP
    /* MK_VOLUME_DOWN */ { KEY_VOLUMEDOWN, true,  CB_VOLUME_DOWN }, // Volume Dec → KEY_VOLUMEDOWN
    /* MK_PLAY_PAUSE  */ { KEY_PLAYPAUSE,  true,  CB_PLAY_PAUSE },  // Play/Pause → KEY_PLAYPAUSE
    /* MK_MENU        */ { KEY_MENU,       true,  CB_MENU },        // Menu       → KEY_MENU
    /* MK_BACKSPACE   */ { KEY_BACKSPACE,  false, 0x2A },        // → KEY_BACKSPACE
    /* MK_DPAD_CENTER */ { KEY_SELECT,     true,  CB_SELECT },   // Menu Pick  → KEY_SELECT
};
