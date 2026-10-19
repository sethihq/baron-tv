# Baron TV (private fork of tv-vibehack)

Private working copy of [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack) (MIT) for one
TV: a Baron 32" "Cloud TV". Goal: strip the CloudWalker and CVTE ads and bloat, replace the home screen
with the tv-vibehack launcher, then post a before/after video.

Upstream's `README.md` and `PROMPT.md` describe the general method. This file is the state of this TV
and what's left to do. Upstream is now called cleanyourtv; v2.1.0 (`042fd69`) was merged in on
2026-10-19. To pull newer upstream work: `git remote add upstream https://github.com/Gathin23/tv-vibehack.git`,
then `git fetch upstream && git merge upstream/main`.

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
  Cloud TV App Store (only way to install apps), remove Stark Store. Dock order confirmed: YouTube,
  Prime Video, Spotify, Cloud TV App Store (as in `config.xml`). Netflix isn't installed, though the
  remote has a Netflix key.
- Work moved to a second Mac (192.168.1.9) the same day. It is set up as in "Setup on a new Mac"
  (SDK in `/opt/homebrew/share/android-commandlinetools`) and ADB answers from it. Checked from there:
  nothing disabled, Home is still the stock launcher.
- Upstream v2.1.0 is merged into `main` with the Baron changes below kept. The launcher builds
  (249 KB) but is **not installed**. Changes from upstream:
  - `config.xml`: dock YouTube, Prime Video, Spotify, Cloud TV App Store; CloudWalker helper apps
    hidden; no Live TV (no tuner, inputs are AV and HDMI 1-2).
  - `ui_density_dpi` = 213 (new). At 1280x720 and 160 dpi the launcher would draw a quarter too small;
    213 puts it on Android TV's 960x540 dp canvas. Applied in `MainActivity.attachBaseContext` and `ClockDream`.
  - `input_action` (upstream's key, extended), `input_action_category`, `input_action_extra` and
    `input_action_repeat` (new). Nothing on the TV answers Android's standard passthrough VIEW intent, so
    the Inputs menu sends `com.cvte.intent.ACTION_TUNE_SOURCE_OR_CHANNEL` (category
    `com.cvte.intent.CATEGORY_TUNE_SOURCE_OR_CHANNEL`) by name to each receiver of it, here
    `com.cvte.tv.api.impl/com.cvte.tv.api.tif.TifReceiver`. Read from its bytecode: it ignores the URI and
    reads an int extra `sourceId`, the number after "HW" in the input ID (3 AV, 4 HDMI 1, 5 HDMI 2). It
    opens the single-instance `TvSupportActivity`, which only switches in `onNewIntent`, so from Home
    (a cold start) the broadcast must arrive twice: `input_action_extra` = `sourceId`,
    `input_action_repeat` = 2. Verified by hand with `am broadcast` for all three inputs (HAL log
    `startMainVideo(main, source_id = N)`); not yet through the launcher. Pressing Home closes the player.
  - All of these can also come from a theme's `config.json`. Don't push `themes/default`: it replaces the
    Baron dock and hidden apps. `aurora` and `six-columns` are safe.
- The remote's Input key (scan code 79, `KEYCODE_TV_INPUT` 178) does nothing even on stock: CVTE's TV API
  turns it into a `tv.cloudwalker.ir.REMOTE` broadcast for the stock launcher, which Android 9 blocks
  ("Background execution not allowed"). `am start -a com.cvte.intent.SHOW_INPUT` opens CVTE's Source panel
  (`com.cvte.tv.setting/.extra.TvExtraActivity`: AV, HDMI 1, HDMI 2) over any screen, a good target for it.
- Watch the first boot after debloating: `persist.sys.bootvideo.path` points into `com.cvte.ad.spider`'s
  files (the boot video), and `tv.cloudwalker.apiservice` lives in `/vendor/app/cw_source/`.
- Memory (read-only audit, 2026-10-19): 437 MB, about 100 MB free with 226 MB in ZRAM, "critical" for
  2h20m of the 7h since boot. `/system/etc/cvte_white_black_list.ini` sets what may start at boot
  (`[startup]`), what the low-memory killer never stops (`[keeplive]`: App Store, updater, apiservice,
  inputserver, YouTube) and a `[launchername]`; the prop `cw.lpackage` names the stock launcher. Biggest
  users: stock launcher 38 MB average (78 peak), the All Apps screen 84 MB, App Store 39 MB (stays loaded
  once opened: `am force-stop tv.cloudwalker.market` after installing apps). No strong candidates beyond
  the list. Spotify's boot starter never runs (Spotify isn't in `[startup]`), so step 6 needs nothing.
  Must stay on, besides the list's leave-alone set: `com.cvte.tv.api.impl` (its HotkeyService handles
  the remote's app keys), the TV and downloads providers, the keyboard, FallbackHome.

## Next steps

1. Check ADB: `tools/venv/bin/python -I tools/tv.py --probe`. If refused, redo "Getting ADB".
2. Done: the input switch works by hand (the TV changes input; 4 is HDMI 1, 5 HDMI 2, 3 AV):
   ```bash
   tools/venv/bin/python -I tools/tv.py "B='am broadcast -n com.cvte.tv.api.impl/com.cvte.tv.api.tif.TifReceiver -a com.cvte.intent.ACTION_TUNE_SOURCE_OR_CHANNEL -c com.cvte.intent.CATEGORY_TUNE_SOURCE_OR_CHANNEL --ei sourceId 4'; \$B; \$B"
   ```
   Still to check through the launcher's Inputs menu once it's installed.
3. Dock order is confirmed. Build and install:
   `STOCK_LAUNCHER=tv.cloudwalker.launcher.com TV_HOST=192.168.1.20 launcher/build-and-install.sh`.
   Check it with screenshots: `tools/tv.py 'screencap -p /sdcard/s.png'`, then `tools/tv.py --pull /sdcard/s.png s.png`.
4. Uncomment the stage-B lines (apiservice, skin, apps) and run
   `TV_HOST=192.168.1.20 tools/disable.sh examples/baron-cloudtv-32/debloat.txt`.
5. Test Home, Back, the remote's Input key, the Settings and Wi-Fi pills, each app, and a reboot (Home
   must come back to the new launcher). Compare `dumpsys meminfo` with the backup.
6. Optional: the Clock screensaver (see README).
7. Set ADB ENABLE back to OFF in the factory menu.

Undo everything: `TV_HOST=192.168.1.20 tools/restore.sh examples/baron-cloudtv-32/debloat.txt tv.cloudwalker.launcher.com`.

## Working rules

- Work on the real TV and verify each change with key injection and a screenshot. Ask the user before
  disabling anything that isn't already in the list, and before anything that changes what's on screen
  while they might be recording.
- One `pm` command per shell call (`tools/tv.py --batch` does this).
- The user wants short replies, no em dashes anywhere (code and comments included), an explicit yes
  before every push, and no AI attribution lines in commits. Standing yes for this file: keep it
  updated and pushed to GitHub as work progresses, so either Mac can pick up where the other left off.
