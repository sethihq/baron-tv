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
| RAM | 512 MB, 437 MB usable. Android reports memory as "critical" even after the debloat |
| Screen | UI 1280x720 at 160 dpi |
| Network | Wi-Fi, DHCP. The MAC changes on every boot (`88:00:33:77:xx:xx`), so the IP does too (.20 to .24 on 2026-10-19) |
| Remote | No number keys: Power, Mute, D-pad, Home, Back, Menu (three lines), Vol -/+, Input, Play/Pause, cursor, Netflix, Prime Video, YouTube |

Find the TV at the start of every session:

```bash
dns-sd -G v4 Android.local      # CloudWalker's phone-remote service (instance CloudTV_<ethernet MAC>)
arp -an | grep 88:0:33:77       # after a ping sweep of the subnet
```

or read the IP row in the factory menu.

The firmware's default system lock PIN (`ro.CVT_DEF_SYS_LOCK_PASSWORD`) is `1234`, in case a PIN prompt appears.

## Getting ADB (the TV has no Developer options)

1. CloudWalker's phone-remote server (`tv.cloudwalker.inputserver`) listens on port 5511 with no auth.
   `GET /input/?keycode=N` injects an Android key code. `/apps` opens the All Apps screen (gone since
   the debloat) and `/play?packageName=` launches an app and always answers OK: they are actions, not reads.
2. Open the CVTE factory menu with Menu, then 1 1 4 7:
   ```bash
   for k in 82 8 8 11 14; do curl -s "http://$TV_HOST:5511/input/?keycode=$k"; echo; done
   ```
   From the physical remote, without number keys: Menu, Left, Up, Left, Up, Back. Either way, start
   from the home screen: Menu is ignored while HDMI 1 is on screen. Both sequences live in
   `/vendor/menu_config_xml/hotkey.xml` and are caught by `com.cvte.tv.api.impl`, not by any launcher.
3. Go to DEBUG (7th row), then set ADB ENABLE (4th row) to ON with Right. Port 5555 opens at once.
   Stay out of SHIPPING INIT (wipes the TV), AGING MODE (looping test screen), BOARD INIT,
   PANEL SETTING and SW UPGRADE. Over the API: from BACKLIGHT VALUE, Down x3 (keycode 20) then Right (22).
4. ADB is unauthenticated (`ro.adb.secure=0`): any computer on the Wi-Fi gets a shell. It also survives
   reboots (`persist.sys.usb.config=adb`, `persist.adb.tcp.port=5555`). Set ADB ENABLE back to OFF
   when the work is done.

Never touch `com.cvte.fac.menu`, `com.cvte.tv.api.impl` or `tv.cloudwalker.inputserver`: together they
are the only way back into ADB.

## Setup on a new Mac

```bash
brew install uv openjdk@17 && brew install --cask android-commandlinetools
JAVA_HOME=/opt/homebrew/opt/openjdk@17 sdkmanager "platforms;android-34" "build-tools;34.0.0"
uv venv tools/venv && uv pip install --python tools/venv/bin/python -r tools/requirements.txt
export TV_HOST=<ip from dns-sd above>
tools/venv/bin/python -I tools/tv.py --probe   # expect SMART_TV, mt5862_512m, Android 9
```

The Mac must be on the same Wi-Fi as the TV, and macOS may ask to let the terminal reach the local
network: allow it. `launcher/build-and-install.sh` finds the Homebrew JDK and SDK by itself.

## State on 2026-10-19 (evening)

- **Done, and it survives reboots** (three hard reboots tested):
  - The new launcher (`com.gathin.tvhome`) is installed and is the default Home. The stock launcher
    (`tv.cloudwalker.launcher.com`, label "Lite Launcher") is left installed as a fallback; it doesn't run.
  - The 15 packages in `examples/baron-cloudtv-32/debloat.txt` (stage B included) are removed for
    user 0 with `REMOVE=1` (see below), not disabled.
  - `InputKeyService` is on: the remote's Input key opens CVTE's Source panel from any screen.
  - JioHotstar (`in.startv.hotstar` 26.05.25.0) is installed from the Cloud TV App Store. The user still
    has to accept its terms and sign in on the TV.
- Checked on the TV: Home and Back; each app opens (YouTube, Prime Video, Spotify, App Store, Media
  Center, JioHotstar); Inputs menu to HDMI 1 (HAL log `startMainVideo(main, source_id = 4) ret = 0`);
  Input key; Wi-Fi and Settings pills. HDMI 2 and AV through the menu not tried (same code path; their
  broadcasts were verified by hand).
- Memory 2 minutes after boot, against the stock backup: free 154 MB (was 138), used PSS 267 MB (300),
  in ZRAM swap 150 MB (188). The launcher uses 13.5 MB; the stock one averaged 38 MB.
- User's answers: doesn't use Movie Box, Browser or Kindlink (all go); both updaters off; keep the
  Cloud TV App Store (only way to install apps), remove Stark Store. Dock: YouTube, Prime Video,
  Spotify, Cloud TV App Store. Asked for Netflix and JioHotstar: JioHotstar is in, Netflix can't be
  (see below). Asked whether to switch it to Google's Android TV: advised against, since there's no
  official build for this board, a wrong file can brick it, and Google's services need more memory
  than the TV has.
- Backup of the stock state: `backup/20261019-151014/` (package lists, getprop, settings, Home and
  keyboard, meminfo) and `backup/dumpsys/` (per-package dumps). Committed on purpose, since this repo is
  private. `tools/backup.sh` takes a fresh one.
- Work happens on two Macs; this file is how they hand over.

## How this firmware behaves

- **Disabling doesn't stick.** At boot every package comes back enabled (state 0) before any app runs,
  so `pm disable-user` undoes itself on the next reboot. The default-Home choice does survive, and so
  does `pm uninstall -k --user 0`, so the list is applied with
  `REMOVE=1 tools/disable.sh examples/baron-cloudtv-32/debloat.txt`. The APKs stay in the system image
  with their data; `tools/restore.sh` (or `cmd package install-existing <pkg>`) brings a package back.
  `pm hide` is refused (the shell lacks MANAGE_USERS). `REMOVE=1` exists in the `.sh` tools only.
- If the set of Home apps changes (a launcher appears or disappears), Android drops the default and
  shows a "Use Home as Home" chooser after the next boot: pick Home, ALWAYS. That's what happened when
  the first reboot re-enabled the stock launcher.
- The shell can't ask for a clean restart (`svc power reboot`: no REBOOT permission); `tools/tv.py reboot`
  (hard) works. The TV takes about a minute to come back, at a new IP.
- **App Store:** CloudWalker's store reads the Home app's versionName and crashes at start unless it has
  four dash-separated parts (`CwHeaders`, it takes the fourth as a header). The launcher's versionName
  is `2.0-0-tvhome-lite-com` for that; the type matches the stock launcher's `1.4.0-101-gdb625e6-lite-com`.
  The store stays in memory once opened: `am force-stop tv.cloudwalker.market` after installing apps.
  App pages also open from links (`cwmarket://appstore`, parameter name unknown).
- **Netflix:** not in the Cloud TV Store. The TV isn't Netflix-certified (no `ro.nrdp.*` props), so
  Netflix's TV app won't run, and Netflix's help centre says its phone app needs a Play Protect certified
  device. The remote's Netflix key is wired to the phone app (`com.netflix.mediaclient`). Realistic
  route: a streaming stick on HDMI, switched to with the Inputs menu or the Input key.
- **Inputs:** nothing answers Android's standard passthrough VIEW intent. The launcher sends
  `com.cvte.intent.ACTION_TUNE_SOURCE_OR_CHANNEL` (category `com.cvte.intent.CATEGORY_TUNE_SOURCE_OR_CHANNEL`)
  by name to `com.cvte.tv.api.impl/com.cvte.tv.api.tif.TifReceiver`, which ignores the URI and reads an
  int extra `sourceId`, the number after "HW" in the input ID (3 AV, 4 HDMI 1, 5 HDMI 2). It opens the
  single-instance `TvSupportActivity`, which only switches in `onNewIntent`, so from Home the broadcast
  goes twice. By hand:
  ```bash
  tools/venv/bin/python -I tools/tv.py "B='am broadcast -n com.cvte.tv.api.impl/com.cvte.tv.api.tif.TifReceiver -a com.cvte.intent.ACTION_TUNE_SOURCE_OR_CHANNEL -c com.cvte.intent.CATEGORY_TUNE_SOURCE_OR_CHANNEL --ei sourceId 4'; \$B; \$B"
  ```
  The TV closes whatever launcher is running when it switches input, and Home closes the player.
- **Keys:** Home is a plain `KEYCODE_HOME` to the default Home (no `HOME_KEY_SERVICE` needed). The app
  keys go through `com.cvte.tv.api.impl` and `hotkey.xml`. The Input key (scan code 79,
  `KEYCODE_TV_INPUT` 178) did nothing even on stock: CVTE turns it into a broadcast to the stock launcher,
  which Android 9 blocks. `InputKeyService` now opens `com.cvte.intent.SHOW_INPUT` for it
  (`input_key_action`). `input keyevent` bypasses accessibility services; to test like the real remote:
  `tools/tv.py 'sendevent /dev/input/event0 1 79 1; sendevent /dev/input/event0 0 0 0; sendevent /dev/input/event0 1 79 0; sendevent /dev/input/event0 0 0 0'`.
- **Launcher changes from upstream:** `config.xml` dock and hidden CloudWalker helpers, no Live TV (no
  tuner); `ui_density_dpi` = 213 (at 1280x720 and 160 dpi the design would draw a quarter too small);
  `input_action`, `input_action_category`, `input_action_extra` = `sourceId`, `input_action_repeat` = 2;
  `input_key_action`; the versionName above. All but the versionName can also come from a theme's
  `config.json`. Don't push `themes/default`: it replaces the Baron dock and hidden apps.
- **Memory:** `/system/etc/cvte_white_black_list.ini` sets what may start at boot (`[startup]`) and what
  the low-memory killer never stops (`[keeplive]`). Must stay: `com.cvte.tv.api.impl` (hotkeys, inputs,
  factory menu), `com.cvte.tv.globalui`, the MediaTek input packages, the TV and downloads providers, the
  keyboard, FallbackHome, and the list's leave-alone set. Spotify's boot starter never runs.
- The boot video path (`persist.sys.bootvideo.path`) points into `com.cvte.ad.spider`'s files; the TV
  boots fine with that package removed.

## Next steps

1. The user accepts JioHotstar's terms and signs in, and signs in to the other apps if needed.
2. Netflix: if wanted, a streaming stick on HDMI (Fire TV Stick or Chromecast with Google TV).
3. Optional: JioHotstar in the dock instead of the App Store (`dock_apps` in `config.xml`, then reinstall).
4. Optional: the Clock screensaver (see README).
5. Record the "after" video.
6. When done: ADB ENABLE back to OFF in the factory menu. After that, any change needs "Getting ADB" again.

Reinstall the launcher after a change: `INPUT_KEY_SERVICE=1 TV_HOST=<ip> launcher/build-and-install.sh`
(Home already belongs to it, so nothing else is disabled).

Undo everything: `TV_HOST=<ip> tools/restore.sh examples/baron-cloudtv-32/debloat.txt tv.cloudwalker.launcher.com`.

## Working rules

- Work on the real TV and verify each change with key injection and a screenshot. Ask the user before
  removing anything that isn't already in the list, and before anything that changes what's on screen
  while they might be recording.
- One `pm` command per shell call (`tools/tv.py --batch` does this).
- The user wants short replies, no em dashes anywhere (code and comments included), an explicit yes
  before every push, and no AI attribution lines in commits. Standing yes for this file: keep it
  updated and pushed to GitHub as work progresses, so either Mac can pick up where the other left off.
