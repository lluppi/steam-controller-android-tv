# Steam Controller for Android

Use the **Steam Controller 2026** (Valve, codename *Ibex*) as a standard Android gamepad — no root required. Connect via USB OTG / wireless Puck, or directly via Bluetooth.

The app reads the controller's proprietary HID protocol and exposes it to Android as a virtual gamepad (accessed via Shizuku), so any game that supports controllers sees a real input device — Xbox 360, Xbox One, DualShock 4 or DualSense, your choice. A **Desktop mode** turns the same controller into a virtual mouse + keyboard, ideal for Android TV boxes.

This is a fork of [SonicDX12/SteamController-Android](https://github.com/SonicDX12/SteamController-Android) that adds a **`/dev/uhid` output backend** for devices where `/dev/uinput` is closed to the shell UID — notably the NVIDIA Shield TV, where it belongs to `system:bluetooth`. The controller then still appears as a real `InputDevice`, with no root and no fallback to input injection. See `PLAN.md` and `HANDOVER.md`.

## Features

### Connection

- **USB OTG** — wired or via the wireless Puck dongle
- **Bluetooth LE** — direct pairing with the controller, no dongle needed
- **Live transport switching** in the UI (toggle group with USB and Bluetooth icons)
- **Refresh paired BT devices** without restarting the app
- **In-app help dialog** explaining the controller's wireless mode combos (Steam+A+R1, Steam+B+R1, etc.)

### Emulation

- **Five virtual profiles**: Xbox 360 (default), Xbox One, Sony DualShock 4, Sony DualSense, and **Desktop** (mouse + keyboard)
- **Cycle gamepad profiles directly from the notification** (`↻ → next profile`) without opening the app
- **Real `InputDevice`** via Linux `uinput` (UID shell via Shizuku UserService) — recognised by games as a real gamepad, not filtered like injected events
- **Automatic fallback** to `IInputManager.injectInputEvent` if `/dev/uinput` is denied (less compatible, kept as safety net)

### Desktop mode

- Turns the controller into a virtual **mouse + keyboard** — right trackpad drives the cursor, left trackpad scrolls, buttons map to common keys (volume, play/pause, back, home, enter, escape, tab, space, etc.)
- Even while a gamepad profile is active, the trackpads can double as a mouse sidecar (toggle in Calibration) so you can still navigate menus without switching profiles
- Full **Android TV** support: dedicated banner/leanback UI, D-pad focus navigation, on-screen keyboard shows up correctly when a text field is focused

### Game Profiles

- Save the current calibration, button mapping, rumble intensity and mouse sensitivity as a **named preset**
- Load, rename, duplicate or delete presets from a dedicated screen
- **Bind a preset to one or more apps** — the service automatically switches profile when you launch a bound game (foreground-app detection, no manual step)

### Tuning

- **Per-stick calibration** — radial dead zone (0–30%), center offset capture, Y-axis inversion, live 2D preview
- **Custom button mapping** — categorised list (Face / Bumpers / Triggers full-press / Stick clicks / System / Back paddles / Grips), using the official Steam Input button icons. Any source button to any target, including back paddles L4/L5/R4/R5, the Quick Access Menu button, and forcing a trigger to "fully pulled"
- **Special actions** — map any button to **📸 Take screenshot** (saved in Pictures/Screenshots, visible immediately in the gallery)
- **Rumble forwarding pipeline** with adjustable intensity (0–100%) and a manual "Test rumble" button (Bluetooth only for now — see Known limitations)

### Backup & restore

- **Export** every live setting and all Game Profiles to a single JSON file via the system file picker
- **Import** that file back at any time — handy after reinstalling the app or moving to a new phone

### Auto-update

- Checks GitHub Releases for a newer version at launch (once every 24h) or on demand
- Shows the release notes and downloads the signed APK straight from GitHub, then hands off to the system installer

### Debug & status

- **HID debug view** — every button, stick, trigger, trackpad, IMU quaternion and raw hex dump, updated at the controller's ~300 Hz
- **Battery indicator** in the status card and in the notification — works over both USB and Bluetooth
- **Persistent foreground service notification** with the active emulation profile, battery, profile-cycle action, and stop action

### Platform

- **Material 3** design with Steam blue accents
- **Adaptive layouts** — phone (max-width 520dp), tablet (`sw600dp`, two-column layouts) and Android TV (`television`, leanback navigation)

## Requirements

- Android 8.0+ (API 26) — phone, tablet, or Android TV
- [Shizuku](https://shizuku.rikka.app/) installed and running
- For USB: USB Host (OTG) support on the phone/tablet
- For Bluetooth: standard BLE (available on every modern Android)
- A **Steam Controller 2026** (Valve Ibex). The older Steam Controller is not yet supported.

## Setup

1. Install [Shizuku](https://shizuku.rikka.app/) and start it (ADB Wireless on Android 11+, or one-time ADB cable for older versions).
2. Install this app and grant it the Shizuku permission when prompted.
3. Grant the **POST_NOTIFICATIONS** permission when asked (Android 13+) so the foreground status notification shows up.
4. **For USB**: plug the Puck (or the controller directly) into the OTG port. Android will ask for USB permission.
5. **For Bluetooth**: pair the controller via Android Settings → Bluetooth first, then select it from the Bluetooth device dropdown in the app. Use the `↻` refresh button if you just paired it.
6. Pick the emulated controller profile (Xbox 360 is the safest default for games — broadest compatibility. Pick Desktop for mouse + keyboard, e.g. on Android TV).
7. Hit **Start Service**. The status card shows `Mode: <profile> (uinput) ✓` when everything is up.
8. Optional: tune everything to your liking (calibration, mapping, rumble) and save it as a **Game Profile** — bind it to a game so it auto-loads next time you launch it.

If `uinput` is blocked by SELinux on your device (rare on stock Android, possible on some hardened ROMs), the app falls back to `injectInputEvent`, which works in most apps but is filtered by many games.

## How it works

```
Steam Controller (USB or BT)
         │
         ▼
HID report parser  (report 0x45 state, 53B USB / 45B BLE — plus a
                     dedicated 0x43 battery status report)
         │  validated against SteamlessController.h + hardware capture
         ▼
ControllerService
   • debounce (15-bit injectable mask, 3 frames)
   • baseline state (ignore buttons held at startup)
   • mapping (Steam buttons → Xbox buttons or special actions)
   • per-stick calibration
   • rumble intensity scaling
   • foreground-app polling → Game Profile auto-switch
         │
         ▼
UInputGamepad → AIDL/Binder → UInputService (UID shell via Shizuku)
                                       │
                                       ▼
                                JNI uinput_jni.cpp
                                       │
                          ┌────────────┴────────────┐
                          ▼                          ▼
                  gamepad device            mouse+keyboard sidecar
                (Xbox/DS4/DualSense)      (Desktop mode, or trackpad-
                                            as-mouse alongside a gamepad)
                          │                          │
                          └────────────┬─────────────┘
                                       ▼
                       backend seam (cpp/output_backend.h)
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
            /dev/uinput → kernel                  /dev/uhid → kernel
       preferred where the shell UID can      used where /dev/uinput is denied,
       open it (it has force feedback)        e.g. NVIDIA Shield TV, where it
                                              belongs to system:bluetooth
                    └──────────────────┬──────────────────┘
                                       ▼
                        Android sees a real "Microsoft
                       X-Box 360 pad" (or DS4, mouse, etc.)
```

The HID protocol is parsed natively and translated into the chosen profile's button/axis layout before being written to virtual devices. The Shizuku `UserService` runs as the `shell` user (UID 2000). Which backend it uses is decided at runtime by probing both: `uinput` first where it is permitted, because only it can deliver force feedback, and `uhid` otherwise — where the descriptor is handed to the kernel and `hid-generic` produces the same three devices. The one in use is named on the status card.

For BLE, the standard HID service (`0x1812`) is claimed by the OS, so the app uses Valve's vendor service (`100f6c32-1735-4313-b402-38567131e5f3`) directly. Connection priority is bumped to `HIGH` after connect to bring the BLE interval from ~50 ms down to ~11 ms.

## Build

Standard Android Gradle build, requires:

- Android Studio Hedgehog or newer
- Android Gradle Plugin 8.5+
- Kotlin 2.0+
- NDK + CMake 3.22.1 (for the native `uinput` JNI library)

**First time:** open the project in Android Studio and let it sync — this regenerates the Gradle wrapper. After that:

```bash
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

The app supports `arm64-v8a`, `armeabi-v7a` and `x86_64` ABIs.

For signed release builds and publishing to GitHub Releases, see [RELEASING.md](RELEASING.md).

## Configuration

Live settings and Game Profiles are persisted in `SharedPreferences`:

- Selected transport (USB or Bluetooth) and paired BT device address
- Emulated profile (Xbox 360 / Xbox One / DS4 / DualSense / Desktop)
- Per-stick calibration (dead zone, center offset, invert Y)
- Per-button mapping (source buttons → Xbox targets, keyboard keys, or special actions like screenshot)
- Rumble intensity (0–100%) and mouse sensitivity (Desktop mode / trackpad sidecar)
- Named Game Profiles, each with its own copy of the settings above plus the list of apps it auto-switches on

You can tweak everything live — most changes apply within ~250 ms (next mapping cache refresh) without restarting the service. Changing transport or emulated profile requires restarting the service (or use the notification's profile cycle action). Use **Export backup** / **Import backup** in the Game Profiles screen to move all of this to a JSON file — useful before uninstalling the app or when setting it up on a new device.

## Troubleshooting

**Nothing responds, but the OS says the controller is bonded.** Check the LED colour. The controller wakes into the wireless mode it last used, so it is often hunting for a puck that is not there: hold `B + R1 + Steam` until the chime and a **blue** LED for Bluetooth (white means a puck slot, green means wired). The status card says this too when the link stalls.

**Controller went to sleep.** Not a bug: it powers itself down when idle, and the app keeps retrying with backoff (and immediately when the Bluetooth link comes back), so pressing Steam is enough — no need to restart the app.

**Everything is dead after a reboot.** Shizuku does not survive a reboot on Android 9 and cannot be restarted from the TV (there is no wireless-debugging pairing that old). Start it from a PC — the exact command is in `HANDOVER.md` §7 — after which the app picks up on its own. The status card reports this state explicitly.

**Testing from a PC: stop the service before installing.** `adb install` kills the app mid-session, and a GATT client that dies without closing leaves the controller wedged until it is power-cycled or re-paired. `adb shell run-as com.steamcontroller.android.debug am stop-service --user 0 -n com.steamcontroller.android.debug/com.steamcontroller.android.service.ControllerService` first, then install.

## Known limitations

- **Virtual device backend**: the app probes `/dev/uinput` and falls back to `/dev/uhid`. Where both are refused by SELinux it injects input events instead, which most apps ignore — the status card names the backend in use and the reason when one is unavailable.
- **Steam button** passes through as `KEYCODE_BUTTON_MODE`. Android handles it as the system "Guide" key which may open the launcher in some setups.
- **Rumble byte format** is an empirically-tuned best guess based on the Linux `hid-steam` driver. Works over Bluetooth. **USB rumble is not implemented yet** — the controller only vibrates when connected over BT.
- **Trackpads**: usable as a mouse (Desktop mode, or as an optional sidecar cursor alongside a gamepad profile). Not yet exposed as a DualShock 4/DualSense touchpad to games that support one natively. **The right trackpad click works; the left trackpad click is not reported anywhere in the controller's vendor report**, so it cannot be mapped — the left pad scrolls. Evidence in `docs/evidence/P3-S6.md`.
- **Rumble needs the `uinput` backend.** `hid-generic` implements no force feedback for the `uhid` descriptors, so on those devices games' rumble requests never arrive. Calibration says so instead of offering a dead test button.
- **Gyroscope** (quaternion IMU) is parsed but not yet routed anywhere. Gyro aiming is planned for a future release, fits best with the DualShock 4 / DualSense profiles.
- **Shizuku at reboot**: the user must restart Shizuku after each reboot of the device (an Android limitation, not the app's). The service starts on boot and the status card says what is missing.

## Roadmap

- USB rumble implementation
- Trackpad as a real touchpad input (DS4/DualSense profile) for games that support it
- Gyro aiming for DS4 / DualSense profiles
- Rumble without `uinput` (DualShock 4 emulation via `hid-sony`, which is present on the Shield's kernel)
- RetroArch autoconfig profiles and back-paddle presets
- HID debug log export ("Log to File")

## Credits

The HID protocol reverse engineering credit goes to:

- [**SteamlessController**](https://github.com/ddeverill/SteamlessController) by ddeverill — the definitive `SteamController.h` byte layout reference for the SC2026
- The [**Linux kernel `hid-steam` driver**](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-steam.c) for additional validation of button bit positions and the rumble command structure

Other key dependencies:

- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps — the `uinput` access path without root
- [Android USB Host API](https://developer.android.com/guide/topics/connectivity/usb/host) and the BLE GATT stack
- [Material Components for Android](https://github.com/material-components/material-components-android) for the Material 3 UI

## License

MIT — see [LICENSE](LICENSE).
