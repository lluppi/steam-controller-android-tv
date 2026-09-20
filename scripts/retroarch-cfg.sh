#!/usr/bin/env bash
# Pull or push RetroArch's config, with the guard that trips everyone up.
#
# RetroArch rewrites retroarch.cfg when it exits, so an edit made while it is running — or with
# config_save_on_exit left on — silently disappears. This script stops RetroArch, edits, and
# verifies, in that order.
#
#   scripts/retroarch-cfg.sh pull            copy the config to ./retroarch.cfg
#   scripts/retroarch-cfg.sh push            push ./retroarch.cfg back to the device
#   scripts/retroarch-cfg.sh guard           stop RetroArch and force config_save_on_exit=false
#   scripts/retroarch-cfg.sh show            print the interesting settings
#
# Device address: pass it as $SHIELD or set ANDROID_SERIAL, e.g.
#   SHIELD=192.168.1.50:5555 scripts/retroarch-cfg.sh show

set -euo pipefail

PKG=com.retroarch.aarch64
CFG=/sdcard/Android/data/$PKG/files/retroarch.cfg
ADB=(adb)
[ -n "${ANDROID_SERIAL:-}" ] && ADB=(adb -s "$ANDROID_SERIAL")
[ -n "${SHIELD:-}" ] && ADB=(adb -s "$SHIELD")

# Two settings that matter for a headless edit: not saving on exit, and where per-device
# autoconfigs are read from. Note the default dir lives inside the app's private data, so the
# shell UID cannot write it.
show() {
	"${ADB[@]}" shell "grep -nE 'config_save_on_exit|joypad_autoconfig_dir|input_joypad_driver' $CFG"
}

guard() {
	echo "--- stopping RetroArch (otherwise it rewrites the config on exit)"
	"${ADB[@]}" shell "am force-stop $PKG"
	sleep 2
	echo "--- disabling config_save_on_exit"
	"${ADB[@]}" shell "sed -i 's/^config_save_on_exit = .*/config_save_on_exit = \"false\"/' $CFG"
	show
}

pull() {
	guard
	"${ADB[@]}" pull "$CFG" ./retroarch.cfg
	echo "wrote ./retroarch.cfg"
}

push() {
	[ -f ./retroarch.cfg ] || {
		echo "no ./retroarch.cfg to push — run '$0 pull' first" >&2
		exit 1
	}
	guard
	"${ADB[@]}" push ./retroarch.cfg "$CFG"
	show
}

case "${1:-}" in
pull) pull ;;
push) push ;;
guard) guard ;;
show) show ;;
*)
	sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
	exit 1
	;;
esac
