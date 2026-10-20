#!/usr/bin/env bash
# Put the TV back the way build-and-install.sh found it: the old launcher as Home, its Input key
# service, the old screensaver. The new home stays installed but unused; pass UNINSTALL=1 to remove it.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.25 $0}"
PY=../tools/venv/bin/python
TV="$PY -I ../tools/tv.py"
PKG=com.barontv.home
# Without a saved state, fall back to what the TV had before the new home (CLAUDE.md, "State").
home=com.gathin.tvhome/.MainActivity
a11y=com.gathin.tvhome/com.gathin.tvhome.InputKeyService
saver=com.gathin.tvhome/com.gathin.tvhome.ClockDream
saver_on=1
[ -f .tv-state ] && . ./.tv-state
case "$home" in */*) ;; *) home=com.gathin.tvhome/.MainActivity ;; esac
printf '%s\n' \
  "cmd package set-home-activity --user 0 $home" \
  "settings put secure enabled_accessibility_services $a11y" \
  "settings put secure screensaver_components $saver" \
  "settings put secure screensaver_enabled $saver_on" \
  "cmd notification disallow_listener $PKG/$PKG.media.NowPlayingListener" \
  | $TV --batch
[ "${UNINSTALL:-0}" = 1 ] && $TV "pm uninstall $PKG"
$TV "input keyevent KEYCODE_HOME; cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -n 1"
echo "Back to the old home. (home/.tv-state kept; delete it before a fresh install on another TV.)"
