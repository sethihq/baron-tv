# Baron TV: notes for working on this repo

Working notes for the agent and the people building Baron Home, a fork of
[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) (MIT, formerly tv-vibehack) for one TV: a
Baron 32" "Cloud TV". It began as stripping the CloudWalker and CVTE ads and bloat and replacing the home
screen with the tv-vibehack launcher, and grew into Baron Home and its phone app. The repo is public since
2026-10-21: keep personal details (addresses, network names, device identifiers) out of it. README.md is the
public introduction; this file is the working log and handoff. The history was rewritten that day, before
going public (one commit identity, the TV's dumps and a few identifiers taken out), so a clone from before
then can't pull: clone it again.

Upstream's README (now `CLEANYOURTV.md`) and `PROMPT.md` describe the general method. This file is the state of this TV
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
| Network | Wi-Fi, DHCP. The MAC changes on every boot (`88:00:33:77:xx:xx`), so the IP does too (.20, .24, then .25 on 2026-10-19) |
| Remote | No number keys: Power, Mute, D-pad, Home, Back, Menu (three lines), Vol -/+, Input, Play/Pause, cursor, Netflix, Prime Video, YouTube |

Find the TV at the start of every session:

```bash
dns-sd -G v4 Android.local      # CloudWalker's phone-remote service (instance CloudTV_<ethernet MAC>)
arp -an | grep 88:0:33:77       # after a ping sweep of the subnet
```

or read the IP row in the factory menu.

After a reboot the phone-remote server comes back in Android's stopped state (`stopped=true`), so port
5511 and the `Android.local` name stay silent until it's opened once. Use the ARP sweep, then over ADB:
`am start -n tv.cloudwalker.inputserver/.MainActivity` and `input keyevent KEYCODE_HOME` (checked
2026-10-19: port 5511 opens at once). The remote's own factory-menu sequence below doesn't need it.

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
- Tuning, later that night (all reversible, nothing removed):
  - System animations at 0.5x (`window_animation_scale`, `transition_animation_scale`,
    `animator_duration_scale`). `tools/restore.sh` sets them back to 1.0.
  - Cloud TV App Store compiled with `cmd package compile -m speed -f tv.cloudwalker.market`: it was the
    only app still interpreted (30 MB on /data). Undo: `cmd package compile --reset tv.cloudwalker.market`.
    The other apps were already `speed-profile` from install, the launcher `speed`.
  - Private DNS through AdGuard: `settings put global private_dns_specifier dns.adguard-dns.com`, then
    `settings put global private_dns_mode hostname`. Checked: Wi-Fi still validated, YouTube resolves,
    `doubleclick.net` is blocked, the Cloud TV Store and JioHotstar's login screen load. Undo:
    `settings put global private_dns_mode off`, the first thing to try if an app stops loading or playing.
  - Measured first: 215 MB free, 216 MB used PSS, idle CPU near 0, 542 MB free on /data. Background Wi-Fi
    scanning was already off. JioHotstar idles as a 13 MB cached process (a Firebase sessions service)
    that Android reclaims on demand, so restricting background apps wasn't worth it.
- User's answers: doesn't use Movie Box, Browser or Kindlink (all go); both updaters off; keep the
  Cloud TV App Store (only way to install apps), remove Stark Store. Dock: YouTube, Prime Video,
  Spotify, Cloud TV App Store. Asked for Netflix and JioHotstar: JioHotstar is in, Netflix can't be
  (see below). Asked about installing Google's Android TV and about more launcher customisation: see
  "Suggestions" under Next steps.
- Backup of the stock state: `tools/backup.sh` writes package lists, getprop, settings, Home and keyboard
  and meminfo to `backup/<timestamp>/`, which is gitignored: TV dumps hold device identifiers, so they
  stay on the machine that took them (the first one, from 2026-10-19, is kept outside git).
- 2026-10-20: a home screen redesign is under way in `prototype/` (see "Home screen redesign"). The TV
  itself hasn't changed since the tuning above.
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

## Home screen redesign (prototype, 2026-10-20)

A clickable web prototype of the new home screen lives in `prototype/`. The user reviews every UI change
there first: **nothing goes to the TV until they approve it in the prototype.** Plain HTML, CSS and JS,
no build step:

```bash
uv run --no-project python -I prototype/serve.py   # no-cache server, so a plain reload shows the latest
open http://localhost:4321
```

The canvas is 960x540, so one CSS pixel is one dp in the launcher (the TV draws it at 1.33x on its
1280x720 output). Arrow keys are the D-pad, Enter is OK, Esc is Back. The toolbar above the TV switches
View (New, or Now: a screenshot of the current TV home), Font, Icons, Weather icons and the screensaver
delay, and has a Show now button for the screensaver.

What the user has decided, in order:

- **Dark only**, on black, after trying light.
- **Font: Open Runde** (rounded Inter, SIL OFL, `assets/fonts/open-runde/`), the only one ("Open Runde
  default, remove the others": Outfit and Timeless are gone; the launcher keeps its own Outfit). It has no
  Light, so the big numbers are Regular.
- **Icons: Phosphor** (MIT, `assets/icons/phosphor/<regular|duotone|fill>/`) for the buttons, chips and
  weather glyph; the weather icons are Phosphor too, the only set ("Meteocons remove, Phosphor default").
- **Layout from the user's two reference photos** of a custom Apple TV style launcher:
  - Page 1: greeting, clock and date on the left; round Inputs, Wi-Fi and Settings buttons on the right
    with live Bengaluru weather under them; a "Trending on your apps" shelf (one wide backdrop and two
    posters, each with a title and a "Series · Drama · Prime Video" line); the dock.
  - Page 2, reached with Down from the dock, which scrolls the dock to the top: Inputs (HDMI 1, HDMI 2
    and AV as tiles, dimmed when nothing is plugged in), Shortcuts (Wi-Fi, Picture, Sound, Settings) and
    a Bengaluru weather card (now, the next 8 hours, high and low, sunrise and sunset).
  - The reference's crypto and currency rates card was built, then **removed** at the user's request.
- **Dock order:** Prime Video, Netflix, YouTube, JioHotstar, Spotify, then App Store and Media Center (7
  tiles). Netflix can't run on this TV (see "How this firmware behaves"): its tile is drawn from the
  Netflix mark (`assets/apps/netflix-banner.svg`), and on the TV it could only switch to an HDMI stick.
- **Focus:** the focused item lifts where it is (dock tiles 1.08x, posters 1.035x, chips 1.06x and a
  shade lighter, each with a soft shadow) over 240 ms, ease-out, no bounce. The user rejected the
  installed launcher's travelling white glass highlight here ("going here and there", "not smooth"):
  don't bring it back. The round buttons and the Inputs menu fill with the one accent, sky blue
  `#51b3f9` (white on it fails WCAG contrast; flagged, the user's call).
- **Trays:** the dock band, buttons, chips and weather card are a translucent white veil (7.5 to 10%)
  with no edge line, so the glow tints them.
- **Glow:** behind everything, in the colour of the focused artwork, picked the way the launcher's
  `AppAdapter.vividColor` does it, then set in OKLCH at 62% lightness with chroma capped at 0.16, and
  yellows leaned to amber (hue 68) so they don't go olive. Hidden on the buttons and on page 2. The user
  hasn't said whether to keep it.
- **Screensaver:** the old DVD logo (our own drawing, not an official file) bouncing on black, a new
  colour at every bounce and a small pulse on an exact corner hit. It starts after 10 minutes without
  input, and the first key only wakes it.

Data, fetched by the page the way the launcher would:

- Weather: Open-Meteo, no key, Bengaluru at 12.9716, 77.5946, every 30 minutes.
- Shelf: TMDB discover for region IN on the Prime Video and Hotstar providers, by popularity, every 3
  hours, cached in localStorage. It needs the user's free TMDB key or v4 read token in
  `prototype/local/tmdb-key.txt` (gitignored, so create it on each Mac); without it the shelf shows
  placeholders. A poster can only open its app, not the exact title.
- App banners in `assets/apps/` were pulled from the APKs on the TV.

Added on the second Mac, 2026-10-20 afternoon (the user's asks in quotes):

- **Now playing, Dynamic Island style** ("spotify now playing dynamic island style also on tv"),
  `island.css` plus a section in `app.js`. A pill at the top centre, on the round buttons' centre line,
  while Spotify plays: cover, song and four bars moving in the cover's colour (dots when paused). It
  morphs open into a player (cover, song with Spotify's mark, artist, progress, times, previous / play /
  next) while it has focus, and for 4.5 s when the song changes or music starts; the date and the weather
  line fade while it's open, as an iPhone's status bar steps aside. It leads the top row: Up from the wide
  poster reaches it, focus lands on play/pause, Left and Right walk the controls, Right past next goes to
  Inputs, Esc goes to the dock. Hidden on page 2; stays, small, over the screensaver. Transport icons are
  always Phosphor fill. Toolbar: Spotify Playing / Paused / Off, Next song. Always black, with a
  hairline edge since black on black has no shape ("the island should always be black"; the Grey option
  is gone). Demo queue; covers and lengths come from Apple's public
  search (`itunes.apple.com/search`, open CORS) at run time, so none are stored, with drawn covers offline.
- **Glow** ("these bg color glow should be more optimized"), `glow.css`: the new default "Refined" rises
  from behind the dock, deeper and more saturated at its core, with a faint second tone in the top-left
  corner; the colours are registered properties so they morph between apps instead of dimming through
  black; a fine grain (overlay blend, invisible on black) stops banding on the 8-bit panel. It is the only
  glow ("glow set to the refined one, remove the others": Original and Off are gone) and the home
  screen's only light ("the glow should be the bottom glow below the dock, how you made before").
- **Typography and spacing** ("how apple does for its products"), in `app.css`: a type scale on `.screen`
  (`--t-display` 56, `--t-sub` 17, `--t-body` 15, `--t-label` 13, `--t-caption` 12, with `--tr-*`
  tracking and `--lh-text` 1.3), a 4 dp spacing scale (`--s-1` to `--s-8`), safe area 32 by 56. Section
  labels are 13 semibold and quieter than content; gaps 24 (clock to shelf), 20 (shelf to dock), 32 (dock
  to page 2); the date and the weather line share a baseline; page 2 fades the clock and shelf out.
- **Shelf alignment** ("these things also are not aligned"): the small posters sat 10 dp low because a
  `<button>` centres its content and the grid stretched them to the wide card's height; now
  `align-items: start`. Focused posters lift to 1.05 with a deeper shadow so focus can't read as misalignment.
- **Phone partner app** ("make a partner app for it for android, the ui should look good"): a clickable
  prototype in `prototype/phone/` (http://localhost:4321/phone/), in a 412x892 Android frame, same design
  language as the TV (Open Runde, Phosphor, one accent, 4/8 spacing, 44 px touch targets; touch presses
  shrink slightly instead of lifting). A "Living room TV, Connected" header and four tabs:
  - Remote: a touch pad (every 56 px of swipe is one D-pad step, a flick counts once, a tap is OK),
    Back, Home, Input, Menu, a volume pill that repeats while held, Mute, Play/Pause.
  - Apps: the dock's seven apps in order; Netflix marked "Not on this TV".
  - Playing: cover, song, artist, progress, previous / play / next, a TV volume slider; the cover's
    colour glows behind the screen.
  - TV: inputs and shortcuts, then Customise home: dock order by dragging (the Glow and Island pickers
    went when the TV settled on one look for each).
  Everything is simulated; each action says what the TV would do. A native build would be Kotlin with
  Jetpack Compose on the same tokens; find the TV despite its per-boot IP (subnet scan, or the launcher
  announcing itself over NSD); send keys to CloudWalker's port 5511 (`/input/?keycode=N`); and add a
  small LAN server to the launcher for apps, now playing, inputs, volume and the theme file, behind a
  pairing code shown on the TV.

- **Prototype chrome** ("move all these to the right side panel"): the controls live in a panel down
  the right (Screen, Look, Now playing, Screensaver, Keys) and the TV fills the rest; under 1040 px wide
  the panel drops below the TV.
- **Clock widget** ("good morning, time and date, weather on the right in an Apple Watch style cute
  widget, space saving"), `watch.css`, the default (Look > Clock: Widget, or Classic to compare): a
  calendar badge (weekday in Apple's calendar red), a temperature gauge (a 270 degree arc from today's
  low to its high, a dot at now, the temperature inside, the sky's glyph in the gap) and the time with
  the greeting over it. Every clock (Bento, Widget, Classic) now leads the top band at the left and the
  round buttons sit at the right ("the clock on the left, HDMI and the others on the right, the same for
  all of them"), as in the sidebar layout; with the bento or the widget the island is centred in the gap
  between them, with Classic it keeps the screen's centre and the date steps aside. The
  height saved goes to the posters (222 tall, from 190), so the dock doesn't move.
- **Page 2** ("the whole panel on the right, HDMI and other things on the left"): inputs and shortcuts
  stack down the left, the weather panel takes the right column, its place name level with "Inputs",
  its top and bottom flush with the tiles; six hours of forecast. Inputs and shortcuts are two focus rows.
- **Edge glow while music plays** ("a Mac app glows the screen edges when music plays, not for a movie
  intro"), `edge.css` plus the edge section of `app.js`. Researched: Lumn (paid, $9.99, two cover colours
  60/40, beat or steady idle glow), Open Glow (MIT: k-means cover colours flowing round, FFT beat swells),
  Rimshot (open source: neon cover palette, kick detection, drawn only in edge strips), MacGlow, EdgeBeat.
  Third version, after "the edge glow sucks, not aesthetic", "not straight clipped, inner shadows, make it
  interesting", the user's reference of glowing recap cards and "there is this hard line, don't want
  that": soft dabs of light along the rounded edge on a half-resolution canvas, added together, centred
  just outside the screen so the edge never reads as a line (no stroke at the edge). Colour: three hues,
  the cover's main colour, -55 and +45 degrees, vivid, any olive hue leaned to amber, lightest at the edge
  and saturating inward, flowing round every 50 s. (A five-colour loop that drifted round the wheel with
  every kick was tried and dropped: "too many colours, I liked the earlier one".) It shows only in
  music mode, over the dimming, and never with the screensaver ("the DVD player thing should never clash
  when the glow is happening": while music plays, idle time goes to music mode, and starting the
  screensaver ends music mode first): on the home screen the one light is the glow
  below the dock, as before the edge light existed ("the glow should be the bottom glow below the dock,
  how you made before"; a backlight version behind the UI, then a bottom-weighted one, came first). Its
  depth rolls along the edge like a slow tide and pools in the corners; a soft inner shadow under it
  gives depth.
  **Synced to the real music** ("edge lighting should be syncing with music"): panel Glow follows Song /
  Mic / Tempo. Song plays each track's 30 s Apple Music preview (`previewUrl` from the iTunes search, open
  CORS) and listens with a Web Audio FFT; Mic listens to the room (getUserMedia); Tempo fakes it. Kicks
  (spectral flux 40 to 150 Hz over an adaptive threshold) send a swell of light from the bottom centre
  round both sides; the highs' jumps (4 to 10 kHz) send small ones from the top; the bass deepens the glow,
  the highs sparkle it. The island shows the preview's real time. Never for video (Sound is: Video).
  **Music mode** ("when a song plays the middle should dim, only the edge lighting visible and breathing,
  at full"): while music plays and the remote rests (Music mode after 8 s / 30 s / Off, plus Show now),
  everything in the middle fades to black and the light grows to full strength, breathing on a 5.5 s
  cycle with the beat swells on top; it replaces the DVD screensaver while music plays; the first key
  only wakes the screen.
  On the TV: light only when the playing stream's `AudioAttributes` content type is MUSIC
  (`AudioManager.getActivePlaybackConfigurations`); the spectrum from `android.media.audiofx.Visualizer`
  on the output mix (RECORD_AUDIO through `pm grant`); draw with one pre-blurred dab bitmap, tinted, at
  half resolution; over other apps it needs an overlay window (SYSTEM_ALERT_WINDOW through `appops`).
- **App tiles** ("tiles not united, logos not well designed, I want the covers real TVs use"), always
  covers now ("tiles should always be covers"; the Banners option is gone, and the phone app uses the
  same covers), `assets/apps/covers/*.svg`: each
  brand's TV banner colour full-bleed, every mark centred at one optical size (at most 62% wide and 40%
  tall, with small per-mark corrections), a shared 6% top sheen, a white 10% hairline round every tile so
  black ones keep their edge. Netflix shows its red wordmark. Logos: Prime Video 2024, Netflix, YouTube
  and Spotify are official SVGs from Wikimedia Commons; JioHotstar traced from Wikipedia's 2025 PNG;
  Cloud TV App Store and Media Center traced from their APK banners.

- **Layout: Sidebar** (default; Top bar to compare), `sidebar.css` ("move this panel to the right side,
  and a similar dock on the right for HDMI and the others", then, after a first swap, "the bento on the
  left, the HDMI one on the right"): the bento widget sits top left, 232 wide, with the shelf under it;
  the dock of tiles (Inputs: HDMI 1 with a green dot while connected, HDMI 2, AV; TV: Wi-Fi, Picture,
  Sound, Settings) runs down the right from the top-right corner, 232 wide; the island hangs centred in
  the gap between them, level with their tops, and opens only 364 wide so it covers neither; the shelf's
  art flexes so its captions end exactly where the dock of tiles ends, above the full-width app dock; no
  page 2. A focused dock tile only turns lighter ("selected state big, remove, only colour change is fine").
  **Wi-Fi and Sound views** ("when Wi-Fi is clicked the whole panel should turn into the Wi-Fi devices
  nearby, same for Sound"): the tiles slide out and a view slides in, as a Control Centre module opens.
  Redrawn after "this UI also please improve": the header has an iOS-style switch (Wi-Fi on or off; off
  leaves a quiet line instead of the list); the network in use is its own card (a green badge, the name
  over "Connected"); the rest sit under "Other networks" in one grouped card split by hairlines, each with
  its lock and a signal icon that is always the full fan with the arcs it doesn't reach dimmed, so they
  line up. Choosing one moves it into the card, "Connecting…" with a breathing grey badge, then
  "Connected". Sound is the box itself turned into one tall volume bar ("only the sound bar should be
  there, the bento box itself should turn into the sound bar"): no header and no list of outputs (they
  were dropped), the level fills the box from the bottom in white, the glyph at the top, "Volume" and a
  big number at the bottom, flipping dark where the fill covers them; Up and Down in fives, a click or
  drag on the Mac, the glyph mutes at 0. Focus is colour only everywhere, except the switch, whose
  colour is its state, so it gets a ring. Back or the chevron returns, and leaving a view (Left, to the
  shelf) closes it. Demo networks; on the TV, `WifiManager` scan results (location permission is granted
  by the install script) would fill them, and the bar would drive `AudioManager.setStreamVolume`. Focus moves to the nearest item in the pressed direction (FocusFinder
  style: same band first, then closest), since these aren't simple rows any more.
- **Beat bot** ("there's this ESP32 bot face, open source; in the centre, cutely dancing on the song"):
  robot eyes in the middle of music mode, a panel switch (Beat bot On / Off). The reference is FluxGarage
  RoboEyes (GPL-3.0), so none of its code is used: two rounded eyes and a small smile drawn from scratch on
  a canvas. After "it should blink and dance, more reactions, remove the glow" it is flat white with no
  glow or tint; a try with mouthless, carved eye shapes was rejected ("the earlier ones were cuter, do
  around them only"), so the face is the first one and everything new is life around it. A new dance
  every 4 to 6.5 s, landing on the kicks: nod, sway, hop, side step, eyes bouncing in turn, looking left
  and right, eyes circling. A mood every 7 to 12 s: plain, smiling, chilled (a contented squint, smaller
  moves), curious (one eye bigger, head on one side, a sideways smile). Every 3 to 6 s a gesture on the
  beat: a wink, a double blink, a laugh (^ ^ with an open mouth and a shake), a quick glance about, or
  heart eyes (the biggest hits bring hearts or a beaming squint). It hears the song: a drop makes it gasp
  (big eyes, an "o" mouth) then beam; 3.5 s with no beat makes it sleepy (half-shut eyes breathing, head
  drooping, a flat mouth), and the beat coming back wakes it with a start. Blinks every 1.6 to 4.8 s, a
  quarter of them double, and one at each new dance. Happy eyes close to a line that bends into ^, never
  a fade. With Edge glow Steady it still dances to the song's felt tempo; with reduced motion it shows
  still eyes. Springs carry every move. Port: a custom View drawing the same shapes on a Canvas.
- **Shelf in the sidebar** ("size height should be equal"): the posters share one set of rows (art,
  title, details) through CSS subgrid, so all three pieces of art are the same height and the titles share
  a line whether or not a poster has details.
- **Clock: Bento** (default; Widget and Classic stay), `bento.css` and `bento.js` (the user sent four
  widget references, "cute widgets like this but don't want to copy"): a black frame with a hairline
  holding a cream watch dial with an orange second hand, the greeting over a chunky time, a square in the
  colour of the sky now (sun, part cloud, cloud, rain, storm, night) with the temperature, and a line
  that reads like a sentence: the date, then what the weather does next ("Drizzle from 2 pm", "Clear till
  late"), computed from the coming hours.
- **Selected states** ("drop shadows on selected states too strong", then "selected state big, remove,
  only colour change is fine", then "getting too big on selected state"): focus is colour only
  everywhere, nothing grows, lifts, casts a shadow or moves. Posters and app tiles get a fine ring, 1 dp
  of 60% white 2 dp outside the art ("white stroke too stark, subtle, less stroke"; their pictures hide
  any change of surface; a poster with no art also turns lighter, and its title brightens); dock tiles, chips and the Wi-Fi and Sound rows turn lighter. A
  press dips to 0.97 for a moment, as the round buttons do. Port: a focus ring drawable, no scale
  animators on focus.
- **Phone app polish** ("selected glow and drop shadow too strong, should be subtle; also use haptics"):
  quieter selected, pressed and dragged states; haptics through one helper (`navigator.vibrate` in the
  prototype) with named patterns (tick, tap, repeat, confirm, reject, lift, slot, select), each mapped in a
  comment to `HapticFeedbackConstants` and Compose's haptic types for the native build. The native app is
  not built yet: only the localhost prototype exists.

Porting the island: a `NotificationListenerService` (say `MediaListener`) gives the launcher
`MediaSessionManager.getActiveSessions`; follow Spotify's `MediaController` (metadata title, artist,
`METADATA_KEY_ALBUM_ART`, and `PlaybackState` position extrapolated from its update time and speed),
send play / pause / next / previous with its transport controls. Grant over ADB with
`cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener`. **Unchecked on the
TV** (it was off at midday on 2026-10-20): whether Spotify's TV app publishes a session while it plays,
and whether that command exists on this Android 9 build. Check the island's top position for overscan
on the real panel.

Open questions for the user:

1. The TMDB key (not given yet).
2. Keep App Store and Media Center in the dock, or move them to page 2 so the dock is the five apps?
3. The Netflix tile on the real TV: drop it, or point it at an HDMI stick once there is one?
4. Island (always black now): keep it over the screensaver?
5. Edge glow (music mode only now): Beat or Steady?
6. Layout: the sidebar, or the top bar? Clock: Bento, Widget or Classic? (Settled: covers, Open Runde,
   the Refined glow, Phosphor weather icons.)
7. Music mode: 8 s, 30 s or off? Glow follows the song, the mic, or a fixed tempo?

Porting it to the TV once approved (`launcher/`, Java):

- Rebuild the layout as page 1 and page 2, with the prototype's focus model (rows top to bottom; Up and
  Down go to the nearest item in the next row).
- Open Runde from the release's `desktop/` OTFs (v1.0.1, GitHub `lauridskern/open-runde`) into
  `res/font`; Phosphor SVGs as VectorDrawables.
- Weather and TMDB need the `INTERNET` permission, the launcher's first network use. Keep the TMDB key
  out of git (`local.properties`, read into `BuildConfig`).
- The screensaver as a new `DreamService` next to `ClockDream`, then on the TV:
  `settings put secure screensaver_components com.gathin.tvhome/.<Dream>`,
  `settings put secure screensaver_enabled 1` and `settings put system screen_off_timeout 600000`.
  Check that the CVTE firmware actually runs dreams.
- Mind the 512 MB: three posters at most, TMDB sizes w342 and w780, no blur, no video.

## New home screen (native, `home/`, 2026-10-20)

The approved prototype, Sidebar layout with the Bento clock and Line icons, ported as its own app:
`com.barontv.home` ("Baron Home"), now the TV's Home. The old launcher (`com.gathin.tvhome`) stays
installed as the fallback. Kotlin with framework views only (no AndroidX, no Compose: lightest on 512 MB),
kotlinx-coroutines, minSdk 28, release with R8 (about 1.2 MB). It lays out at 213 dpi, so the TV's 1280 x
720 is exactly the prototype's 960 x 540 dp and every prototype measurement maps 1:1 (`activity_home.xml`
slots, `core/Tokens.kt` colours converted from OKLCH).

- **Install, undo, measure** (TV on, `TV_HOST` set): `home/build-and-install.sh` builds, installs, grants
  location and microphone, sets Home, swaps the Input key's accessibility service from the old launcher to
  this app (taking it off and back on, see below), turns location providers on for Wi-Fi scans, and saves
  the TV's previous values once in `home/.tv-state` (gitignored). `SET_SAVER=0` skips the screensaver
  setting. `home/rollback.sh` puts the old home, accessibility service and screensaver back
  (`UNINSTALL=1` also removes the app). `home/measure.sh` prints cold start, memory and frame stats.
  `am start -n com.barontv.home/.HomeActivity --ez show_saver true` shows the screensaver at once.
- **Parts:** `home/` glow, bento clock (Open-Meteo weather for Bengaluru, cached), shelf, app dock,
  toast; `side/` the Inputs and TV box with the Wi-Fi view and the Sound bar; `island/` now playing;
  `media/` what's playing; `music/` music mode (edge light, Visualizer beats, the bot); `saver/` the DVD
  screensaver; `input/` the Input key service. Built by three agents in parallel, integrated here.
- **Shelf:** no TMDB key yet, so TVmaze (no key): "New on your apps", episodes from the last 7 days on
  the installed apps (Prime Video, JioHotstar), credited "Data from TVmaze". `tmdb.key=` in
  `home/local.properties` switches it to the prototype's TMDB trending.
- **Dock:** six apps (Netflix isn't installed, so no tile), covers rendered from the prototype's SVGs
  to PNG, other leanback apps after them. Focus starts on YouTube. Focus is colour only, with the fine ring.
- **What this TV is like (found on 2026-10-20, keep in mind for any UI work):**
  - The renderer is Android 9's older OpenGL pipeline (`dumpsys gfxinfo`: Pipeline=FrameBuilder,
    `debug.hwui.renderer=opengl`), not SkiaGL, and Paparazzi doesn't show its limits. An ALPHA_8
    bitmap inside a BitmapShader draws black (the first glow was invisible); `drawBitmap` of an ALPHA_8
    bitmap is tinted fine; gradients with a local matrix work; only basic Porter-Duff blending plus ADD
    and MULTIPLY are safe; no blur.
  - `ro.config.low_ram=true`: notification access doesn't exist (`cmd notification allow_listener`
    exits 0 and does nothing). What's playing comes instead from the Input key's accessibility service:
    a music app's media notification carries its session token, and a MediaController from a token needs
    no permission (`media/SessionTokens.kt`). Android keeps an accessibility service's old event types
    across a reinstall until it's enabled afresh, hence the off-and-on in the install script.
  - No dream service (`dumpsys dreams`: "Can't find service: dreams"), so no screensaver ever ran on this
    TV, the clock one included. The DVD screensaver runs inside the home screen instead: after 10 minutes
    with no key, never while music plays (idle time is music mode's then), first key only wakes it.
  - Wi-Fi scans and the network's name need location on: the install script sets
    `location_providers_allowed` (gps, network); the TV has no GPS, nothing is ever located.
  - The TV reports every input as standby, so HDMI 1 shows no green dot.
  - The firmware force-stops the app you leave whenever Home comes up (logcat: `black_white_list_manager
    forceStopPackage`). Only apps on the keep-alive lists survive: `[keeplive]` in
    `/system/etc/cvte_white_black_list.ini` has YouTube but not Spotify, and the framework also reads
    `/vendor/menu_config_xml/bg_process_keeped_white_list.ini` (absent). Both partitions are read-only;
    writing them needs root and a remount, which can stop the TV booting, so it wasn't done. So music
    never plays behind the home screen, and music mode runs over the music app instead (`OverlayMusic`,
    from the accessibility service: while a music app is in front with music audio active and no key for
    8 s, the same dim, light and bot in a TYPE_ACCESSIBILITY_OVERLAY window; the first key only wakes it).
    It has no session to read, so the light keeps its own colours, and the island never shows (nothing
    plays while the home screen is up). Checked working over Spotify on 2026-10-20.
  - Keys injected through CloudWalker's server (port 5511, the phone app's route) never reach
    accessibility services (keycode 178 sent there doesn't open the source panel). So the home runs a
    bridge for the phone, `input/RemoteBridge.kt` on port 5512 inside the accessibility service:
    `GET /wake` (a phone key was pressed: music mode's idle clock restarts, or it wakes), `GET /input`
    (opens CVTE's source panel), `GET /` ("Baron Home").
- **Keeping the service on:** Android drops an accessibility service from the enabled list when its
  package is force-stopped, and on 2026-10-21 it also dropped ours when a system app was removed, taking
  the bridge, Input key, volume bar and music mode over apps with it (the phone said the new home wasn't
  answering). `input/ServiceGuard.kt` now puts it back at once: when the home or the service starts, and
  whenever the setting changes while the process lives (checked: set to null, back within a second).
  It needs WRITE_SECURE_SETTINGS, a development permission the install script grants with `pm grant`.
  `QUIET=1 home/build-and-install.sh` reinstalls without bringing the home up, so the app in front (and
  its music) isn't force-stopped.
- **The stock launcher is removed** (2026-10-21, with the user's OK): CVTE's TV settings (the Menu key)
  start `tv.cloudwalker.launcher.com` by name when you leave them, past the default Home, and it only
  showed an error screen since the debloat. `pm uninstall -k --user 0`, now at the end of
  `examples/baron-cloudtv-32/debloat.txt`; back with `cmd package install-existing tv.cloudwalker.launcher.com`.
  Still to check: where leaving TV settings lands now.
- **Measured on the TV:** cold start about 0.8 s (0.65 to 1.2 s; 1.4 to 5 s only right after an install
  while the TV compiles), the same as the old launcher. Memory about 58 MB PSS (11 MB of it private).
  Moving focus round the screen: 557 frames, 1.3% janky, p50 6 ms, p90 9 ms. The screensaver runs at 60
  fps with 17 ms frames.
- **The lamp** ("I've a Philips lamp, sync it with the music"): a Philips Smart Wi-Fi colour bulb (WiZ
  inside, module ESP25_SHRGB_01, firmware 1.38, at 192.168.1.x on 2026-10-20). WiZ lamps take JSON over
  UDP port 38899 with no account, but this firmware answered "Invalid params" to every change (reads
  worked, replies were signed) until the user turned on local communication and all controls in the
  WiZ app (Settings > Security). `music/LampSync.kt`: when music mode starts it finds the lamps that are
  on by broadcast, saves what each is doing, flashes them on every kick in the edge light's next colour
  at full brightness and sinks to 25% 160 ms later (at most one command per lamp every 90 ms; a command
  answers in about 20 to 40 ms), and puts each back (scene, colour or white, brightness) when music mode
  ends. A lamp that's off stays off.
- **Bridge, all of `home/BRIDGE.md`** (`input/RemoteBridge.kt`, `bridge/`): volume (read, step, set),
  inputs and switching (the side dock's broadcast), apps and launching, the dock order (re-lays the home's
  dock live), what's playing (the front app, `isMusicActive`, and a title and artist read off the music
  app's screen when it can be: `canRetrieveWindowContent`, walked only on request, `bridge/NowScreen.kt`),
  media keys (`dispatchMediaKeyEvent`), shortcuts. The home's own volume bar (`volume/`): the side dock's
  Sound view in an accessibility overlay at the dock's place, 2 s after the last change, shown for bridge
  changes and for the remote's volume and mute keys, which the accessibility service takes over (flags
  without the system UI) so CVTE's pop-up shouldn't show. Checked on the TV on 2026-10-21: the phone's
  volume (through the bridge) shows the home's bar and no CVTE pop-up, but the physical remote's Vol+ and
  Vol- never reach the home: CVTE's hotkey service in `com.cvte.tv.api.impl` (logcat tag `hotkey`,
  `handleMapKeyEvent`) takes them first, sets the volume and draws its own round pop-up. There's no setting
  to turn that off (only `osd_timer`), so the remote's volume keys keep CVTE's pop-up. Simulate a real
  remote key with `sendevent /dev/input/event0 1 <scan> 1` then `0 0 0`, release likewise (Vol+ 115,
  Vol- 114, Mute 113; Input 79). Steps snap to fives, so a step from 18 lands on 20.
- **Still to check on the TV** (it was off when the tabs and bridge landed): reinstall the home, then the
  bridge endpoints with curl, the remote's volume keys and the bar, `/now` with Spotify in front (its
  screen may read only as a web view, then title and artist stay empty), `/media` in Spotify (if it has no
  session, fall back to keys 85/87/88 through 5511), `/source`, `/dock?order=`; the phone's D-pad edges and
  tabs on a real phone. Also: that the Visualizer hears the beat over Spotify (no "Can't hear the TV"
  warning under logcat tag `BaronMusic` so far) and music mode's frame times; input switching from the
  tiles; which Picture candidate opens (`side/PictureSettings.kt`). Spotify's TV app posts no media
  notification while it's in front, so `SessionTokens` has nothing to read from it.

## Phone app (native, `phone-app/`, 2026-10-20)

The real Android app for the phone prototype ("yes build it, start with the remote"), built Remote first.
Kotlin and Jetpack Compose, AGP 8.5.2, Gradle 8.9 wrapper, compileSdk 34, minSdk 26 (Android 8), package
`com.barontv.remote`, app name "Baron TV". Release APK about 1.7 MB, signed with the debug key like the
launcher (sideloaded, never published).

- **Build and install:** `phone-app/share.sh` builds the release APK and serves it over the Wi-Fi with a
  QR code (`http://<mac-ip>:8090/BaronTV.apk`): scan it on the phone, download, tap to install, and allow
  installs from the browser once. The phone must be on the same Wi-Fi. macOS may ask to allow Python's
  incoming connections. With USB debugging, `adb install -r` works too (adb is in
  `/opt/homebrew/share/android-commandlinetools/platform-tools/`).
- **Checks:** `./gradlew assembleDebug testDebugUnitTest verifyPaparazziDebug assembleRelease` from
  `phone-app/` (JAVA_HOME=/opt/homebrew/opt/openjdk@17, ANDROID_HOME=/opt/homebrew/share/android-commandlinetools).
  56 JVM tests: the pad's gesture rules, subnet maths, the key URL, the search order and state changes
  with fakes, and end to end against a stand-in HTTP server. Paparazzi renders the screen without a
  phone: `recordPaparazziDebug` writes `app/src/test/snapshots/images/` (Connected, Searching, TV not
  found, Not on Wi-Fi on a Pixel 6, Connected on a Nexus 5).
- **Screen (`ui/`):** the prototype's Remote tab, ported value for value from `prototype/phone/`
  (OKLCH colours converted exactly, Open Runde as TTFs in `res/font`, Phosphor as vector drawables from
  `tools/phosphor_to_vector.py`, the pad's squircle corners). No tab bar yet, so the pad takes the height.
  The touch pad works as phone.js: past 10 dp a swipe counts, then every 56 dp is one D-pad step in the
  main direction, a short fast flick sends once, a still tap under 500 ms is OK; cues light, the hint
  fades on the first touch; TalkBack gets Up, Down, Left, Right and OK actions. Keys: Back, Home, Input,
  Menu, the Volume pill (hold to repeat), Mute, Play/Pause, with a slight press-in, no ripples. Haptics
  through `View.performHapticFeedback`, better constants on newer Android (`Haptics.kt`). The header
  shows the TV's name and state; tapping the name shows its address. Not connected, the pad and keys dim
  and a press only buzzes "no"; "TV not found" offers Try again, "Not on Wi-Fi" opens Wi-Fi settings.
- **Connection (`tv/`, `CloudWalkerRemote`):** talks to CloudWalker's phone-remote server, port 5511,
  `GET /input/?keycode=N`. Finding the TV (its IP changes every boot): the last address (TCP connect,
  400 ms), then mDNS `_cloudwalker._tcp` (multicast lock held, each answer re-checked since caches keep
  old addresses), then after 2 s a scan of the phone's /24 as well, 64 at a time, 300 ms each, nearest the
  last address first; first answer wins, nothing in 7 s is "TV not found". Probing is only ever a TCP
  connect: every endpoint of that server is an action. Connected, it checks every 5 s. Presses go in
  order on one worker, a queue of 32 (full drops new presses); a press that can't reach the TV starts a
  new search. Nothing runs in the background (start and stop follow the app being on screen). Logcat
  tag `BaronRemote`.
- **To check on the real TV** (it was off while this was built): that it advertises `_cloudwalker._tcp`
  and the phone resolves an IPv4 address; that the server copes with bare connect-and-close probes; that
  each press answers quickly; the timeouts on the real Wi-Fi; every key. Input (178) is likely ignored:
  injected keys skip the accessibility service that turns Input into CVTE's source panel (see "Keys").
  After a TV reboot the server sleeps until opened once, so the app ends in "TV not found" until then.
- **The home's bridge** (port 5512, the API in `home/BRIDGE.md`): every press also nudges `GET /wake` (at
  most once a second, on its own thread, so a missing bridge never slows a key), and Input asks
  `GET /input` to open the input list, falling back to keycode 178 when there's no bridge (the old
  launcher). `tv/HttpTvBridge.kt` is the client for everything else; with no bridge, launching falls back
  to CloudWalker's `/play?packageName=` and volume to keycodes.
- **Tabs (2026-10-20, four agents in parallel)** after "the D-pad doesn't control the TV properly", "the
  prototype's other sections aren't there", "volume shows the TV's native UI": the shell (`ui/RemoteApp.kt`)
  draws the shared header (name and state) over the chosen tab and the prototype's tab bar (Remote, Apps,
  Playing, TV), remembering the last tab. Remote (`ui/RemoteTab.kt`): the pad is a D-pad as well as a swipe
  pad, since its edge carets looked like buttons but every tap was OK: a tap within 72 dp of an edge sends
  that direction (the middle is OK), holding an edge repeats (first at 420 ms, then every 160 ms, the volume
  keys' clock), swipes as before; volume and mute go through the bridge so the TV shows the home's own bar.
  Apps: the TV's apps as covers, tap to open. Playing: what `/now` gives (the app, playing or not, a title
  and artist only when the TV can read them), previous / play-pause / next, a volume slider that never
  jumps; it polls only while on screen. TV: inputs (tap switches), Picture / Sound / Settings shortcuts, and
  the dock order by dragging, saved on the TV at once.

## Next steps

### To finish this round

1. The user accepts JioHotstar's terms and signs in, and signs in to the other apps if needed.
2. Record the "after" video.
3. When done: ADB ENABLE back to OFF in the factory menu (same path as "Getting ADB"). Until then it
   stays on through reboots and anyone on the Wi-Fi can get a shell. After that, any change needs
   "Getting ADB" again.

### Suggestions, waiting on the user

- **Netflix:** a streaming stick on HDMI (Fire TV Stick or Chromecast with Google TV), reached with the
  Inputs menu or the Input key. Nothing on this TV itself can run Netflix (see above).
- **Dock order:** set in the prototype (see "Home screen redesign"). The TV keeps its old order until the
  redesign is installed.
- **Screensaver:** the DVD one in the prototype (see "Home screen redesign"); the README's Clock
  screensaver is the fallback.
- **A more custom launcher.** The user is a visual designer and wants more customisation. The home screen
  is already our own app, so almost anything can change:
  - Look: layout, colours, wallpaper, fonts, tile shapes, custom icons and banners, the clock and greeting.
  - Structure: which apps sit where, extra rows or pages (a Kids page, say), hidden apps.
  - Extras: weather, a custom screensaver, light animations, branding.
  - Quick changes without a rebuild: colours, wallpaper and app order through a theme's `config.json`
    (`tools/theme.sh`, see `themes/README.md`). Don't push `themes/default`: it replaces the Baron dock
    and hidden apps.
  - Limits: 512 MB rules out video backgrounds, heavy blur and 3D, so keep it light. Rows of content from
    inside other apps ("continue watching" from YouTube or Prime) aren't available on this TV.
  - Workflow: the user sends references, we build them into the localhost prototype, the user approves
    there, then we port to the launcher. See "Home screen redesign".
- **Not possible:** Google's Android TV or any other Android build. There's none for this board, a wrong
  file can brick it, and Google's services need more memory than the TV has.

Reinstall the launcher after a change: `INPUT_KEY_SERVICE=1 TV_HOST=<ip> launcher/build-and-install.sh`
(Home already belongs to it, so nothing else is disabled).

Undo everything: `TV_HOST=<ip> tools/restore.sh examples/baron-cloudtv-32/debloat.txt tv.cloudwalker.launcher.com`.
It doesn't touch Private DNS or the App Store compile: undo those as shown under State.

## Working rules

- UI changes go to the `prototype/` first. Nothing reaches the TV until the user approves it there.
- Work on the real TV and verify each change with key injection and a screenshot. Ask the user before
  removing anything that isn't already in the list, and before anything that changes what's on screen
  while they might be recording.
- One `pm` command per shell call (`tools/tv.py --batch` does this).
- The user wants short replies, no em dashes anywhere (code and comments included), an explicit yes
  before every push, and no AI attribution lines in commits. Standing yes for this file: keep it
  updated and pushed to GitHub as work progresses, so either Mac can pick up where the other left off.
- Commit as `sethihq <72229933+sethihq@users.noreply.github.com>`, the only address in the public history.
  Set it once per clone: `git config user.name sethihq` and
  `git config user.email 72229933+sethihq@users.noreply.github.com`.
