# Steam Controller 2026 on NVIDIA Shield TV — fork plan

Fork of [SonicDX12/SteamController-Android](https://github.com/SonicDX12/SteamController-Android)
(MIT, v2.0, commit `9514fcc`) adding a **`/dev/uhid` output backend** so the Shield TV
(Android 9, SELinux Enforcing, `/dev/uinput` closed to `shell`) gets a *real*
`InputDevice` gamepad **and** a system-wide trackpad mouse cursor at the same time.

Field notes, device identity, adb access recipe and every gotcha already paid for:
`~/notes/projects/steam-controller-android-tv.md` (private — contains the LAN address;
never copy it into this repo). Below, `$SHIELD` means that host:port.

Branch: `shield-uhid` (off `upstream/master`). Remote `upstream` = SonicDX12.
`origin` is intentionally unset until the owner creates the GitHub fork (P0.S1).

---

## 0. Viability verdict

**Viable. Small, well-scoped, high-confidence.** Reasons, in evidence order:

1. `/dev/uhid` is `crw-rw---- uhid uhid`, `shell` is in group `3011(uhid)`, and an open
   test as shell returned `OPENED OK` (verified on the device, see notes).
2. Android's *own* Bluetooth HID stack (`btif_hh` in Bluedroid/Fluoride) creates every
   Bluetooth keyboard/mouse/gamepad on the Shield through `/dev/uhid`. A uhid device
   surfacing as an `InputDevice` is therefore the normal path on this build, not an
   experiment. AOSP also ships the `hid` shell command (`frameworks/base/cmds/hid`,
   Android 8+) that does exactly this from the shell UID — usable for the spike with
   zero compilation.
3. The upstream architecture already isolates the output layer:
   `UInputGamepad.pushFrame()` (app UID) → AIDL `IUInputService.sendFrame(ints…)` →
   `UInputService` (shell UID, Shizuku user service) → `uinput_jni.cpp`. Everything
   controller-specific (BLE GATT, report parsing, calibration, mapping, profiles, UI)
   lives *above* the AIDL boundary and is untouched. The uhid backend is a second
   implementation of ~5 native entry points (`createDevice / sendFrame /
   sendMouseFrame / pollFFEvent / destroy`) selected at runtime inside the shell-UID
   process.
4. Upstream already models the required **three** virtual devices (gamepad + mouse
   sidecar + keyboard sidecar, see the comment block at the top of `uinput_jni.cpp` for
   why mouse and keyboard must be separate devices). The uhid backend mirrors that 1:1.
5. Rooting is rejected (data wipe, Dolby/AI-upscaling loss, Widevine risk) — see notes.

**Known risks / open questions** (each has a step below):

| Risk | Mitigation |
| --- | --- |
| uhid gamepad not classified `SOURCE_GAMEPAD` by EventHub | P1 spike gate before any app code |
| HID button index → `BTN_*` mapping subtleties (`hid-input` maps Button N → `BTN_GAMEPAD + N-1`, so indexes 3 and 6 land on `BTN_C`/`BTN_Z` and must be skipped) | Descriptor in Appendix A already skips them; P1 verifies with `getevent -pl` |
| Rumble: `hid-generic` has no force feedback, so games' FF never reaches `UHID_OUTPUT` | Out of MVP; P6 stretch (DS4 emulation via `hid-sony`, or PID class if kernel has `hid-pidff`) |
| Sidecar keyboard keys outside the Keyboard usage page (BACK, HOME, MENU, VOL±, PLAYPAUSE, SELECT) | Consumer-page collection; P3.S4 verifies Android keycode parity against the uinput backend |
| Build toolchain: workstation has JDK 26 only; AGP 8.5 / Gradle 8.7 need JDK 17 | P0.S2 |
| Device currently unreachable from the workstation (ping timed out while planning) | P0.S3 — confirm power/lease before anything else |

---

## Working conventions for workers

- One step = one PR-sized commit (or small series) on `shield-uhid`, message prefixed
  with the step id, e.g. `P3.S2: uhid gamepad descriptor + report packing`.
- Every step lists **Evidence**. Paste the actual command output (logcat lines,
  `dumpsys input` excerpts, `getevent -pl`) into the commit body or `docs/evidence/PX-SY.md`.
  Do not mark a step done on reasoning alone.
- Do not add automated tests unless a step explicitly says so.
- Never hardcode the Shield's IP/hash paths in committed files. Use `$SHIELD`.
- adb: **one connection at a time**; `input tap` does nothing (no touchscreen); use
  `input keyevent`. The full gotcha table is in the notes.
- The app owns the vendor GATT channel while running — `am force-stop
  com.steamcontroller.android` before any `getevent` capture of the *controller*.
  Capturing the *virtual* devices while the app runs is fine.
- Keep `applicationId` = `com.steamcontroller.android`. Because our signing key differs
  from upstream's, the first install must be `adb uninstall` + install — export the
  user's settings with the app's Backup feature first.

---

## Phase 0 — Bootstrap (workstation + device + baseline build)

### P0.S1 Fork on GitHub, wire `origin`

- Owner decision needed: GitHub account / repo name. Suggested `steam-controller-android-tv`.
- `git remote add origin <fork-url>`; push `shield-uhid`; keep `upstream` for rebases.
- **Evidence:** `git remote -v` shows both; branch visible on GitHub.

### P0.S2 Workstation toolchain (Arch)

- `sudo pacman -S jdk17-openjdk android-tools` (adb + JDK 17).
- Android SDK to `~/Android/Sdk` via cmdline-tools (AUR `android-sdk-cmdline-tools-latest`
  or manual zip). `sdkmanager "platforms;android-35" "build-tools;35.0.0"
  "platform-tools" "ndk;26.1.10909125" "cmake;3.22.1"`. Accept licences.
- Do **not** edit the repo's `gradle.properties` for local JDK. Put
  `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk` in `~/.gradle/gradle.properties`,
  and `sdk.dir` in a (gitignored) `local.properties`.
- Generate a debug/release keystore with `scripts/generate-keystore.sh` →
  `keystore.properties` (gitignored, template exists).
- **Evidence:** `./gradlew :app:assembleDebug` succeeds on unmodified `upstream/master`;
  APK path printed.

### P0.S3 Device access

- Power on the Shield, `adb connect $SHIELD`, accept the key prompt **with the TV
  remote**. Record `adb shell getprop ro.build.fingerprint` and `uname -r` into
  `docs/evidence/device.md` (fingerprint + kernel only, no address).
- Restart Shizuku (post-reboot requirement; derive lib path at runtime — recipe in notes).
- Reconfirm the two facts the whole plan rests on:
  `adb shell 'ls -l /dev/uhid /dev/uinput; id'`.
- **Evidence:** the `ls`/`id` output; Shizuku shows "running" on device.

### P0.S4 Baseline: reproduce upstream behaviour on the Shield

- Install the P0.S2 debug APK (uninstall upstream first, after Backup export).
- Pair the controller in Bluetooth mode (`B + R1 + Steam`, blue LED), connect in app.
- Capture `adb logcat -s uinput_jni UInputGamepad ControllerService ShizukuInputInjector`
  and confirm the known lines: `open denied: Permission denied` → `falling back to inject`.
- Note which UI element shows "limited mode" so P2.S4 can replace it.
- **Evidence:** logcat excerpt in `docs/evidence/P0-S4.md`. This is the "before".

---

## Phase 1 — uhid spike (GO / NO-GO gate)

Nothing in Phases 2+ starts until P1.S3 passes.

### P1.S1 Create a uhid gamepad from the shell with AOSP's `hid` tool

- `adb shell hid` — check usage; on Android 9 it reads JSON from a file or `-` (stdin)
  with `register` / `report` / `delay` commands. Android P's tool hardcodes
  `BUS_BLUETOOTH` for the bus and may not accept a `bus` field — that's fine.
- Push `spike/hid-gamepad-register.json` (Appendix A, already in repo) and run:
  `adb shell 'cat /sdcard/hid-gamepad-register.json - | hid -'` keeping stdin open,
  or run `hid /sdcard/spike.json` with a file that registers, delays, and reports.
- If `hid` is missing/broken: fall back to a ~150-line static C program using
  `UHID_CREATE2` / `UHID_INPUT2` (built with the NDK clang from P0.S2 as a standalone
  `aarch64-linux-android28` PIE executable, pushed to `/data/local/tmp`, run as shell).
- **Evidence:** logcat `EventHub: New device: ... name="Steam Controller (uhid spike)"`
  and `InputReader: Device added ... sources=0x01000511` (or similar containing
  `SOURCE_GAMEPAD 0x401 | SOURCE_JOYSTICK 0x1000010`).

### P1.S2 Confirm evdev capabilities

- Find the node: `adb shell getevent -pl` (look for the spike name).
- Expect `KEY: BTN_A BTN_B BTN_X BTN_Y BTN_TL BTN_TR BTN_TL2 BTN_TR2 BTN_SELECT BTN_START
  BTN_MODE BTN_THUMBL BTN_THUMBR`, `ABS: ABS_X ABS_Y ABS_RX ABS_RY ABS_Z ABS_RZ
  ABS_HAT0X ABS_HAT0Y`. If `BTN_C`/`BTN_Z` appear, the descriptor's button numbering
  drifted — fix before continuing.
- `adb shell dumpsys input | grep -A25 'spike'` → check `Sources`, `KeyboardType`, and
  which `.kl` was picked (`KeyLayoutFile`). With VID/PID `045e:028e` Android should pick
  `Vendor_045e_Product_028e.kl`; with an unknown pair, `Generic.kl`. Record both.
- **Evidence:** `getevent -pl` + `dumpsys input` excerpts.

### P1.S3 End-to-end: RetroArch sees the spike pad; mouse spike moves the cursor everywhere

- Send reports (A press/release, left stick full right, hat up) from the JSON and watch
  RetroArch's *Settings → Input → Port 1 Controls* react. Take screenshots with
  `screencap` (no `uiautomator`).
- Register `spike/hid-mouse-register.json` (Appendix A) and send a few `dx,dy` reports
  **while the Shield launcher is in the foreground**, then while RetroArch is in the
  foreground: the cursor must move in both. This is the requirement that kills inject
  mode; it must be seen to pass.
- Also record: `uname -r`, `ls /sys/bus/hid/drivers/` (does `sony`/`microsoft`/`pidff`
  exist? drives P6), and any `avc: denied` lines (`logcat -b all | grep avc`).
- **GO** if gamepad has `SOURCE_GAMEPAD` **and** cursor moves on the launcher.
  **NO-GO** → stop, write up in `docs/evidence/P1-NOGO.md`, and revisit the root cost
  sheet in the notes. Do not start Phase 2.

---

## Phase 2 — Backend abstraction (no behaviour change yet)

Goal: make the shell-UID process own a *backend choice*, and make the app *display*
it. `uinput` keeps working exactly as before on devices where it is allowed.

### P2.S1 Native backend interface

- New `app/src/main/cpp/backend.h`:

  ```cpp
  struct OutputBackend {
      virtual ~OutputBackend() = default;
      virtual const char* name() const = 0;          // "uinput" | "uhid"
      virtual bool probe() = 0;                       // can we open the device node?
      virtual bool create(const gamepad_profile&) = 0;// gamepad(+sidecars) or desktop pair
      virtual void sendFrame(int buttons, int lx,int ly,int rx,int ry,int lt,int rt,int hx,int hy) = 0;
      virtual void sendMouseFrame(int relX,int relY,int scrollY,int keys) = 0;
      virtual bool pollFF(int32_t* strong, int32_t* weak) = 0; // false = nothing pending
      virtual void destroy() = 0;
  };
  ```

- Move everything device-specific from `uinput_jni.cpp` into `uinput_backend.cpp`
  implementing it; keep `PROFILES[]`, `MOUSE_KEYS[]`, bit layouts in a shared
  `common.h` (the AIDL bit contract must stay identical — `XboxButtons`,
  `MouseTarget` bit positions).
- `uinput_jni.cpp` becomes a thin dispatcher holding `static OutputBackend* g_backend`.
- Add `uhid_backend.cpp` as a stub returning `probe()==false` for now.
- Update `CMakeLists.txt`.
- **Evidence:** builds; on a device where uinput works (or by inspection on the
  Shield: same `open denied` log) behaviour is unchanged. Diff shows pure move.

### P2.S2 Backend selection in the shell process

- New JNI: `selectBackend(int preferred)` returning an int enum
  `{0=NONE,1=UINPUT,2=UHID}`; order `uinput → uhid` by default, with a pref override
  (`auto | uinput | uhid`) so the Shield can be forced to `uhid` during debugging.
- `canOpen()` becomes "any backend probes OK"; log which.
- **Evidence:** logcat `backend: uinput denied (EACCES), uhid probe OK → UHID`.

### P2.S3 AIDL + Kotlin surface

- `IUInputService.aidl`: add `int getBackend();` and `String getBackendDetail();`
  (human string: e.g. `"/dev/uinput: Permission denied; /dev/uhid: ok"`). Bump
  `UserServiceArgs.version(1)` → `2` so a stale Shizuku process is replaced.
- `ControllerService.InjectionMode` gains `UHID`; `chooseInjectionMode()` treats
  `UINPUT` and `UHID` identically for routing (both go through `uinput.pushFrame`).
  Rename `UInputGamepad` → `VirtualGamepad` only if it stays a mechanical rename; otherwise
  leave the name and add a KDoc note.
- `Prefs`: backend preference (`auto` default).
- **Evidence:** `modeFlow` emits `UHID` on the Shield once P3 lands; for now `NONE →
  SHIZUKU_INJECT` still, with the new detail string in logcat.

### P2.S4 Surface the decision in the UI (notes item 3 & 8)

- MainActivity status card: show backend + reason:
  `Backend: uhid — /dev/uinput denied by SELinux, using /dev/uhid (full support)`, or
  `Backend: inject — only the focused app receives input`. Reuse existing strings
  where possible; add to `values/strings.xml` (and any translated `values-*/` present).
- Debug screen: raw `getBackendDetail()`.
- **Evidence:** screenshot of the card on the Shield.

---

## Phase 3 — uhid backend

### P3.S1 uhid plumbing

- `uhid_backend.cpp`: per device `{ int fd; }` for gamepad / mouse / kbd.
  `open("/dev/uhid", O_RDWR|O_CLOEXEC)`; `UHID_CREATE2` with `name`, `phys`, `uniq`
  (unique per device — use profile name + suffix), `bus = BUS_USB` (so the same
  `Vendor_xxxx_Product_yyyy.kl` files upstream relies on are matched; P1 recorded
  what BUS_BLUETOOTH picked — if that was better, use it), `vendor/product/version`
  from `gamepad_profile`, `rd_data/rd_size` = descriptor bytes, `country=0`.
- Write helper: `write(fd, &ev, sizeof(ev))` — the kernel accepts the full
  `struct uhid_event` size. Check `< 0` and log `strerror(errno)`.
- Reader thread (one per backend, `poll()` on all fds): drain `UHID_START`, `UHID_OPEN`,
  `UHID_CLOSE`, `UHID_STOP`; reply to `UHID_GET_REPORT` with `UHID_GET_REPORT_REPLY`
  (`err = -EIO`) and to `UHID_SET_REPORT` with `UHID_SET_REPORT_REPLY` so drivers
  never wait 5 s for us; stash `UHID_OUTPUT` payloads for P6.
- `destroy()`: `UHID_DESTROY` then `close`; keep upstream's 80 ms settle sleep
  (`destroy_devices()` comment explains why) — measure whether uhid needs it too.
- **Evidence:** unit-free: logcat shows `UHID_START` and `UHID_OPEN` received for all
  three fds after create; `getevent -pl` lists three new nodes.

### P3.S2 Gamepad descriptor + report packing

- Descriptor: Appendix A (`GAMEPAD_RD`). Report = 13 bytes:
  `[buttons lo][buttons hi][hat(4b)|pad(4b)][X lo][X hi][Y lo][Y hi][RX lo][RX hi][RY lo][RY hi][Z][RZ]`.
- Map `XboxButtons` bits → HID button indexes so `hid-input` yields the same `BTN_*`
  codes the uinput backend emitted:
  `A→1 B→2 X→4 Y→5 LB→7 RB→8 SELECT→11 START→12 MODE→13 THUMBL→14 THUMBR→15`
  (indexes 3, 6, 9, 10 exist in the descriptor but stay 0; 9/10 = `BTN_TL2/TR2`, unused
  because triggers are analog axes).
- Hat: `(hx,hy)` → 1..8 clockwise from up, 0 = centred (null state).
- Sticks: pass the int16 values through (upstream already inverts Y). Triggers 0..255.
- `sendFrame` = pack + `UHID_INPUT2`. No per-event writes, one report per frame.
- **Evidence:** `getevent -lt` on the uhid gamepad node while pressing each physical
  button shows the *same* `BTN_*`/`ABS_*` stream the uinput backend would have
  produced (compare against `uinput_jni.cpp` `bit_to_key[]`). RetroArch autoconfig /
  manual bind works for every input including L4/L5/R4/R5 mappings.

### P3.S3 Mouse sidecar

- Descriptor: Appendix A (`MOUSE_RD`) — 3 buttons, 16-bit X/Y, 8-bit wheel. Report =
  `[buttons][dx lo][dx hi][dy lo][dy hi][wheel]`. 16-bit deltas so no clamping of
  upstream's `relX/relY` ints is needed; clamp `scrollY` to ±127.
- Preserve upstream's rule: **only write a report when something changed** (see
  `sendMouseFrame` comment about IME focus and the 300 Hz cursor-keepalive problem).
- VID/PID `profile.pid + 0x100` like upstream; name `"Steam Controller Mouse"`.
- **Evidence:** right trackpad moves the cursor on the **launcher**, in the browser,
  and in RetroArch while a game runs, without switching profiles. Left pad scrolls.

### P3.S4 Keyboard sidecar (+ Desktop-mode full keyboard)

- Two application collections in one device: Keyboard page (0x07) as a 6-key array
  (boot-protocol layout, modifiers byte + 6 usages) and a Consumer page (0x0C)
  collection carrying: AC Back 0x0224, AC Home 0x0223, Menu 0x40, Menu Pick 0x41
  (→ `KEY_SELECT`), Volume Up 0xE9, Volume Down 0xEA, Play/Pause 0xCD. Use report ids
  (1 = keyboard, 2 = consumer).
- Map each `MOUSE_KEYS[]` entry to a usage. **Caveat:** Linux maps AC Home →
  `KEY_HOMEPAGE (172)` and Keyboard Home (0x4A) → `KEY_HOME (102)`; Android's
  `Generic.kl` maps 172 → `HOME` and 102 → `MOVE_HOME`. The uinput backend emits
  `KEY_HOME (102)`. Decide per key by checking what the user-visible Android keycode is
  (`adb shell getevent -l` + `dumpsys input` / `KeyEvent` log in DebugActivity) and
  match *behaviour*, not Linux code numbers. Document the table in
  `docs/evidence/P3-S4-keymap.md`.
- `full_alpha` (Desktop profile) is naturally satisfied by the boot-keyboard array
  (all A–Z/0–9 usages declared) — confirm Android classifies it `ALPHAKEY` and that the
  Leanback IME DPAD routing described in `create_keyboard_fd()` still works. For the
  gamepad-mode sidecar, *do not* declare the alphabet (upstream explains: it suppresses
  the on-screen keyboard system-wide) — use a minimal keyboard collection that only
  contains the arrow/Enter/Tab/Space/Esc/Backspace usages plus the consumer collection.
- **Evidence:** every `MouseTarget` key mapped to a back paddle produces the same
  Android `KeyEvent` keycode as upstream's uinput path (table with both columns).
  Desktop profile: soft keyboard navigation works in a text field on the Shield.

### P3.S5 Profile switching and lifecycle parity

- `switchProfile()` recreates the three uhid devices. Verify no leaked
  `/dev/input/event*` nodes after 20 rapid switches (`ls /dev/input | wc -l` stable).
- Verify `destroy()` on service unbind removes all nodes (`getevent -pl`).
- Verify the shell-UID process survives `UHID_CLOSE` (Android closes evdev nodes when
  no window wants them) — reports must keep flowing after the next `UHID_OPEN`.
- **Evidence:** the counts above + a 30 min play session without device loss.

### P3.S6 Flip the Shield to UHID for real

- Remove the pref override default if any was left as `uhid`; `auto` must pick uhid
  on the Shield by itself. Fresh install, fresh Shizuku, connect → status card says
  `Backend: uhid`. RetroArch menu navigable with dpad/stick, confirm/back buttons
  correct (P5 fixes defaults if not).
- **Evidence:** logcat `Using uhid virtual gamepad`; screenshot of status card; short
  video/photo optional.

---

## Phase 4 — Reliability (the stuff that cost the first night)

### P4.S1 GATT auto-reconnect (notes item 1; upstream README "Known limitations")

- In `BluetoothHidManager`, on `STATE_DISCONNECTED` (and on `status != GATT_SUCCESS`
  in `onConnectionStateChange`) schedule reconnect with backoff
  (1 s, 2 s, 4 s … cap 30 s) against the same bonded `BluetoothDevice`, forever while
  the service is running and the transport pref is BLUETOOTH.
- Also register a receiver for `BluetoothDevice.ACTION_ACL_CONNECTED` /
  `ACTION_ACL_DISCONNECTED` filtered to the bonded Steam Controller and trigger an
  immediate connect attempt on ACL up (controller woke in BT mode → link appears →
  we attach the GATT within a second).
- Handle the "GATT 133" flake: on status 133 do `close()` and retry after 500 ms.
- `ControllerService.onConnectionChange` currently only logs — make it drive state.
- Steam-button power-off followed by power-on must reconnect **with no user action**.
- **Evidence:** logcat of 5 power-cycles, each ending in `state=READY` and reports
  flowing, plus one 10 min idle with controller off then on.

### P4.S2 Honest connection status (notes item 2)

- Define *linked* = GATT READY **and** a state report (0x45) or battery report (0x43)
  received in the last N seconds (start with 3 s; the heartbeat runs at 800 ms).
  Expose as `linkFlow: StateFlow<LinkState>` (`DISCONNECTED / CONNECTING / LINKED /
  STALE`).
- MainActivity: green card only on `LINKED`; `STALE` → amber "link lost — tap to
  reconnect" (tap = immediate reconnect); never show "Connected" with battery `–`.
- Notification mirrors it.
- **Evidence:** pull the controller's battery (or power it off) → card flips to
  STALE within 5 s; reconnect → green.

### P4.S3 Wireless-mode guidance (notes item 4)

- When the bonded device is present but no GATT connect succeeds for 20 s, show a
  help sheet: "Controller may be in a different wireless mode. Hold `B + R1 + Steam`
  until the chime and a **blue** LED. White = puck slot, green = wired." Reuse
  `dialog_connection_help.xml`.
- Add the same text to README "Troubleshooting".
- **Evidence:** screenshot of the sheet triggered by putting the controller in puck mode.

### P4.S4 Boot handling (notes item 7)

- `RECEIVE_BOOT_COMPLETED` receiver → if the user enabled "start on boot" and a
  transport is configured, start `ControllerService` (foreground). Because Shizuku is
  dead after reboot on Android 9, the service will land in `NONE`/inject; the status
  card must then say exactly why: "Shizuku not running — after a reboot, run the
  Shizuku start command from a PC (see Help)".
- Help screen: the derived-libpath one-liner from the notes (no hash hardcoded).
- Optional (owner call): a `scripts/shield-shizuku-start.sh` helper for the workstation
  that does `adb connect`, derives the lib path, and starts Shizuku.
- **Evidence:** reboot Shield → notification appears → card shows the Shizuku message
  → run helper → card shows `Backend: uhid` without reopening the app.

---

## Phase 5 — RetroArch integration (notes items 5 & 6)

### P5.S1 Autoconfig profiles

- Ship `retroarch/autoconfig/android/` `.cfg` files matching each emulated profile's
  `input_device` name + `input_vendor_id`/`input_product_id` as Android reports them
  for our uhid devices (read from `dumpsys input` on the Shield after P3.S6).
  Retropad mapping: A/B/X/Y in Retropad orientation, Select = View, Start = Menu,
  L3/R3 = thumb clicks, L2/R2 = analog triggers.
- Install path: `/sdcard/RetroArch/autoconfig/android/` (verify `joypad_autoconfig_dir`
  in `retroarch.cfg`). Provide an in-app "Install RetroArch profiles" action that writes
  them via `runShellCommand` (shell UID can write `/sdcard`), or document `adb push`.
- **Evidence:** fresh RetroArch config → pad is auto-configured on connect (toast
  "… configured in port 1"), menu *confirm* and *back* work without manual binding.

### P5.S2 Back-paddle defaults

- Default mapping (new installs only; do not clobber existing prefs): `L4 → L3`,
  `R4 → R3`, `L5/R5` unmapped.
- Preset "RetroArch save/load state": `L5 → save state`, `R5 → load state`, implemented
  as sidecar keyboard keys chosen to match RetroArch's hotkeys on Android
  (set the corresponding `input_save_state`/`input_load_state` in the autoconfig or
  document the one-time hotkey bind). The notes mention button ids `106/107`; confirm
  against the installed RetroArch version's `retroarch.cfg` before relying on them.
- **Evidence:** in a running core, L5 saves and R5 loads (on-screen RetroArch toast).

### P5.S3 `config_save_on_exit` guard

- Document in README that RetroArch reverts edits unless `config_save_on_exit "false"`
  and it is stopped before editing. Provide `scripts/retroarch-cfg.sh pull|push`.
- **Evidence:** script round-trips a cfg with a visible change.

---

## Phase 6 — Rumble over uhid (stretch; only if P1 recorded a usable HID FF driver)

- Option A: if `/sys/bus/hid/drivers/sony` exists, emulate a DualShock 4
  (`054c:05c4`, USB report 0x01, 64 bytes) so `hid-sony` binds and translates FF into
  output report 0x05 → arrives as `UHID_OUTPUT` → forward to `pollFF`. Battery can also
  be reported via that path. Only for the DS4 profile.
- Option B: PID (Physical Interface Device) class descriptor if `hid-pidff` is
  compiled in (rare on Android kernels).
- Otherwise: show "Rumble unavailable with uhid backend" in Calibration and hide the
  test button.
- **Evidence:** RetroArch/a rumble-capable game vibrates the SC2026 via the existing
  `0x8F` haptic path.

---

## Phase 7 — Release & upstreaming

### P7.S1 Versioning, signing, changelog

- `versionCode 3`, `versionName "2.1-shield"`; `CHANGELOG.md`; README section
  "Shield TV / uhid backend"; update the architecture block diagram in README.
- Release signing via `keystore.properties`/env (upstream `RELEASING.md`).
- **Evidence:** signed release APK installs over the debug build after uninstall.

### P7.S2 CI

- GitHub Actions: `assembleDebug` on push (JDK 17, SDK 35, NDK). No device tests.
- **Evidence:** green run on `shield-uhid`.

### P7.S3 Upstream PRs (owner decision)

- Split into independent PRs against SonicDX12: (1) backend abstraction + uhid,
  (2) GATT auto-reconnect, (3) honest link status, (4) RetroArch autoconfig.
  Upstream already lists "USB / BT auto-reconnect" on its roadmap.

---

## Acceptance matrix (final)

| # | Scenario | Pass condition |
| --- | --- | --- |
| 1 | Fresh install, Shizuku running, controller in BT mode | Status card `Backend: uhid`, green, battery % shown |
| 2 | RetroArch, any core | All 4 face buttons, both sticks, triggers, bumpers, dpad, L3/R3 via paddles |
| 3 | Game running, press Home, use launcher | Right trackpad moves cursor, left scrolls, click works — no profile change |
| 4 | Controller powered off then on | Reconnects automatically < 10 s, no UI interaction |
| 5 | Controller left in puck mode | App shows wireless-mode guidance within 20 s |
| 6 | Shield reboot | Service starts, tells user to start Shizuku; after Shizuku, uhid backend resumes |
| 7 | RetroArch menu on first launch | Confirm/back correct via shipped autoconfig |
| 8 | 20 profile switches | No leaked `/dev/input` nodes, no `avc: denied` |

---

## Appendix A — Spike artefacts (`spike/`)

Files already committed alongside this plan; workers run them in P1.

- `spike/hid-gamepad-register.json` — `hid` tool script: register gamepad, wait,
  press A, release, stick right, centre, hat up, centre.
- `spike/hid-mouse-register.json` — register a mouse and move the cursor in a square.
- `spike/README.md` — how to run them and what to look for.

### Gamepad report descriptor (`GAMEPAD_RD`, 13-byte report)

```
05 01        Usage Page (Generic Desktop)
09 05        Usage (Game Pad)
A1 01        Collection (Application)
A1 00          Collection (Physical)
05 09            Usage Page (Button)
19 01            Usage Minimum (Button 1)
29 0F            Usage Maximum (Button 15)
15 00            Logical Minimum (0)
25 01            Logical Maximum (1)
75 01            Report Size (1)
95 0F            Report Count (15)
81 02            Input (Data,Var,Abs)          ; bytes 0-1 (15 bits)
75 01  95 01  81 03                            ; 1 bit pad
05 01            Usage Page (Generic Desktop)
09 39            Usage (Hat switch)
15 01            Logical Minimum (1)
25 08            Logical Maximum (8)
35 00            Physical Minimum (0)
46 3B 01         Physical Maximum (315)
65 14            Unit (Degrees)
75 04            Report Size (4)
95 01            Report Count (1)
81 42            Input (Data,Var,Abs,Null)      ; byte 2 low nibble
75 04  95 01  81 03                            ; 4 bit pad
65 00            Unit (None)
09 30  09 31  09 33  09 34                     ; X, Y, Rx, Ry
16 00 80         Logical Minimum (-32768)
26 FF 7F         Logical Maximum (32767)
75 10            Report Size (16)
95 04            Report Count (4)
81 02            Input (Data,Var,Abs)          ; bytes 3-10
09 32  09 35                                   ; Z, Rz  (triggers)
15 00            Logical Minimum (0)
26 FF 00         Logical Maximum (255)
75 08            Report Size (8)
95 02            Report Count (2)
81 02            Input (Data,Var,Abs)          ; bytes 11-12
C0             End Collection
C0           End Collection
```

Button index → Linux code (from `hid-input.c`, application = Game Pad):
`1 BTN_A · 2 BTN_B · 3 BTN_C(skip) · 4 BTN_X · 5 BTN_Y · 6 BTN_Z(skip) · 7 BTN_TL ·
8 BTN_TR · 9 BTN_TL2 · 10 BTN_TR2 · 11 BTN_SELECT · 12 BTN_START · 13 BTN_MODE ·
14 BTN_THUMBL · 15 BTN_THUMBR`.

### Mouse report descriptor (`MOUSE_RD`, 6-byte report)

```
05 01  09 02  A1 01  09 01  A1 00
05 09  19 01  29 03  15 00  25 01  95 03  75 01  81 02   ; 3 buttons
95 01  75 05  81 03                                      ; pad
05 01  09 30  09 31  16 01 80  26 FF 7F  75 10  95 02  81 06   ; X,Y int16 rel
09 38  15 81  25 7F  75 08  95 01  81 06                        ; wheel int8 rel
C0  C0
```

## Appendix B — Upstream files touched, by phase

| Phase | Files |
| --- | --- |
| P2 | `cpp/uinput_jni.cpp` (split) → `cpp/backend.h`, `cpp/common.h`, `cpp/uinput_backend.cpp`, `cpp/uhid_backend.cpp`, `cpp/CMakeLists.txt`; `aidl/IUInputService.aidl`; `uinput/UInputService.kt`, `uinput/UInputNative.kt`, `uinput/UInputGamepad.kt`; `service/ControllerService.kt`; `Prefs.kt`; `MainActivity.kt`, `DebugActivity.kt`, `res/values/strings.xml` |
| P3 | `cpp/uhid_backend.cpp`, `cpp/hid_descriptors.h` |
| P4 | `bt/BluetoothHidManager.kt`, `service/ControllerService.kt`, `MainActivity.kt`, new `service/BootReceiver.kt`, `AndroidManifest.xml`, `res/layout/dialog_connection_help.xml` |
| P5 | new `retroarch/autoconfig/android/*.cfg`, `input/ButtonMapping.kt` defaults, `scripts/retroarch-cfg.sh`, README |
| P6 | `cpp/uhid_backend.cpp` (UHID_OUTPUT → FF), `CalibrationActivity.kt` |
| P7 | `app/build.gradle.kts`, `CHANGELOG.md`, README, `.github/workflows/build.yml` |

---

## Status (2026-09-20)

### Done and verified

- **P0.S2 toolchain.** JDK 17 at `~/.local/jdk17` (Adoptium, no sudo), SDK at
  `~/Android/Sdk` (platform 35, build-tools 35.0.0, NDK 26.1.10909125, cmake 3.22.1),
  `org.gradle.java.home` pinned in `~/.gradle/gradle.properties`, `sdk.dir` in the
  gitignored `local.properties`. `./gradlew :app:assembleDebug` is green.
- **P2 + P3** (code only) — committed as `5a8695d` on `shield-uhid`. Backend seam
  (`output_backend.h`), the uhid backend, the AIDL change, and the Kotlin/UI wiring.
  The commit message is the design record.
- `compile_commands.json` is symlinked at the repo root (gitignored) so clangd and the
  lsp/diagnostic tools resolve the NDK sysroot. Without it every C++ file is reported as
  `android/log.h` / `jni.h` not found — a bare-host-clang artifact, not a code error.
  Regenerate via any native build: `app/.cxx/Debug/*/arm64-v8a/compile_commands.json`.

### Not done — and why

- **P1 (the spike) is still the gate and has not run.** Nothing here is device-verified:
  the uhid backend compiles, is wired, and its descriptors and report packing are
  written against the kernel's documented behaviour — but **not one report has ever been
  written to `/dev/uhid`**. Until P1.S3 passes, treat P2/P3 as unproven on hardware.
- **P0.S3 is blocked on a human.** `adb connect $SHIELD` returns `unauthorized`: the
  device needs the key prompt accepted on screen, and only the TV remote can dismiss it.
  Nothing on the device has been touched yet.

### Known wart

pi-lens' ktlint auto-fix applies the `ktlint_official` style on every file write and
reformats whole files, while upstream uses IntelliJ/Android-Studio style. That added
cosmetic churn to the seven Kotlin files in `5a8695d`. A repo `.editorconfig` now
*declares* the intended style for real ktlint/IDE/CI runs, but the agent-side auto-fix
does not honour it. Before opening the upstream PRs (P7.S3), either run `ktlintFormat`
once in a dedicated whitespace-only commit, or review those PRs with `git diff -w`.
