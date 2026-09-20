# Changelog

## 2.1-shield (unreleased)

Fork of upstream 2.0 that adds a **`/dev/uhid` output backend**, so the controller produces a
real Android `InputDevice` on hardware where `/dev/uinput` is closed to the shell UID (NVIDIA
Shield TV). Background: `PLAN.md`, current state and resume points: `HANDOVER.md`.

### Added

- **`uhid` output backend** — gamepad, mouse and keyboard as genuine HID devices. Selected
  automatically when `/dev/uinput` is denied. `uinput` is still preferred where it works,
  because `hid-generic` implements no force feedback for these descriptors.
- **Backend preference** (`auto` / `uinput` / `uhid`), and a status card that names the backend
  in use and reports the probe reason when one is unavailable.
- **Honest link status** — the card is only green when frames are actually arriving, and
  otherwise says why: no controller paired, not connected (press Steam to wake it), link
  stalled (with the wireless-mode hint), or Shizuku not running after a reboot. The card doubles
  as a reconnect action.
- **GATT auto-reconnect** with backoff, an `ACTION_ACL_CONNECTED` receiver so a controller that
  just woke is retried immediately, a handshake watchdog so a stalled subscription cannot wedge
  the app forever, and stale-client cleanup before each connect.
- **Controller resolved from the bonded list**, so a re-paired controller works even though BLE
  gives it a new random address every pairing (previously this needed clearing app data).
- **`BOOT_COMPLETED`** receiver; start-on-boot is on by default and can be turned off.
- **Right trackpad click** mapped (button-mask bit 22, identified on hardware).
- **Back paddles default to the stick clicks** — `L4 -> L3`, `R4 -> R3`, which is what emulators
  use for save/load state.
- Debug builds install **side by side** with a release build (`applicationIdSuffix = ".debug"`),
  so testing never replaces a working install.

### Fixed

- **A duplicate service start broke the link.** `onStartCommand` re-ran initialization on top of a
  live connection, opening a second GATT client; the handshake then stalled within seconds. A
  duplicate start for the same transport is now ignored (a transport change still restarts).
- **Cursor snapped back** to where the swipe began when the finger left the trackpad — the
  firmware zeroes the pad coordinates on lift while the touch flag is still set, which was read
  as motion.
- **Radio labels rendered black** — the theme used a `DayNight` parent with a hardcoded dark
  palette, so off night-mode it resolved to the light scheme.
- **Scrolling left ghost trails** of the previous content — the root view had no background of
  its own.
- **TV focus order** — `DOWN` could not reach Start/Stop, `UP` jumped past it to the Bluetooth
  refresh button, and the action row forgot which card you came from.
- **Automatic bond recreation removed.** It unpaired controllers that had merely gone to sleep,
  since a sleeping controller is indistinguishable from a wedged one at the link layer.

### Known limitations

- **The left trackpad click cannot be mapped.** It is not reported anywhere in the controller's
  vendor report — proven with a labelled capture, see `docs/evidence/P3-S6.md`. The right pad
  clicks, the left pad scrolls.
- **No rumble on the `uhid` backend** — `hid-generic` implements no force feedback for these
  descriptors, so games' rumble requests never arrive. The Calibration screen says so instead of
  offering a dead button.
- **Shizuku must be restarted after every reboot** (an Android 9 limitation, not this app's), and
  starting it needs an adb command since Android 9 has no wireless-debugging pairing. See
  `HANDOVER.md` §7.
