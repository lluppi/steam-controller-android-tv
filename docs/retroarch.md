# RetroArch integration notes

Facts established on the Shield (RetroArch for Android 1.22.2, `com.retroarch.aarch64`), for
whoever picks up PLAN P5.S1.

## The pad should already be auto-detected

RetroArch matches autoconfig profiles on device **name + vendor id + product id**. This app's
`XBOX_360` profile deliberately advertises the real 360 identity (`045e:028e`,
`"Microsoft X-Box 360 pad"`), and upstream's own profile in
[libretro/retroarch-joypad-autoconfig](https://github.com/libretro/retroarch-joypad-autoconfig)
matches exactly:

```text
input_device = "Microsoft X-Box 360 pad"
input_vendor_id = "1118"      # 0x045E
input_product_id = "654"      # 0x028E
input_b_btn = "96"            # AKEYCODE_BUTTON_A -> RetroPad B (RetroArch applies the swap)
input_a_btn = "97"
input_x_btn = "100" / input_y_btn = "99"
input_select_btn = "109" / input_start_btn = "108"
input_l_btn = "102" / input_r_btn = "103"
input_l2_axis = "+6" / input_r2_axis = "+7"
input_l3_btn = "106" / input_r3_btn = "107"
input_up_btn = "h0up" ... "h0right"      # d-pad arrives on the hat, which is what we emit
input_l_x_plus_axis = "+0" ... input_r_y_minus_axis = "-3"
```

So **shipping another autoconfig risks overriding a correct one.** What this fork needs to
guarantee is that the profile is *present* on the device, not that a new one is written.

## Where profiles live, and why the app cannot install them

```text
joypad_autoconfig_dir = "/data/user/0/com.retroarch.aarch64/autoconfig"
```

That is RetroArch's **private data directory**, so neither the app (shell UID) nor adb can write
into it. The bundled APK does not carry autoconfigs either (checked: zero entries in
`base.apk`), so on a fresh install they arrive via RetroArch's own online updater
(*Online Updater → Update Autoconfig Profiles*) or not at all.

Consequences:

- An in-app "install autoconfigs" action cannot work against the default path. It would have to
  redirect `joypad_autoconfig_dir` to somewhere the shell UID can write, which changes a user's
  RetroArch setup — do not do that silently.
- `scripts/retroarch-cfg.sh` exists for that: `guard` stops RetroArch and forces
  `config_save_on_exit = "false"` (already the case on the Shield), `pull`/`push` round-trip the
  config, `show` prints the settings that matter.

## The one thing that may genuinely need a custom profile

The upstream 360 profile's axis numbers assume the real xpad layout. Our uhid descriptor
declares its axes in the order `X, Y, Rx, Ry, Z, Rz` (then the hat), whereas the upstream
profile binds `l_x/l_y = +0/-0/+1/-1` and `r_x/r_y = +2/-2/+3/-3` with the triggers at `+6/+7`.
If RetroArch's Android driver enumerates axes in declaration order, then on our device indices
2/3 are `Rx/Ry` (fine) but **4/5 are the triggers and 6/7 are the hat** — so the triggers would
land on nothing and the hat would be bound as triggers.

**This is unverified and must not be "fixed" blind**: it needs the controller in hand to see
whether the triggers work and whether the d-pad both navigates the menu and does not trigger
L2/R2. If they are wrong, the fix is a small profile in `retroarch/autoconfig/android/` with the
axis lines corrected for this descriptor, installed via `scripts/retroarch-cfg.sh` plus a
`joypad_autoconfig_dir` pointed at a writable path.

## Practical checklist for the verification pass

1. Make sure the pad is detected at all: *Settings → Input → Port 1 Controls → Device* should
   read `Microsoft X-Box 360 pad` (not `Disabled`).
2. Menu navigation: d-pad up/down/left/right, and confirm with A. If the *select* button is
   wrong the emulator's own menu becomes unreachable — that is the failure the plan wants to
   prevent, and it is why the A/B swap in the upstream profile matters.
3. Triggers: pull L2/R2 and watch the *Settings → Input → Port 1 Controls* bindings page.
4. Save/load state: the back paddles default to `L3`/`R3`, which RetroArch sees as keycodes
   106/107 — bind those to save/load state if they are not already.
5. Edit `retroarch.cfg` only via `scripts/retroarch-cfg.sh` (it stops RetroArch and disables
   `config_save_on_exit` first), or the edit will be silently reverted.
