# HANDOVER — Steam Controller on Shield TV via `/dev/uhid`

Everything learned building this fork: what works, what's broken, how to test it, and where
to pick up. Companion documents: `PLAN.md` (the phase/step plan), `docs/evidence/P1-S3.md`
(uhid spike) and `docs/evidence/P3-S6.md` (app-on-device verification, bit mappings, and the
raw protocol analysis).

The device's LAN address and the controller's device id live in the private notes
(`~/notes/projects/steam-controller-android-tv.md`) — never put them in this repo. Below,
`$SHIELD` means `adb -s <address>:5555`.

---

## 1. What this is

A fork of [SonicDX12/SteamController-Android](https://github.com/SonicDX12/SteamController-Android)
v2.0 (MIT, upstream `9514fcc`) adding a **`/dev/uhid` output backend**.

Upstream creates its virtual gamepad through `/dev/uinput`, which on the Shield belongs to
`system:bluetooth` and is permanently denied to the `shell` UID that the Shizuku user service
runs as. It then falls back to input *injection*, which the system filters — so a trackpad
mouse cursor only works inside the focused window, never on the launcher, and the controller
is second-class everywhere except one emulator.

`/dev/uhid` is open to the shell and can present the same three virtual devices as **real HID
devices**: the kernel binds hid-generic and Android gets genuine `InputDevice`s.

- Branch: **`shield-uhid`**, 24 commits on top of upstream, tree clean
- Fork repo (`origin`) is deliberately unset — pick a GitHub name before pushing

## 2. Target device

| | |
| --- | --- |
| Device | NVIDIA Shield Android TV (codename `mdarcy`) |
| OS | Android 9 / SDK 28, `PPR1.180610.011`, `user`, `ro.debuggable=0` |
| Kernel | `4.9.140-tegra-g19e7acaac93b` |
| Display | physical 4K, **app surface 1920x1080 @ density 320** (so 540dp smallest width) |
| SELinux | Enforcing; shell has no root |
| Shell groups | includes `3011(uhid)` and `1004(input)` |

```text
/dev/uhid     crw-rw---- uhid   uhid          <- shell CAN open it
/dev/uinput   crw-rw---- system bluetooth     <- shell CANNOT (this is the whole problem)
```

## 3. Workstation setup (already done, not in the repo)

| thing | path |
| --- | --- |
| JDK 17 (AGP 8.5 / Gradle 8.7 need 17; system JDK 26 is too new) | `~/.local/jdk17` |
| Android SDK (platform 35, build-tools 35.0.0, NDK 26.1.10909125, cmake 3.22.1) | `~/Android/Sdk` |
| Gradle JDK pin | `~/.gradle/gradle.properties` → `org.gradle.java.home=~/.local/jdk17` |
| SDK path | `local.properties` → `sdk.dir=~/Android/Sdk` (gitignored) |
| adb | `~/Android/Sdk/platform-tools/adb` |

`compile_commands.json` is symlinked at the repo root (gitignored) pointing at
`app/.cxx/Debug/*/arm64-v8a/compile_commands.json`, so clangd and the lsp tools resolve the
NDK sysroot. Without it every C++ file is reported as `android/log.h` / `jni.h` not found.

Build: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

Debug builds carry `applicationIdSuffix = ".debug"`, so they install **alongside** an existing
build instead of replacing it (a different signing key makes an in-place upgrade impossible,
and uninstalling would take the user's calibrations and profiles with it).

## 4. Verified working on hardware

All of this was confirmed on the Shield, not inferred:

- **Backend selection picks `uhid` automatically** because `/dev/uinput` is denied, in the
  shell-UID process (`ps` shows `…debug:uinput` under user `shell`)
- **All three devices created and classified correctly:**

  | device | Android sources |
  | --- | --- |
  | `Microsoft X-Box 360 pad` | `0x01000511` = GAMEPAD \| KEYBOARD \| JOYSTICK |
  | `Steam Controller Mouse` | `0x00002002` = MOUSE \| POINTER |
  | `Steam Controller Keyboard` | `0x00000301` = KEYBOARD \| DPAD |

- Android matched the real Xbox 360 keylayout (`Vendor_045e_Product_028e.kl`) from the
  advertised VID/PID, so this backend is interchangeable with uinput for keylayouts and for
  RetroArch autoconfig
- **All 11 buttons** land on exactly the codes the uinput backend declares, with no
  `BTN_C`/`BTN_Z` (the skipped-index design in `XBOX_BUTTONS`)
- **All 8 axes** (`ABS_X/Y/RX/RY/Z/RZ/HAT0X/HAT0Y`), reports at ~100Hz
- **Cursor tracking and scrolling work**; the "snaps back when you let go" bug is fixed
- **Right trackpad click works** (bit 22)
- A controller **re-paired to a new BLE address is resolved automatically**
- After a controller power-cycle the app **reconnects with no user interaction** (ACL receiver
  - retry loop)
- Notification carries the profile-cycle action, which only exists in a virtual-device mode

## 5. OPEN — the left trackpad click

**Status: cannot be mapped from the vendor report. The firmware does not report it.**

A controlled capture settled this: four labelled groups of clicks (left x3, right x3, left x3,
right x3) produced only **two** click groups on the virtual mouse, both accompanied by
`REL_X`/`REL_Y` motion — i.e. the right pad. The two left-pad groups produced **no events at
all**, and the app's transmission log never fired for them.

- Right pad click = **bit 22**, mapped to `BTN_LEFT`, works
- Left pad click = **no bit anywhere**; `Buttons.TP_LT_CLICK` (bit 26) is a constant this
  firmware never sets
- `0x36000000` (once thought to be evidence of it) was a transition artefact
- The `100f6c79` 5-byte notifications are a **heartbeat**, not the click (steady
  `01 02 00 00 00` ↔ `00 02 00 00 00` alternation, no grouping that matches presses)

**The one remaining place it could be:** the controller's **standard HID interface**, which the
app silences with the `DISABLE_LIZARD` write (`0x85`) and never re-enables. The notes' own
appendix records that interface as carrying `BTN_MOUSE`/`BTN_RIGHT`.

Test (needs a human and a power-cycle):

1. Make sure the app is **not running** (stop the service, or uninstall)
2. **Power-cycle the controller** so it boots without lizard mode having been disabled
3. `getevent -lt` the native HID node — find it by name `Steam Ctrl (BT) FXA9963105495`
   (it has been `event5`; node numbers shift, always look it up by name)
4. Click the **left** pad

If `BTN_MOUSE` appears there, the click exists and the app suppresses it — but **the fix is a
trade-off, not a clean win**: that interface also emits its own cursor motion, so enabling it
means two sources driving the pointer, and its keyboard interface is what makes Android treat
a hardware keyboard as attached.

If it is silent there too, the left click is simply unreachable, and the honest recommendation
is to keep the right pad as the click and the left pad for scrolling.

## 6. Findings that will save you a day

### Protocol

- **The state report length varies between sessions: 45 and 46 bytes have both been seen.**
  Any per-byte analysis must pin offsets relative to the report id (`byte[0] == 0x45`) or it
  is silently wrong. An earlier "the button field is only byte 5" conclusion came from exactly
  that mistake and was retracted — **the `Buttons` constant table needs a proper audit**, since
  at least one constant (`TP_RT = 0x00200000`) appears unreachable.
- Reported masks during normal use: `0x30000000`, `0x30200000`, `0x30600000`, `0x32000000`,
  `0x36000000`, `0x36600000` (plus `0x10200000`, `0x10600000`). Bits 28/29 look like status
  flags, bit 21 fires with pad contact, bit 22 is the right-pad click, bit 26 is *not* the left
  click.
- **`keys=0x10000` in a log line says nothing about which pad produced it.** Both wrong
  conclusions in this project came from attributing such lines to a gesture by *time window*.
  Attribute with a labelled capture instead.
- The virtual mouse device filters ABS values by `fuzz` (255 on the stick axes), so **a gamepad
  capture can legitimately come back empty while the app streams normally** — an untouched
  stick emits nothing even though a full report is written every frame.

### Android / Bluetooth behaviour

- **Installing over a live GATT connection wedges the controller.** `adb install -r` SIGKILLs
  the app mid-session, the GATT client dies without closing, and the next client connects at
  the link layer while every GATT operation stalls. It presents as "connected but the
  controller is dead". **Always stop the service first** (see §7). This caused *every* wedge
  during development.
- **A sleeping controller is indistinguishable from a wedged one**: both connect, then stop
  answering GATT. Do not treat that as a reason to re-pair — it will unbond a perfectly good
  controller. (This happened three times before it was understood.)
- **Do not drive `createBond()` in a loop.** Doing so pinned `com.android.bluetooth` at ~40%
  CPU, pushed the box into swap, and made the UI *and the BLE remote* unresponsive until the
  app was force-stopped. Pairing is the user's decision; the app now only logs.
- **BLE peripherals get a new random address on every pairing.** A cached address becomes a
  device that no longer exists, so `getRemoteAddress()` succeeds, `connectGatt` never
  completes and the app retries forever — this was the hidden cause of the notes' "clear app
  data" ritual. Resolve from the bonded list instead.
- **Starting the service twice breaks it.** `onStartCommand` re-ran `initialize()` on top of a
  live connection, opening a second GATT client; the handshake then stalled within seconds.
  A duplicate start is now ignored (a transport change still restarts, deliberately).
- **A Shizuku user service is leaked** when the app process dies without `destroy()` running
  (e.g. any `adb install`). Each leftover holds a JVM (~110MB) and its uhid devices, so dead
  duplicates accumulate: RetroArch can then bind a gamepad whose owner is gone. Clean up with
  `ps -A | grep :uinput` + `kill -9`, keeping the newest.
- Shizuku does **not** survive a reboot on Android 9 and the app cannot use uhid until it's
  restarted (§7).

### Tooling traps on this device

- `nohup <cmd> &` is **not enough** to background something across an adb session — it dies
  with the shell. Use `nohup sh -c "<cmd>" > file 2>&1 &`.
- The AOSP `hid` tool cannot be backgrounded at all, and running it for more than a few
  seconds **drops the adb link** (not a crash: uptime advances and there are no oops).
- `uiautomator dump` fails roughly half the time; always delete the target file first and check
  it was recreated, or you will read a stale dump as a fresh result.
- `svc bluetooth` and `cmd bluetooth_manager` are both unavailable/denied on this build, so the
  BT stack cannot be cycled from shell.

## 7. The test toolkit

**Start / stop the service from adb** — as the app's own uid, because debug builds are
debuggable. No DPAD focus-walking needed:

```sh
SVC=com.steamcontroller.android.debug/com.steamcontroller.android.service.ControllerService
adb shell run-as com.steamcontroller.android.debug am start-foreground-service --user 0 -n $SVC
adb shell run-as com.steamcontroller.android.debug am stop-service         --user 0 -n $SVC
```

`--user 0` is required on **both**; without it you get
`Permission Denial: service asks to run as user -2`.

**Restart Shizuku after a reboot** (pre-staged copy exists at `/data/local/tmp/shizuku.apk`):

```sh
adb shell 'nohup sh -c "CLASSPATH=/data/local/tmp/shizuku.apk app_process /system/bin \
  --nice-name=shizuku_server moe.shizuku.server.ShizukuService --debug=false" \
  > /data/local/tmp/shizuku.out 2>&1 &'
adb shell 'ps -A | grep shizuku_server'      # runs as shell
```

**Watch frames** (look the node up by name — numbers shift):

```sh
ev=$(adb shell 'getevent -p' | grep -B1 'X-Box 360 pad' | grep -o 'event[0-9]*' | head -1)
adb shell "nohup sh -c 'timeout 60 getevent -lt /dev/input/$ev' > /sdcard/gp.txt 2>&1 &"
```

**Tags worth watching:** `virtual_input` (backend choice), `uhid_backend` (device lifecycle),
`uinput_backend`, `UInputGamepad` (the sidecar probe + `sidecar CLICK` lines),
`BluetoothHidManager` (link state machine), `ControllerService`, `EventHub`/`InputReader`
(what Android did with the devices).

**Diagnostics built in that are worth keeping:**

- a throttled sidecar probe logging touch flags, pad coordinates, computed delta, key mask and
  the raw button mask on every change
- `sidecar CLICK:` logging the exact key mask handed to the native layer
- `raw[…]` dumping the whole state report whenever the button mask changes (careful: it is
  verbose; this is what produced the protocol analysis above)

**UI automation (rarely needed now):** press buttons with
`input keyevent 23` after walking focus with `input keyevent 20`; if focus will not move, `61`
(TAB) breaks it out. The device has **no touchscreen**, so `input tap` does nothing.

## 8. Remaining plan work

From `PLAN.md`:

- **P4.S2 — honest link status in the UI.** The app now logs "No bonded Steam Controller — pair
  it again" but the user still just sees "disconnected". Surface the real reason (not paired /
  link stalled / controller asleep) and offer a reconnect.
- **P5 — RetroArch autoconfig + back-paddle defaults.** Ship per-profile `.cfg` files (the
  `keylayout` and device names are verified), default `L4/R4 → L3/R3`, and a save/load-state
  preset.
- **P6 — rumble.** `hid-generic` implements no force feedback for these descriptors, so games'
  rumble never reaches the app. `/sys/bus/hid/drivers/sony` **is present** on this kernel, so
  emulating a DualShock 4 through `hid-sony` is the viable route. Today the Calibration screen
  correctly disables the test button and says rumble needs uinput.
- **P7 — release + upstream PRs.** Split into: backend seam + uhid, GATT auto-reconnect, honest
  link status, RetroArch autoconfig.

**Before any upstream PR:** the ktlint auto-fix on this workstation applies the `ktlint_official`
style on every file write and reformatted whole files, so several Kotlin files carry cosmetic
churn that fights upstream's IntelliJ style (it turned `BLUETOOTH(1, "Bluetooth");` into a
dangling `;` on its own line). A repo `.editorconfig` was added declaring the intended style but
the agent-side auto-fix does not honour it; review those PRs with `git diff -w`.

**ktlint cannot simply be "fixed" on this codebase, and that was measured.** The Gradle ktlint
plugin was wired up and run: upstream violates its *standard* ruleset throughout — max line length
(100), `multiline-if-else`, `statement-wrapping`, `no-semi`, `trailing-comma-on-call-site`,
`function-literal` — in files this fork never touched (`AppPickerActivity`, `BackupManager`,
`GamepadMapper`, `SteamReportParser`, `ShizukuInputInjector`, `UsageStatsHelper`, …).
`ktlintFormat` therefore rewrote ~16 unrelated upstream files, which had to be reverted, and a
`ktlintCheck` in CI would fail on day one. So the plugin was removed again. Adopting ktlint is a
deliberate whole-codebase decision — one dedicated formatting commit, reviewed with `git diff -w`
— not something to bolt on as a cleanup.

## 9. Incident log (what actually happened, and why)

Worth reading before touching this again, because most of these were self-inflicted and each
one cost real time:

1. **Built the uhid backend and verified it on hardware** — backend auto-selected, three
   devices classified correctly, all buttons and axes on the correct codes.
2. **`refresh()` + retry loop** built to recover a wedged handshake. The stall was real
   (subscription descriptor write never completed) but the loop alone could not fix it.
3. **Added a bond escalation** (unbond + re-pair automatically). It fired correctly once —
   `removeBond → true`, `createBond → true` — and then **unbonded the controller twice more for
   being asleep**, because a sleeping controller looks identical. Now gated on a handshake
   having completed in that session, and no longer recreates the bond at all.
4. **A `createBond()` retry loop** (added to finish a repair after the escalation) pinned the
   BT stack, pushed the Shield into swap, and **took the BLE remote down** — the box looked
   locked up. Removed entirely.
5. **Discovered the real wedge cause:** the service re-initializing on top of a live connection
   (duplicate start). Fixed; this was behind most of the "controller went unresponsive"
   reports.
6. **Learned the address-rotation bug** — a re-paired controller has a new BLE address, and the
   cached one pointed at a device that no longer existed. Now resolved from the bonded list.
7. **Three UI bugs fixed** (reported from the TV): black radio labels (DayNight parent on a
   hardcoded dark palette), scroll ghost-trails (root view had no background), and focus order
   (DOWN/UP could not reach Start/Stop; also added focus memory for the card row).
8. **The left-pad click hunt** — two rounds of wrong conclusions drawn from log lines
   attributed to gestures by time window, corrected by a labelled capture. See §5.

## 10. If you pick this up again

Start here, in this order:

1. Read `docs/evidence/P3-S6.md` — it has the logcat proofs, the source classes, the bit
   mappings and the raw report analysis.
2. `./gradlew :app:assembleDebug`, then **stop the service and install** (§7). Never install
   while it is connected.
3. Wake the controller (it sleeps), start the service, confirm `All 6 subscriptions complete`
   in logcat, then confirm frames are flowing.
4. If you want the left-pad click: run the native-HID test in §5. That is the only unknown
   left.
5. Otherwise, take the next plan item in §8 — P4.S2 is the smallest and most user-visible.

---

## Update — second pass (2026-09-21)

### Also done now

- **P4.S2 honest link status.** `linkStatusFlow` (DISCONNECTED / LINKED / STALE) derived from frame
  liveness (3s), the Bluetooth connection state, and whether anything is bonded, each with a
  reason string. The status card shows it, is clickable to reconnect (stop + start), and the
  decision is logged on change — so it is verifiable from logcat instead of only on screen.
  Verified: `DISCONNECTED → STALE (wireless-mode hint) → LINKED`.
- **P4.S4 boot handling.** `BootReceiver` + `start_on_boot` (default on) + the honest post-reboot
  message. Declared for BOOT_COMPLETED and the permission is granted. **BOOT_COMPLETED is
  protected, so adb cannot fire it — exercise this with a real reboot.**
- **P4.S3** wireless-mode guidance, folded into the STALE status text and a new README
  Troubleshooting section.
- **P5.S1** rescoped — **read `docs/retroarch.md` before touching RetroArch profiles.** Upstream
  already matches this pad and the autoconfig dir is not writable by the app.
- **P5.S2** back paddles default to L3/R3. **P5.S3** `scripts/retroarch-cfg.sh`.
- **P7.S1** version 2.1-shield + `CHANGELOG.md`. **P7.S2** CI.
- The duplicate-start guard had a bug of its own (`initializing = true` was missing) — fixed, and
  verified by starting twice: 1 initialization, 3 duplicates ignored.

### Extra gotchas from this pass

- Through `run-as`, **`am stop-service`, `am start-foreground-service` and `am broadcast` all need
  `--user 0`**, or they fail with `service/broadcast asks to run as user -2`.
- **`BOOT_COMPLETED` is a protected broadcast** — it cannot be fired from adb for testing.
- **`uiautomator dump` wedges** with `null root node` after heavy use. Log what you want to observe
  instead of scraping the UI; that is why the link status is logged.
- A TV **screensaver** (`Sys2023:dream`) can be the focused window, so check `mCurrentFocus` before
  trusting a dump.
- The state report is **46 bytes** on current firmware with the button field in bytes 2-5 (all zero
  when idle) — but note the caveat in §6: the length has differed between sessions, so pin offsets
  to the report id before drawing conclusions from byte positions.

### Cleanup pass (same session)

- **The Shizuku user service is now reaped by a watchdog.** A clean stop always reaped it
  (verified: 0 processes after `stop-service`); the leak was specific to a SIGKILLed app, which
  leaves the service orphaned with its uhid devices still registered — that is how duplicate
  gamepads accumulate. `UInputService` now exits after 60s with no client call (the app polls at
  50 Hz while alive). Verified by force-stopping the app and watching the process disappear, then
  re-starting and watching it re-bind.
- **`onServiceDisconnected` must clear `bound`.** Leaving it `true` made `bind()`'s
  `if (bound) return` short-circuit forever: once a user service died under the app, the app could
  never create devices again without a full restart. `onBindingDied`/`onNullBinding` are handled
  the same way now.
- The two mouse modes (sidecar and desktop) had the same no-op guard, send and error handling
  copy-pasted; they share one helper now, which is also where the guard's rationale lives.
- The three diagnostics added during this work (sidecar probe, transmitted key mask, raw state
  report) are gated behind `BuildConfig.DEBUG`, so release builds stay quiet and the tools remain
  for debug builds.
- Removed a dead `android.bluetooth.BluetoothManager` import.

**Deliberately not done:**

- **The three `activity_main` layouts are near-duplicates** (`layout/`, `layout-sw600dp/`,
  `layout-television/`, ~640 lines each) and only the TV one carries the focus-order fixes. The
  other two need the same `nextFocusDown`/`nextFocusUp` additions (device field + refresh button →
  `btnToggleService`; `btnToggleService` → first card; each card → `btnToggleService`; plus
  `clickable`/`focusable` on `statusPillCard`). Left alone because it cannot be verified here and
  a bad edit would break a layout with no way to look at it. Merging the three into one layout is
  the real fix, and is a refactor of its own.
- **The service exposes five state flows** (`modeFlow`, `linkStatusFlow`, `batteryFlow`,
  `backendIdFlow`, `backendDetailFlow`). They could collapse into one `ServiceState`; each is
  meaningful and separately consumed today, so it was not worth the churn and UI risk in this pass.
