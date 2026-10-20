#!/usr/bin/env bash
# Build the new home screen (com.barontv.home) and make it the TV's home. The old launcher
# (com.gathin.tvhome) stays installed as the fallback: home/rollback.sh switches everything back.
# What this changes on the TV, after saving the old values to home/.tv-state (rollback reads them):
#   the default Home, the Input key's accessibility service (only one may answer that key; it also
#   reads what's playing), the screensaver setting (unused: this firmware runs no dreams), location
#   (Wi-Fi scans) and microphone (music beats). Notification access is asked for too, but low-RAM
#   Android ignores it.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.25 $0}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain 2>&1 | grep -v "SDK XML" || true
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "ERROR: the build made no APK" >&2; exit 1; }
PY=../tools/venv/bin/python
TV="$PY -I ../tools/tv.py"
PKG=com.barontv.home
OLD=com.gathin.tvhome

# Save what's there now, once (a reinstall keeps the first, real, old values).
if [ ! -f .tv-state ]; then
  {
    echo "home=$($TV 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | tail -n 1 | tr -d '\r')"
    echo "a11y=$($TV 'settings get secure enabled_accessibility_services' | tail -n 1 | tr -d '\r')"
    echo "saver=$($TV 'settings get secure screensaver_components' | tail -n 1 | tr -d '\r')"
    echo "saver_on=$($TV 'settings get secure screensaver_enabled' | tail -n 1 | tr -d '\r')"
  } > .tv-state
  echo "Saved the TV's current settings to home/.tv-state:"; cat .tv-state
fi

"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/baronhome.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/baronhome.apk" \
  "rm -f /data/local/tmp/baronhome.apk" \
  "pm grant $PKG android.permission.ACCESS_FINE_LOCATION" \
  "pm grant $PKG android.permission.RECORD_AUDIO" \
  "cmd notification allow_listener $PKG/$PKG.media.NowPlayingListener" \
  "cmd package compile -m speed -f $PKG" \
  "cmd package set-home-activity --user 0 $PKG/.HomeActivity" \
  | $TV --batch
# The DVD screensaver (SET_SAVER=0 keeps the TV's current one, e.g. while the dream isn't built yet).
if [ "${SET_SAVER:-1}" = 1 ]; then
  $TV "settings put secure screensaver_components $PKG/$PKG.saver.DvdDream; settings put secure screensaver_enabled 1; echo screensaver: DvdDream"
fi
# Android 9 only gives Wi-Fi scan results and the network's name with location on (the TV has no GPS,
# so nothing is ever located): the Wi-Fi view needs it.
$TV "settings put secure location_providers_allowed +network; settings put secure location_providers_allowed +gps; echo location: \$(settings get secure location_providers_allowed)"

# The Input key (and now playing): this app's service in place of the old launcher's, every other service
# left as it was. It's taken off and put back, because Android keeps the service's old settings across a
# reinstall until it's enabled afresh (it would miss the notification events now playing needs).
$TV "s=\$(settings get secure enabled_accessibility_services | tr ':' '\n' | grep -v -e '^$OLD/' -e '^$PKG/' -e '^null\$' | tr '\n' ':' | sed 's/:\$//'); settings put secure enabled_accessibility_services \"\${s:-null}\"; sleep 1; settings put secure enabled_accessibility_services \"\${s:+\$s:}$PKG/$PKG.input.InputKeyService\"; settings put secure accessibility_enabled 1; echo Input key: \$(settings get secure enabled_accessibility_services)"

$TV "am start -W -n $PKG/.HomeActivity | grep -E '^(Status|TotalTime|Error)'"
HOME_NOW=$($TV 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | tail -n 1 | tr -d '\r')
case "$HOME_NOW" in
  $PKG/*) echo "Home is now $HOME_NOW. Undo with: TV_HOST=$TV_HOST home/rollback.sh" ;;
  *) echo "ERROR: Home resolves to ${HOME_NOW:-nothing}, not the new home. Run home/rollback.sh." >&2; exit 1 ;;
esac
