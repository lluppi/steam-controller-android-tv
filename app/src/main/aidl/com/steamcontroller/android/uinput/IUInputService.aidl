package com.steamcontroller.android.uinput;

// Runs in the Shizuku user service process (shell UID).
//
// Fronts the virtual-output backends: /dev/uinput when the shell UID is allowed to open
// it, /dev/uhid otherwise. See cpp/output_backend.h.
interface IUInputService {
    // Probe the backends and adopt one. `preferred` is 0 = auto, 1 = uinput, 2 = uhid.
    // Returns the chosen backend id (0 = none usable, 1 = uinput, 2 = uhid).
    int selectBackend(int preferred);

    int getBackend();

    // Human-readable probe result for every backend, e.g.
    // "/dev/uinput: Permission denied, /dev/uhid: ok". Shown in the UI.
    String getBackendDetail();

    // Whether games can receive rumble through the selected backend.
    boolean supportsRumble();

    // Create the virtual devices for the given profile id (see GamepadProfile.kt).
    // Returns true on success.
    boolean createGamepad(int profileId);

    void sendFrame(int buttons,
                   int leftStickX, int leftStickY,
                   int rightStickX, int rightStickY,
                   int leftTrigger, int rightTrigger,
                   int dpadX, int dpadY);

    // Desktop / mouse-mode frame. `keys` is a MouseTarget bitmask.
    void sendMouseFrame(int relX, int relY, int scrollY, int keys);

    // Returns [strongMagnitude, weakMagnitude] in 0..65535 if a game triggered rumble,
    // or null if nothing happened since the last poll.
    int[] pollForceFeedback();

    // Run an arbitrary shell command as the Shizuku shell user. Returns the exit code.
    // Used for screenshot (screencap), and as a general escape hatch for future system actions.
    int runShellCommand(in String[] cmd);

    // Same, but returns captured stdout (trimmed) instead of the exit code, or null on failure.
    // Used to read a Settings value before overriding it, so it can be restored later.
    String runShellCommandForOutput(in String[] cmd);

    void destroy();
}
