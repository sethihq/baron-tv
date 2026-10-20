# Notes on the Baron Cloud TV

What was learned while cleaning up one Baron 32-inch "Cloud TV" and building Baron Home for it. Most of it
comes from CVTE's board and CloudWalker's OS rather than from Baron, so it may hold on other sets built
from the same parts. Everything was checked on this one TV only.

## The set

| | |
|---|---|
| Set | Baron 32-inch HD LED "Cloud TV" (80 cm), sold in India |
| OS | CloudWalker Cloud TV OS on AOSP Android 9 (API 28), a `userdebug` build with dev-keys |
| Board | CVTE TP.SK518T.PB816, MediaTek `mt5862_512m`, Mali-G31 MP2, firmware 20240913_213710 |
| Memory | 512 MB, 437 MB usable; Android calls memory "critical" even after the clean-up |
| Screen | 1366 x 768 panel, UI at 1280 x 720 and 160 dpi |
| Missing | Play Store, Chromecast, developer options, a screensaver (dream) service |

## Getting ADB

Cloud TV's settings have no Build entry, so there are no developer options. ADB is a switch in CVTE's
factory menu instead.

1. From the home screen, open the factory menu. On the remote press **Menu, Left, Up, Left, Up, Back**
   (Menu is ignored while an HDMI input is on screen). From a computer, the same menu opens with Menu
   then 1 1 4 7 sent through CloudWalker's phone-remote server:
   ```bash
   for k in 82 8 8 11 14; do curl -s "http://<tv ip>:5511/input/?keycode=$k"; echo; done
   ```
   Both sequences live in `/vendor/menu_config_xml/hotkey.xml` and are handled by `com.cvte.tv.api.impl`.
2. Open **DEBUG** (the 7th row) and set **ADB ENABLE** (its 4th row) to ON. Port 5555 opens at once, and
   the setting survives reboots.
3. Stay out of **SHIPPING INIT** (it wipes the TV), **AGING MODE** (a looping test screen), **BOARD INIT**,
   **PANEL SETTING** and **SW UPGRADE**.
4. This build's ADB accepts any computer without asking (`ro.adb.secure=0`), so set ADB ENABLE back to
   OFF when you're done.

Never remove `com.cvte.fac.menu` or `com.cvte.tv.api.impl`: together they are the way back into ADB.

### Finding the TV

The Wi-Fi MAC address changes at every boot, so the TV's IP address does too. The factory menu's first
page shows the current IP. While CloudWalker's phone-remote server is running, the TV also announces
itself over mDNS as `_cloudwalker._tcp`. After a reboot that server stays stopped until its app has been
opened once, so neither the mDNS name nor port 5511 answers until then.

### CloudWalker's phone-remote server

`tv.cloudwalker.inputserver` serves HTTP on port 5511 with no authentication. `GET /input/?keycode=N`
injects an Android key code, `/play?packageName=` opens an app, `/apps` opens the All Apps screen and
`/tvInfo` returns the board, model, panel and MAC addresses. All of them except `/tvInfo` are actions, and
`/play` answers OK even for apps that don't exist. Keys injected this way never reach accessibility
services.

## The clean-up

- **Disabling doesn't stick.** At boot every package comes back enabled before any app runs, so
  `pm disable-user` undoes itself. `pm uninstall -k --user 0` does survive, and keeps the APK in the
  system image with its data; `cmd package install-existing <package>` brings it back. That is what
  `REMOVE=1 tools/disable.sh` does. `pm hide` is refused, because the shell lacks `MANAGE_USERS`.
- When the set of Home apps changes, Android forgets the default and asks again after the next boot with
  a "Use ... as Home" chooser. Pick the home you want and Always.
- `/system/etc/cvte_white_black_list.ini` decides what may start at boot (`[startup]`) and what the
  low-memory killer never stops (`[keeplive]`). Keep `com.cvte.tv.api.impl` (hotkeys, inputs, the factory
  menu), `com.cvte.tv.globalui`, the MediaTek input packages, the TV and downloads providers, the keyboard
  and the fallback Home. The list in `examples/baron-cloudtv-32/debloat.txt` says what else must stay.
- CVTE's TV settings (the Menu key) start the stock launcher, `tv.cloudwalker.launcher.com`, by name when
  you leave them, past the default Home. After the clean-up it only showed an error screen, so it is
  removed for the user too.
- The boot video path (`persist.sys.bootvideo.path`) points into `com.cvte.ad.spider`'s files. The TV boots
  fine with that package removed.
- The shell can't request a clean restart (`svc power reboot` needs `REBOOT`). `tools/tv.py reboot` works
  and the TV takes about a minute to come back.

## Apps

- **CloudWalker's App Store** reads the Home app's `versionName` and crashes at start unless it has four
  parts separated by dashes, because it uses the fourth as a header. Both Baron Home and the launcher use
  names of that shape. The store stays in memory after use; force-stop it after installing apps.
- **Netflix** isn't in the store and won't run: the set isn't Netflix certified (no `ro.nrdp.*`
  properties). The remote's Netflix key is wired to Netflix's phone app, which isn't installed either.
- **The firmware force-stops the app you leave** whenever Home comes up (`black_white_list_manager` in
  logcat). Only packages on its keep-alive lists survive: YouTube is there, Spotify isn't. Both lists sit
  on read-only partitions and changing them needs root and a remount, which can stop the TV booting. So
  music never plays behind the home screen, and Baron Home's music mode runs over the music app.

## Inputs and keys

- Nothing on this TV answers Android's standard intent for opening an HDMI input. CVTE's own route is a
  broadcast to `com.cvte.tv.api.impl/com.cvte.tv.api.tif.TifReceiver` with the action
  `com.cvte.intent.ACTION_TUNE_SOURCE_OR_CHANNEL`, the category
  `com.cvte.intent.CATEGORY_TUNE_SOURCE_OR_CHANNEL` and an int extra `sourceId`: 3 for AV, 4 for HDMI 1,
  5 for HDMI 2. It ignores the channel URI. From the home screen it has to be sent twice, because the
  player it opens only switches inputs on the second intent:
  ```bash
  B='am broadcast -n com.cvte.tv.api.impl/com.cvte.tv.api.tif.TifReceiver -a com.cvte.intent.ACTION_TUNE_SOURCE_OR_CHANNEL -c com.cvte.intent.CATEGORY_TUNE_SOURCE_OR_CHANNEL --ei sourceId 4'; $B; $B
  ```
  CVTE's own source panel opens with the action `com.cvte.intent.SHOW_INPUT`.
- The TV reports every input as standby, connected or not.
- The remote's **Input key** (`KEYCODE_TV_INPUT`, 178) did nothing even before the clean-up: CVTE turns it
  into a broadcast to the stock launcher, which Android 9 blocks. An accessibility service catches it and
  opens the source panel instead.
- The remote's **volume and mute keys** go to CVTE's hotkey service in `com.cvte.tv.api.impl` before any
  app sees them, and it shows its own round pop-up. There's no setting to turn that off.
- `input keyevent` skips accessibility services. To test like the real remote, write raw events, for
  example Vol+ (scan code 115; Vol- is 114, Mute 113, Input 79):
  ```bash
  sendevent /dev/input/event0 1 115 1; sendevent /dev/input/event0 0 0 0
  sendevent /dev/input/event0 1 115 0; sendevent /dev/input/event0 0 0 0
  ```

## Drawing and system services

- The renderer is Android 9's older OpenGL pipeline (FrameBuilder, `debug.hwui.renderer=opengl`), not
  SkiaGL. An `ALPHA_8` bitmap inside a `BitmapShader` draws black, while `drawBitmap` of the same bitmap
  tints correctly; gradients with a local matrix work; only basic Porter-Duff modes plus ADD and MULTIPLY
  are safe; there's no blur. Paparazzi snapshots don't show any of these limits, so check on the TV.
- Baron Home lays itself out at 213 dpi, so the 1280 x 720 screen is exactly a 960 x 540 dp canvas, the
  same as the prototype's.
- `ro.config.low_ram=true` removes notification access: `cmd notification allow_listener` exits 0 and
  does nothing. What's playing can still be read through an accessibility service, because a music app's
  media notification carries its session token and a `MediaController` built from a token needs no
  permission. Spotify's TV app posts no such notification while it's in front.
- There is no dream service (`dumpsys dreams` can't find it), so no screensaver ever runs on this TV.
  Baron Home's DVD screensaver runs inside the home screen.
- Wi-Fi scans and the network's name need location on. The install script turns on the network location
  provider; the TV has no GPS.
- Android takes an accessibility service off the enabled list when its package is force-stopped. Baron
  Home puts its own back at once, using `WRITE_SECURE_SETTINGS` granted over ADB.

## Philips WiZ bulbs

Philips Smart Wi-Fi bulbs with WiZ inside take JSON commands over UDP port 38899 with no account. The one
tested (firmware 1.38) answered "Invalid params" to every change until local communication was allowed in
the WiZ app, under Settings, Security. A command answers in 20 to 40 ms; music mode sends at most one per
bulb every 90 ms.

## Measured

Baron Home cold-starts in about 0.8 s (up to 5 s right after an install, while the TV compiles it) and uses
about 58 MB of memory, 11 MB of it private. Moving focus round the screen gave 557 frames with 1.3% janky,
p50 6 ms and p90 9 ms. The screensaver runs at 60 fps.
