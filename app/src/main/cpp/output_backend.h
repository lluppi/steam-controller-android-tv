// The seam between "the controller produced a frame" and "Android sees real input".
//
// A backend owns some device nodes, translates a frame into whatever that node
// wants, and reports rumble commands coming back from games. Everything above this
// interface (BLE/USB transport, report parsing, calibration, mapping, profiles, UI)
// is backend-agnostic and lives in Kotlin.
#pragma once

#include "hid_common.h"

// The backend that was selected. These values cross the AIDL boundary — keep in
// sync with UInputService.kt / ControllerService.InjectionMode.
enum BackendId {
    BACKEND_NONE   = 0,  // nothing usable: caller should fall back to input injection
    BACKEND_UINPUT = 1,
    BACKEND_UHID   = 2,
};

// What the user asked for (AIDL `selectBackend` argument). AUTO takes the most
// capable backend the device actually allows, in candidate order.
enum BackendPref {
    PREF_AUTO   = 0,
    PREF_UINPUT = 1,
    PREF_UHID   = 2,
};

class OutputBackend {
public:
    virtual ~OutputBackend() = default;

    virtual const char* name() const = 0;

    // Whether this backend's device node can be opened right now. Never creates
    // anything, so it is cheap and side-effect free.
    virtual bool probe() = 0;

    // Result of the last probe(), for display in the UI: "ok", or the reason it
    // failed (e.g. "Permission denied"). Never null.
    virtual const char* probeDetail() const = 0;

    // Create the virtual devices for `profileId`. Tears down anything already
    // created first, so this doubles as "switch profile".
    virtual bool createDevices(int profileId) = 0;

    // `buttons` is an XboxBit bitmask; the axes use the ranges from hid_common.h.
    virtual void sendFrame(int buttons,
                           int lx, int ly, int rx, int ry,
                           int lt, int rt,
                           int dpadX, int dpadY) = 0;

    // Mouse/keyboard sidecar frame. `relX`/`relY` are cursor deltas, `scrollY` is
    // wheel ticks, `keys` is an MK_* bitmask (bits 0..15 keys, 16..18 mouse buttons).
    virtual void sendMouseFrame(int relX, int relY, int scrollY, int keys) = 0;

    // Rumble (force feedback) requested by a game since the last call. Returns false
    // when nothing is pending; otherwise fills *strong/*weak with 0..65535.
    virtual bool pollForceFeedback(int32_t* strong, int32_t* weak) = 0;

    virtual void destroyDevices() = 0;
};

// Singletons, one per backend.
OutputBackend* uinput_backend();
OutputBackend* uhid_backend();
