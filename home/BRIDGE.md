# The home's bridge for the phone app

`com.barontv.home` serves plain HTTP on **port 5512** of the TV (inside its accessibility service, so it
runs whenever the home is installed and its service is on). The phone keeps sending key presses to
CloudWalker's own server (port 5511, `GET /input/?keycode=N`); everything else goes here. Every reply is
`200` with a JSON body unless noted; an unknown path is `404`. No pairing (same as CloudWalker's server):
nothing here reads anything private, and the actions are what the remote can already do.

| Request | Reply | What it does |
|---|---|---|
| `GET /` | `Baron Home` (text) | Says the bridge is there. |
| `GET /wake` | `OK` (text) | A key was pressed on the phone: music mode's idle clock restarts, or it wakes. |
| `GET /input` | `OK` (text) | Opens CVTE's source panel, as the remote's Input key does. |
| `GET /volume` | `{"level":35,"muted":false}` | The TV's media volume, 0 to 100. |
| `GET /volume?step=up` (`down`, `mute`) | same as above, after the change | Moves it by 5% (mute toggles), and shows the home's own volume bar on the TV, never CVTE's. |
| `GET /volume?set=40` | same | Sets it (the phone's slider), showing the home's volume bar. |
| `GET /inputs` | `[{"name":"HDMI 1","live":true},{"name":"HDMI 2","live":false},{"name":"AV","live":false}]` | The TV's inputs and whether something is plugged in (this TV reports every port as standby, so `live` may be false for all). |
| `GET /source?name=HDMI%201` | `{"ok":true}` | Switches to that input (CVTE's broadcast, as the side dock does). |
| `GET /apps` | `[{"name":"YouTube","package":"com.google.android.youtube.tv","pinned":true}, ...]` | The installed TV apps in the home's dock order: pinned ones first, then the rest. |
| `GET /launch?package=com.spotify.tv.android` | `{"ok":true}` | Opens that app on the TV. |
| `GET /dock` | `["com.amazon.amazonvideo.livingroom","com.google.android.youtube.tv", ...]` | The dock order. |
| `GET /dock?order=pkgA,pkgB,pkgC` | `{"ok":true}` | Saves a new dock order; the home re-lays its app dock at once. Unknown or uninstalled packages are ignored. |
| `GET /now` | `{"app":"com.spotify.tv.android","appName":"Spotify","playing":true,"title":"","artist":"","positionMs":-1,"durationMs":-1}` or `{}` when nothing plays | What's playing, best effort. This TV can't read media sessions (low-RAM Android), so `title` and `artist` come from what the music app shows on screen when it can be read, else they're empty, and the times are -1 when unknown. |
| `GET /media?action=playpause` (`next`, `previous`) | `{"ok":true}` | Sends that media key to whatever is playing. |
| `GET /shortcut?name=picture` (`sound`, `settings`) | `{"ok":true}` | Opens the TV's picture settings, the home's volume bar, or Settings. |

The phone tells the bridge apart from an old launcher by `GET /` answering `Baron Home`; without the
bridge it falls back to key presses (volume keys 24/25/164, Input 178) and to CloudWalker's
`/play?packageName=` for opening apps, and hides what it can't do.
