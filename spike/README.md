# Phase 1 spike — uhid from the shell UID, no compilation

Scripts for AOSP's `hid` shell tool (`frameworks/base/cmds/hid`, present on Android 8+).
They register a virtual HID device through `/dev/uhid` **as uid 2000 (shell)** — the same
uid the app's Shizuku user service runs as — and then stream a few reports.

The JSON is deliberately "lenient JSON" (unquoted hex literals), matching the tool's own
README; the tool uses `JsonReader.setLenient(true)` + `Integer.decode`.
On Android 9 the `bus` field may be ignored (the P-era tool hardcodes `BUS_BLUETOOTH`).
If the parser rejects it, delete the `bus` line.

## Run

```sh
adb connect $SHIELD
adb push spike/hid-gamepad-register.json /data/local/tmp/
adb push spike/hid-mouse-register.json  /data/local/tmp/
# terminal 1
adb logcat -s EventHub InputReader hid
# terminal 2 (device stays registered for 60 s after the last report)
adb shell hid /data/local/tmp/hid-gamepad-register.json
```

**One adb connection at a time** — use `adb shell` sessions from the same host, not two hosts.

## Gamepad script timeline

| t (s) | report | expect |
|---|---|---|
| 0 | register `045e:028e` "Steam Controller (uhid spike)" | logcat `EventHub: New device`, `InputReader: Device added … sources=` containing gamepad/joystick |
| 3.0 | button 1 down → `BTN_A` | RetroArch Input settings: A lights |
| 3.5 | all up | |
| 4.0 | X = +32767 | left stick full right |
| 5.0 | centre | |
| 5.5 | hat = 1 (up) → `ABS_HAT0Y -1` | dpad up |
| 6.0 | centre | |
| 6.5 | Z = 255 → `ABS_Z` | left trigger full |
| 7.5 | centre | |
| 67.5 | script ends, device destroyed | `EventHub: Removed device` |

While registered, in another shell:

```sh
adb shell getevent -pl                      # find the node; check KEY:/ABS: lists
adb shell dumpsys input | grep -B2 -A30 'uhid spike'
adb shell 'logcat -b all -d | grep avc'     # SELinux denials — expect none
adb shell 'uname -r; ls /sys/bus/hid/drivers/'   # for Phase 6 (rumble) planning
```

Fail conditions to look for: `BTN_C`/`BTN_Z` in the KEY list (button numbering drift),
missing `ABS_HAT0X/Y` (hat descriptor mis-parsed), `sources` lacking GAMEPAD.

## Mouse script

Draws a 200 px square and scrolls down/up 3 ticks. Run it **with the Shield launcher in
the foreground**, then again with RetroArch in the foreground: the cursor must move in
both. That is the property inject mode cannot provide.

## GO / NO-GO

GO = gamepad has `SOURCE_GAMEPAD` **and** the mouse moves the cursor on the launcher.
Record results in `docs/evidence/P1-S3.md` (fingerprint + kernel only — no LAN address).
