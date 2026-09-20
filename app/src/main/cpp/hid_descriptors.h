// HID report descriptors for the uhid backend.
//
// These are the uhid equivalent of uinput's UI_SET_* key/axis declarations: the
// kernel's hid-generic driver binds to them and produces evdev devices, which is how
// Android ends up with a genuine InputDevice. The expected resulting evdev codes are
// noted per report; they mirror the uinput backend's declarations so the two backends
// are interchangeable for both Android's keylayouts and RetroArch's autoconfig.
//
// Report layouts (offsets used by uhid_backend.cpp):
//
//   gamepad  13 bytes  0-1 buttons (15 bits) | 2 hat(4b)+pad | 3-10 X,Y,Rx,Ry int16 | 11-12 Z,Rz (triggers, 0-255)
//   mouse     6 bytes  0 buttons (3 bits) | 1-4 X,Y int16 relative | 5 wheel int8
//   keyboard  9 bytes  0 report id (1) | 1 modifiers | 2 reserved | 3-8 key array (6 usages)
//   consumer  2 bytes  0 report id (2) | 1 bits, in ConsumerBit order
#pragma once

#include <stddef.h>
#include <stdint.h>

inline constexpr uint8_t UHID_REPORT_ID_KEYBOARD = 1;
inline constexpr uint8_t UHID_REPORT_ID_CONSUMER = 2;
inline constexpr int UHID_KBD_ARRAY_SLOTS = 6;

inline constexpr size_t UHID_GAMEPAD_REPORT_SIZE = 13;
inline constexpr size_t UHID_MOUSE_REPORT_SIZE = 6;
inline constexpr size_t UHID_KBD_REPORT_SIZE = 9;       // id + modifiers + reserved + 6 slots
inline constexpr size_t UHID_CONSUMER_REPORT_SIZE = 2;

// Gamepad: 15 buttons, a hat switch, 4 int16 stick axes and 2 8-bit trigger axes.
// Button indexes line up with BTN_GAMEPAD + N - 1 (see XBOX_BUTTONS in hid_common.h);
// indexes 3, 6, 9 and 10 are declared but never set, which is the same trick the real
// Xbox One S Bluetooth descriptor uses to skip BTN_C/BTN_Z/BTN_TL2/BTN_TR2.
//
// Hat is a null-state 4-bit field (1..8 clockwise from up), because that is what
// hid-input turns into ABS_HAT0X/ABS_HAT0Y — the same codes uinput declares.
inline constexpr uint8_t UHID_GAMEPAD_RD[] = {
    0x05, 0x01,              // Usage Page (Generic Desktop)
    0x09, 0x05,              // Usage (Game Pad)
    0xA1, 0x01,              // Collection (Application)
    0xA1, 0x00,              //   Collection (Physical)
    0x05, 0x09,              //     Usage Page (Button)
    0x19, 0x01,              //     Usage Minimum (Button 1)
    0x29, 0x0F,              //     Usage Maximum (Button 15)
    0x15, 0x00,              //     Logical Minimum (0)
    0x25, 0x01,              //     Logical Maximum (1)
    0x75, 0x01,              //     Report Size (1)
    0x95, 0x0F,              //     Report Count (15)
    0x81, 0x02,              //     Input (Data,Var,Abs)          → BTN_A..BTN_THUMBR
    0x75, 0x01,              //     Report Size (1)
    0x95, 0x01,              //     Report Count (1)
    0x81, 0x03,              //     Input (Const)                 → pad to 16 bits
    0x05, 0x01,              //     Usage Page (Generic Desktop)
    0x09, 0x39,              //     Usage (Hat switch)
    0x15, 0x01,              //     Logical Minimum (1)
    0x25, 0x08,              //     Logical Maximum (8)
    0x35, 0x00,              //     Physical Minimum (0)
    0x46, 0x3B, 0x01,        //     Physical Maximum (315)
    0x65, 0x14,              //     Unit (Degrees)
    0x75, 0x04,              //     Report Size (4)
    0x95, 0x01,              //     Report Count (1)
    0x81, 0x42,              //     Input (Data,Var,Abs,Null)     → ABS_HAT0X / ABS_HAT0Y
    0x75, 0x04,              //     Report Size (4)
    0x95, 0x01,              //     Report Count (1)
    0x81, 0x03,              //     Input (Const)                 → pad to 8 bits
    0x65, 0x00,              //     Unit (None)
    0x09, 0x30,              //     Usage (X)
    0x09, 0x31,              //     Usage (Y)
    0x09, 0x33,              //     Usage (Rx)
    0x09, 0x34,              //     Usage (Ry)
    0x16, 0x00, 0x80,        //     Logical Minimum (-32768)
    0x26, 0xFF, 0x7F,        //     Logical Maximum (32767)
    0x75, 0x10,              //     Report Size (16)
    0x95, 0x04,              //     Report Count (4)
    0x81, 0x02,              //     Input (Data,Var,Abs)          → ABS_X/Y/RX/RY
    0x09, 0x32,              //     Usage (Z)
    0x09, 0x35,              //     Usage (Rz)
    0x15, 0x00,              //     Logical Minimum (0)
    0x26, 0xFF, 0x00,        //     Logical Maximum (255)
    0x75, 0x08,              //     Report Size (8)
    0x95, 0x02,              //     Report Count (2)
    0x81, 0x02,              //     Input (Data,Var,Abs)          → ABS_Z / ABS_RZ
    0xC0,                    //   End Collection
    0xC0,                    // End Collection
};

// Mouse: 3 buttons, 16-bit relative X/Y (so trackpad deltas are never clamped) and an
// 8-bit wheel. Y is not inverted here — the Kotlin side already produces mouse-style
// deltas (see UInputGamepad.computeRightPadDelta).
inline constexpr uint8_t UHID_MOUSE_RD[] = {
    0x05, 0x01,              // Usage Page (Generic Desktop)
    0x09, 0x02,              // Usage (Mouse)
    0xA1, 0x01,              // Collection (Application)
    0x09, 0x01,              //   Usage (Pointer)
    0xA1, 0x00,              //   Collection (Physical)
    0x05, 0x09,              //     Usage Page (Button)
    0x19, 0x01,              //     Usage Minimum (Button 1)
    0x29, 0x03,              //     Usage Maximum (Button 3)
    0x15, 0x00,              //     Logical Minimum (0)
    0x25, 0x01,              //     Logical Maximum (1)
    0x95, 0x03,              //     Report Count (3)
    0x75, 0x01,              //     Report Size (1)
    0x81, 0x02,              //     Input (Data,Var,Abs)          → BTN_LEFT/RIGHT/MIDDLE
    0x95, 0x01,              //     Report Count (1)
    0x75, 0x05,              //     Report Size (5)
    0x81, 0x03,              //     Input (Const)                 → pad to 8 bits
    0x05, 0x01,              //     Usage Page (Generic Desktop)
    0x09, 0x30,              //     Usage (X)
    0x09, 0x31,              //     Usage (Y)
    0x16, 0x01, 0x80,        //     Logical Minimum (-32767)
    0x26, 0xFF, 0x7F,        //     Logical Maximum (32767)
    0x75, 0x10,              //     Report Size (16)
    0x95, 0x02,              //     Report Count (2)
    0x81, 0x06,              //     Input (Data,Var,Rel)          → REL_X / REL_Y
    0x09, 0x38,              //     Usage (Wheel)
    0x15, 0x81,              //     Logical Minimum (-127)
    0x25, 0x7F,              //     Logical Maximum (127)
    0x75, 0x08,              //     Report Size (8)
    0x95, 0x01,              //     Report Count (1)
    0x81, 0x06,              //     Input (Data,Var,Rel)          → REL_WHEEL
    0xC0,                    //   End Collection
    0xC0,                    // End Collection
};

// Both keyboards are two application collections in one device: hid-input pools them
// into a single input device, and because every usage is EV_KEY (no EV_REL) Android
// classifies it as a keyboard rather than a mouse — the reason upstream keeps mouse and
// keyboard as separate virtual devices in the first place.
//
// The two keyboard descriptors differ only in their key-array field, and both tail off
// into the same consumer collection. They are written out in full rather than composed
// from a shared fragment: descriptor bytes are data, and a macro that reassembles them
// is harder to check against the HID spec than two literal arrays.
//
// Consumer collection bits are in ConsumerBit order (see hid_common.h).

// Desktop keyboard: one array spanning every keyboard usage (0x00-0xA1). Declaring the
// whole alphabet is what makes Android add INPUT_DEVICE_CLASS_ALPHAKEY, which the
// Leanback soft keyboard needs to route DPAD navigation to itself.
inline constexpr uint8_t UHID_KBD_DESKTOP_RD[] = {
    0x05, 0x01,              // Usage Page (Generic Desktop)
    0x09, 0x06,              // Usage (Keyboard)
    0xA1, 0x01,              // Collection (Application)
    0x85, UHID_REPORT_ID_KEYBOARD,
    0x05, 0x07,              //   Usage Page (Keyboard)
    0x19, 0xE0,              //   Usage Minimum (Left Control)
    0x29, 0xE7,              //   Usage Maximum (Right GUI)
    0x15, 0x00,              //   Logical Minimum (0)
    0x25, 0x01,              //   Logical Maximum (1)
    0x75, 0x01,              //   Report Size (1)
    0x95, 0x08,              //   Report Count (8)
    0x81, 0x02,              //   Input (Data,Var,Abs)  → L/R shift, ctrl, alt, GUI
    0x95, 0x01,              //   Report Count (1)
    0x75, 0x08,              //   Report Size (8)
    0x81, 0x03,              //   Input (Const)         → reserved byte
    0x05, 0x07,              //   Usage Page (Keyboard)
    0x19, 0x00,              //   Usage Minimum (0)
    0x29, 0xA1,              //   Usage Maximum (0xA1)
    0x15, 0x00,              //   Logical Minimum (0)
    0x25, 0xA1,              //   Logical Maximum (0xA1)
    0x75, 0x08,              //   Report Size (8)
    0x95, UHID_KBD_ARRAY_SLOTS,  //   Report Count (6)
    0x81, 0x00,              //   Input (Data,Array,Abs) → every keyboard key
    0xC0,                    // End Collection (keyboard)
    0x05, 0x0C,              // Usage Page (Consumer)
    0x09, 0x01,              // Usage (Consumer Control)
    0xA1, 0x01,              // Collection (Application)
    0x85, UHID_REPORT_ID_CONSUMER,
    0x15, 0x00,              //   Logical Minimum (0)
    0x25, 0x01,              //   Logical Maximum (1)
    0x75, 0x01,              //   Report Size (1)
    0x95, 0x01,              //   Report Count (1)
    0x09, 0xCD, 0x81, 0x02,  //   Play/Pause        bit 0
    0x09, 0x40, 0x81, 0x02,  //   Menu              bit 1
    0x09, 0x41, 0x81, 0x02,  //   Menu Pick         bit 2
    0x09, 0xE9, 0x81, 0x02,  //   Volume Increment  bit 3
    0x09, 0xEA, 0x81, 0x02,  //   Volume Decrement  bit 4
    0x09, 0x24, 0x02, 0x81, 0x02,  // AC Back     bit 5
    0x09, 0x23, 0x02, 0x81, 0x02,  // AC Home     bit 6
    0x95, 0x01, 0x75, 0x01, 0x81, 0x03,  // pad to 8 bits
    0xC0,                    // End Collection (consumer)
};

// Gamepad-mode sidecar keyboard: only the keys back-paddle mappings can emit, spread
// over two dense array fields (Enter..Space and Home..Up). Deliberately does NOT
// declare the alphabet: a device that claims A-Z makes Android believe a hardware
// QWERTY is attached and suppress the on-screen keyboard system-wide.
//
// Each array field's Logical Minimum equals its Usage Minimum on purpose — for array
// fields the kernel looks the reported value up as `usage_index = value -
// logical_minimum`, so the usage list and the logical range must share a base.
inline constexpr uint8_t UHID_KBD_MINIMAL_RD[] = {
    0x05, 0x01,              // Usage Page (Generic Desktop)
    0x09, 0x06,              // Usage (Keyboard)
    0xA1, 0x01,              // Collection (Application)
    0x85, UHID_REPORT_ID_KEYBOARD,
    0x05, 0x07,              //   Usage Page (Keyboard)
    0x19, 0xE0,              //   Usage Minimum (Left Control)
    0x29, 0xE7,              //   Usage Maximum (Right GUI)
    0x15, 0x00,              //   Logical Minimum (0)
    0x25, 0x01,              //   Logical Maximum (1)
    0x75, 0x01,              //   Report Size (1)
    0x95, 0x08,              //   Report Count (8)
    0x81, 0x02,              //   Input (Data,Var,Abs)  → L/R shift, ctrl, alt, GUI
    0x95, 0x01,              //   Report Count (1)
    0x75, 0x08,              //   Report Size (8)
    0x81, 0x03,              //   Input (Const)         → reserved byte
    0x05, 0x07,              //   Usage Page (Keyboard)
    0x19, 0x28,              //   Usage Minimum (Enter)
    0x29, 0x2C,              //   Usage Maximum (Space)
    0x15, 0x28,              //   Logical Minimum (Enter) — must equal Usage Minimum
    0x25, 0x2C,              //   Logical Maximum (Space)
    0x75, 0x08,              //   Report Size (8)
    0x95, 0x02,              //   Report Count (2)
    0x81, 0x00,              //   Input (Data,Array,Abs) → Enter, Esc, Backspace, Tab, Space
    0x05, 0x07,              //   Usage Page (Keyboard)
    0x19, 0x4A,              //   Usage Minimum (Home)
    0x29, 0x52,              //   Usage Maximum (Up Arrow)
    0x15, 0x4A,              //   Logical Minimum (Home) — must equal Usage Minimum
    0x25, 0x52,              //   Logical Maximum (Up Arrow)
    0x75, 0x08,              //   Report Size (8)
    0x95, 0x04,              //   Report Count (4)
    0x81, 0x00,              //   Input (Data,Array,Abs) → Home, arrows (and range neighbours)
    0xC0,                    // End Collection (keyboard)
    0x05, 0x0C,              // Usage Page (Consumer)
    0x09, 0x01,              // Usage (Consumer Control)
    0xA1, 0x01,              // Collection (Application)
    0x85, UHID_REPORT_ID_CONSUMER,
    0x15, 0x00,              //   Logical Minimum (0)
    0x25, 0x01,              //   Logical Maximum (1)
    0x75, 0x01,              //   Report Size (1)
    0x95, 0x01,              //   Report Count (1)
    0x09, 0xCD, 0x81, 0x02,  //   Play/Pause        bit 0
    0x09, 0x40, 0x81, 0x02,  //   Menu              bit 1
    0x09, 0x41, 0x81, 0x02,  //   Menu Pick         bit 2
    0x09, 0xE9, 0x81, 0x02,  //   Volume Increment  bit 3
    0x09, 0xEA, 0x81, 0x02,  //   Volume Decrement  bit 4
    0x09, 0x24, 0x02, 0x81, 0x02,  // AC Back     bit 5
    0x09, 0x23, 0x02, 0x81, 0x02,  // AC Home     bit 6
    0x95, 0x01, 0x75, 0x01, 0x81, 0x03,  // pad to 8 bits
    0xC0,                    // End Collection (consumer)
};
