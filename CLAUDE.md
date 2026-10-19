# Baron TV (private fork of tv-vibehack)

Private working copy of [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack) (MIT) for one
TV: a Baron 32" "Cloud TV". Goal: strip the CloudWalker and CVTE ads and bloat, replace the home screen
with the tv-vibehack launcher, then post a before/after video.

Upstream's `README.md` and `PROMPT.md` describe the general method. This file is the state of this TV
and what's left to do. Based on upstream commit `31eb392`; upstream has moved on since (`042fd69` on
2026-10-19). To pull it in later: `git remote add upstream https://github.com/Gathin23/tv-vibehack.git`.

## The TV

| | |
|---|---|
| Model | Baron 32" HD LED "Cloud TV" (80 cm), 1366x768 panel |
| OS | CloudWalker Cloud TV OS on AOSP Android 9 (API 28), `userdebug` build with dev-keys. No Play Store, no Chromecast |
| Board | CVTE TP.SK518T.PB816, MediaTek (`mt5862_512m`), Mali-G31 MP2, firmware 20240913_213710 |
| RAM | 512 MB, 437 MB usable. Android reports memory as "critical" on the stock home screen |
| Screen | UI 1280x720 at 160 dpi |
| Network | Wi-Fi MAC xx:xx:xx:xx:xx:xx, last IP 192.168.1.20 (DHCP, may change) |
| Remote | No number keys: Power, Mute, D-pad, Home, Back, Menu (three lines), Vol -/+, Input, Play/Pause, cursor, Netflix, Prime Video, YouTube |

If the IP changed: `dns-sd -B _cloudwalker._tcp` (instance `CloudTV_<ethernet MAC>`, host `Android.local`),
or `arp -an | grep xx:xx:xx:xx:xx:xx`, or the IP row in the factory menu.

The firmware's default system lock PIN (`ro.CVT_DEF_SYS_LOCK_PASSWORD`) is `1234`, in case a PIN prompt appears.

## Getting ADB (the TV has no Developer options)

1. CloudWalker's phone-remote server (`tv.cloudwalker.inputserver`) listens on port 5511 with no auth.
   `GET /input/?keycode=N` injects an Android key code. `/apps` opens the All Apps screen and
   `/play?packageName=` launches an app and always answers OK: they are actions, not reads.
2. Open the CVTE factory menu with Menu, then 1 1 4 7:
   ```bash
   for k in 82 8 8 11 14; do curl -s "http://192.168.1.20:5511/input/?keycode=$k"; echo; done
   ```
3. Go to DEBUG (7th row), then set ADB ENABLE (4th row) to ON with Right. Port 5555 opens at once.
   Stay out of SHIPPING INIT (wipes the TV), AGING MODE (looping test screen), BOARD INIT,
   PANEL SETTING and SW UPGRADE. Over the API: from BACKLIGHT VALUE, Down x3 (keycode 20) then Right (22).
4. ADB is unauthenticated (`ro.adb.secure=0`): any computer on the Wi-Fi gets a shell. Set ADB ENABLE
   back to OFF when the work is done.

Never disable `com.cvte.fac.menu` or `tv.cloudwalker.inputserver`: together they are the only way back
into ADB.

## Setup on a new Mac

```bash
brew install uv openjdk@17 && brew install --cask android-commandlinetools
JAVA_HOME=/opt/homebrew/opt/openjdk@17 sdkmanager "platforms;android-34" "build-tools;34.0.0"
uv venv tools/venv && uv pip install --python tools/venv/bin/python -r tools/requirements.txt
export TV_HOST=192.168.1.20
tools/venv/bin/python -I tools/tv.py --probe   # expect SMART_TV, mt5862_512m, Android 9
```

The Mac must be on the same Wi-Fi as the TV, and macOS may ask to let the terminal reach the local
network: allow it. `launcher/build-and-install.sh` finds the Homebrew JDK and SDK by itself.

## State on 2026-10-19

- **The TV is back to stock**, except ADB ENABLE is still ON. The debloat list was applied once
  (12 packages), then reverted so the user could record the "before" video. The recording is done.
- Backup: `backup/20261019-151014/` (package lists, getprop, settings, Home and keyboard, meminfo) and
  `backup/dumpsys/` (per-package dumps, including the TV service's intent filters). Committed on
  purpose, since this repo is private. `tools/backup.sh` takes a fresh one.
- Debloat list: `examples/baron-cloudtv-32/debloat.txt`, with reasons per package and what to leave alone.
  The three stage-B lines at the bottom stay commented until the new launcher owns Home.
- User's answers: doesn't use Movie Box, Browser or Kindlink (all go); both updaters off; keep the
  Cloud TV App Store (only way to install apps), remove Stark Store. **Dock order not confirmed yet.**
- Launcher adapted and built (242 KB) but **not installed**. Changes from upstream:
  - `config.xml`: dock YouTube, Prime Video, Spotify, Cloud TV App Store; CloudWalker helper apps
    hidden; no Live TV (no tuner, inputs are AV and HDMI 1-2).
  - `ui_density_dpi` = 213 (new). At 1280x720 and 160 dpi the launcher would draw a quarter too small;
    213 puts it on Android TV's 960x540 dp canvas. Applied in `MainActivity.attachBaseContext` and `ClockDream`.
  - `input_tune_action` and `input_tune_category` (new). CVTE's TV app ignores Android's standard
    passthrough VIEW intent, so the Inputs menu sends `com.cvte.intent.ACTION_TUNE_SOURCE_OR_CHANNEL`
    (category `com.cvte.intent.CATEGORY_TUNE_SOURCE_OR_CHANNEL`) by name to
    `com.cvte.tv.api.impl/com.cvte.tv.api.tif.TifReceiver`, with the passthrough channel URI as data.
    **Unverified on the TV.** Fallback: `com.cvte.intent.SHOW_INPUT` opens CVTE's own source menu
    (`com.cvte.tv.setting/.extra.TvExtraActivity`).

## Next steps

1. Check ADB: `tools/venv/bin/python -I tools/tv.py --probe`. If refused, redo "Getting ADB".
2. Test the input switch before installing (the TV will change input):
   ```bash
   tools/venv/bin/python -I tools/tv.py "am broadcast -n com.cvte.tv.api.impl/com.cvte.tv.api.tif.TifReceiver -a com.cvte.intent.ACTION_TUNE_SOURCE_OR_CHANNEL -c com.cvte.intent.CATEGORY_TUNE_SOURCE_OR_CHANNEL -d content://android.media.tv/passthrough/com.mediatek.tvinput%2F.hdmi.HDMIInputService%2FHW4"
   tools/venv/bin/python -I tools/tv.py "logcat -d | grep -iE 'TifReceiver|changeSourceOrChannel' | tail"
   ```
   HW4 is HDMI 1, HW5 is HDMI 2, `com.mediatek.tvinput/.composite.CompositeInputService/HW3` is AV.
   If it doesn't switch, try the URI as an extra; failing that, make the Inputs pill fire SHOW_INPUT.
3. Ask the user for the dock order, then
   `STOCK_LAUNCHER=tv.cloudwalker.launcher.com TV_HOST=192.168.1.20 launcher/build-and-install.sh`.
   Check it with screenshots: `tools/tv.py 'screencap -p /sdcard/s.png'`, then `tools/tv.py --pull /sdcard/s.png s.png`.
4. Uncomment the stage-B lines (apiservice, skin, apps) and run
   `TV_HOST=192.168.1.20 tools/disable.sh examples/baron-cloudtv-32/debloat.txt`.
5. Test Home, Back, the remote's Input key, the Settings and Wi-Fi pills, each app, and a reboot (Home
   must come back to the new launcher). Compare `dumpsys meminfo` with the backup.
6. Optional: the Clock screensaver (see README); stop Spotify's boot starter if RAM is still tight.
7. Set ADB ENABLE back to OFF in the factory menu.

Undo everything: `TV_HOST=192.168.1.20 tools/restore.sh examples/baron-cloudtv-32/debloat.txt tv.cloudwalker.launcher.com`.

## Working rules

- Work on the real TV and verify each change with key injection and a screenshot. Ask the user before
  disabling anything that isn't already in the list, and before anything that changes what's on screen
  while they might be recording.
- One `pm` command per shell call (`tools/tv.py --batch` does this).
- The user wants short replies, no em dashes anywhere (code and comments included), an explicit yes
  before every push, and no AI attribution lines in commits.
