# steam controller bridge

use a steam controller 2026 (valve, codename ibex) as a standard android gamepad, no root. usb otg or the wireless puck, or bluetooth le directly. the app parses the controller's proprietary hid protocol and exposes it as an xbox 360-compatible virtual gamepad; a desktop mode turns the same controller into a mouse and keyboard for android tv.

this is a fork of [sonicdx12/steamcontroller-android](https://github.com/SonicDX12/SteamController-Android) that adds a `/dev/uhid` output backend for devices where `/dev/uinput` is closed to the shell uid - notably the nvidia shield tv, where it belongs to `system:bluetooth`. the controller still appears as a real `InputDevice`, with no root and no fallback to input injection.

| transport | how |
| --- | --- |
| usb otg | wired, or via the wireless puck dongle |
| bluetooth le | pair the controller directly, no dongle |

| output profile | what android sees |
| --- | --- |
| xbox 360 gamepad | `Microsoft X-Box 360 pad`, plus an optional mouse/keyboard sidecar |
| desktop | virtual mouse (right trackpad cursor, left trackpad scroll) and keyboard |

| backend | when |
| --- | --- |
| `/dev/uinput` | preferred where the shell uid can open it - the only one with force feedback |
| `/dev/uhid` | where uinput is denied, e.g. the shield tv; the descriptor goes to the kernel and `hid-generic` makes the same devices |
| `injectInputEvent` | last resort where selinux refuses both; most games filter it |

which one is in use is probed at runtime, and named on the status card together with the reason when one is unavailable.

## requirements

- android 8.0+ (api 26) - phone, tablet or android tv; the app supports `arm64-v8a`, `armeabi-v7a` and `x86_64`
- [shizuku](https://shizuku.rikka.app/) installed and running
- usb host (otg) support for the wired and puck paths
- a steam controller 2026 (valve ibex). the older steam controller is not supported

not affiliated with or endorsed by valve. steam and steam controller are trademarks of valve corporation.

## setup

1. install shizuku and start it (adb wireless on android 11+, a one-time adb cable below that)
2. install this app and grant the shizuku permission when prompted
3. grant `POST_NOTIFICATIONS` when asked (android 13+) so the foreground status notification shows up
4. usb: plug the puck or the controller into the otg port, android asks for usb permission. bluetooth: pair the controller via settings first, then pick it in the app's device dropdown, `↻` if you just paired it
5. pick the emulated profile - xbox 360 is the safest default for games, desktop for mouse + keyboard
6. hit **start service**. the card shows `Mode: <profile> (uinput) ✓` when it is up
7. optional: tune calibration, mapping, gyro and rumble, save it as a game profile, bind it to an app so it auto-loads

### the optional accessibility service

shizuku's wireless-debugging start button cannot receive d-pad focus on android tv, so with only a remote there is no way to press it. the app ships an optional accessibility service, `Steam Controller Bridge - Shizuku starter`, that presses it for you:

- it is off unless you turn it on under accessibility settings. the app only suggests it when you ask it to start shizuku and shizuku is not running
- its config restricts it to shizuku's package (`moe.shizuku.privileged.api`); it sees no other app's windows
- it stays inert until you ask the app to start shizuku, disarms after 45 seconds or once it has picked the adb port, and only ever clicks shizuku's start and port buttons

on a phone or tablet, or if you start shizuku some other way, you never need it.

everything is live: most changes apply within ~250 ms, without a restart. changing transport or profile restarts the service (or use the notification's profile cycle action).

## how it works

```text
steam controller (usb or bt)
         │
         ▼
hid report parser   report 0x45 state, 53b usb / 45b ble,
                    plus a 0x43 battery status report
         │
         ▼
ControllerService   debounce (15-bit injectable mask, 3 frames),
                    startup baseline, button mapping, per-stick
                    calibration, rumble scaling, foreground-app
                    polling → game profile auto-switch
         │
         ▼
UInputGamepad → AIDL → UInputService (uid shell via shizuku)
         │
         ▼
jni uinput_jni.cpp  → backend seam (cpp/output_backend.h)
         │
    ┌────┴────┐
    ▼         ▼
uinput     uhid            both, plus mouse+keyboard sidecars,
(kernel)   (kernel)        land as a real "Microsoft X-Box 360 pad"
```

the parser is native, and translates the hid report into the chosen profile's layout before writing to virtual devices. the shizuku `UserService` runs as the `shell` user (uid 2000) and probes both backends: `uinput` first where it is permitted, because only it can deliver force feedback, `uhid` otherwise.

for ble, the standard hid service (`0x1812`) is claimed by the os, so the app talks to valve's vendor service (`100f6c32-1735-4313-b402-38567131e5f3`) directly. connection priority is bumped to `HIGH` after connect to bring the ble interval from ~50 ms down to ~11 ms. the controller is resolved from the bonded list, so a re-paired controller works even though ble gives it a new random address every pairing.

## build

standard android gradle build. needs android studio hedgehog or newer, android gradle plugin 8.5+, kotlin 2.0+, and the ndk with cmake 3.22.1 for the native jni library.

the gradle wrapper is committed, so android studio or the command line both work:

```sh
./gradlew assembleDebug
# apk lands in app/build/outputs/apk/debug/
```

signed release builds: `scripts/generate-keystore.sh` makes a keystore, `keystore.properties.template` shows the values it needs, then `./gradlew assembleRelease`. `.github/workflows/release.yml` builds and publishes one when you push a `v*` tag.

## configuration

live settings and game profiles live in `SharedPreferences`: transport and paired bt address, output mode, per-stick calibration (dead zone 0-30%, centre offset, invert y), per-button mapping (face / bumpers / triggers / stick clicks / system / back paddles / grips, or special actions like `📸 take screenshot`), rumble intensity 0-100%, mouse sensitivity, and named profiles each carrying its own copy plus the apps it switches on automatically.

`export backup` / `import backup` in the game profiles screen moves all of it to one json file - useful before uninstalling or when moving to a new device.

## troubleshooting

**nothing responds, but the os says the controller is bonded.** the controller wakes into the wireless mode it last used, so it is often hunting for a puck that is not there. check the led: hold `b + r1 + steam` until the chime, blue is bluetooth, white is a puck slot, green is wired. the status card doubles as a reconnect action and says the same thing.

**controller went to sleep.** not a bug, it powers down when idle. the app keeps retrying with backoff and immediately on `ACTION_ACL_CONNECTED`, so pressing steam is enough.

**everything is dead after a reboot.** shizuku does not survive a reboot on android 9 and cannot be restarted from the tv, so start it from a pc. copy shizuku's apk to `/data/local/tmp/shizuku.apk` once, then:

```sh
adb shell 'nohup sh -c "CLASSPATH=/data/local/tmp/shizuku.apk app_process /system/bin \
  --nice-name=shizuku_server moe.shizuku.server.ShizukuService --debug=false" \
  > /data/local/tmp/shizuku.out 2>&1 &'
adb shell 'ps -A | grep shizuku_server'      # runs as shell
```

the app picks up on its own afterwards. the card reports this state explicitly.

**testing from a pc.** stop the service before `adb install` - a gatt client that dies without closing leaves the controller wedged until it is power-cycled or re-paired:

```sh
adb shell run-as io.github.lluppi.steamcontrollerbridge.debug am stop-service --user 0 \
  -n io.github.lluppi.steamcontrollerbridge.debug/com.steamcontroller.android.service.ControllerService
```

debug builds install side by side with a release build (`applicationIdSuffix = ".debug"`), so testing never replaces a working install.

## known limitations

| limitation | detail |
| --- | --- |
| no rumble on `uhid` | `hid-generic` implements no force feedback for these descriptors, so games' rumble requests never arrive. calibration says so instead of offering a dead test button |
| usb rumble | not implemented - the controller only vibrates over bluetooth |
| left trackpad click | not reported anywhere in the controller's vendor report, so it cannot be mapped. the right pad clicks, the left pad scrolls |
| trackpads | usable as a mouse, not yet exposed as a ds4/ds5 touchpad to games that support one natively |
| gyroscope | gyro aiming is mixed into the right stick (tuned and bias-calibrated on the calibration screen). games never see a real motion sensor |
| steam button | passes through as `KEYCODE_BUTTON_MODE`, android treats it as the system guide key and may open the launcher |
| shizuku at reboot | must be restarted by the user after each reboot (android 9 limitation, not the app's) |
| rumble byte format | empirically tuned from the linux `hid-steam` driver |
| inject fallback | where selinux refuses both backends the app injects input events, which most apps ignore |

## roadmap

- usb rumble
- trackpad as a real touchpad input (ds4/ds5 profile)
- gyro as a native motion sensor for ds4 / ds5
- rumble without `uinput` (dualshock 4 emulation via `hid-sony`, present on the shield's kernel)
- retroarch autoconfig profiles and back-paddle presets
- hid debug log export ("log to file")

## credits

- [steamlesscontroller](https://github.com/ddeverill/SteamlessController) by ddeverill - the `SteamController.h` byte layout reference for the sc2026
- the [linux kernel `hid-steam` driver](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-steam.c) for additional validation of button bit positions and the rumble command structure
- [shizuku](https://github.com/RikkaApps/Shizuku) by rikkaapps - the `uinput` access path without root
- the [android usb host api](https://developer.android.com/guide/topics/connectivity/usb/host) and the ble gatt stack
- [material components for android](https://github.com/material-components/material-components-android) for the material 3 ui

## license

mit - see [LICENSE](LICENSE), which carries upstream's copyright and this fork's.
