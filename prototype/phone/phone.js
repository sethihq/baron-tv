// Baron TV remote, phone prototype: a companion app for the Baron TV. Remote is a touch pad plus the
// keys of the TV's own remote; Apps opens the TV's apps; Now playing shows and controls Spotify on the
// TV; TV switches inputs, opens the TV's menus and sets how the home screen looks. Nothing here reaches
// the TV: the line above the tabs says what the TV would do.
//
// How each action would reach the real TV:
// - Keys go to CloudWalker's phone-remote server on the TV, which needs no pairing:
//   GET http://<tv>:5511/input/?keycode=N, with Up 19, Down 20, Left 21, Right 22, OK 23, Back 4,
//   Home 3, Menu 82, Input 178, Volume up 24, Volume down 25, Mute 164 and Play/Pause 85. That server
//   comes back stopped after the TV restarts, until something opens it once (see CLAUDE.md).
// - Apps, now playing, inputs and the home settings go to a small LAN server the launcher would add.
//   It lists the TV's apps and opens them (the phone-remote server's /play?packageName= opens one
//   too), reads and controls the TV's media session, switches inputs with CVTE's sourceId broadcast,
//   and writes the theme config.json the launcher already reads.

const KEYS = {
  up: { code: 19, says: "Up" },
  down: { code: 20, says: "Down" },
  left: { code: 21, says: "Left" },
  right: { code: 22, says: "Right" },
  ok: { code: 23, says: "OK" },
  back: { code: 4, says: "Back" },
  home: { code: 3, says: "Home" },
  menu: { code: 82, says: "Menu" },
  input: { code: 178, says: "Input" },
  volup: { code: 24, says: "Volume up" },
  voldown: { code: 25, says: "Volume down" },
  mute: { code: 164, says: "Mute" },
  playpause: { code: 85, says: "Play/Pause" },
};

// The TV's dock order as the user set it on the TV prototype. Netflix can't run on this TV.
const APPS = [
  { name: "Prime Video", banner: "../assets/apps/primevideo-banner.png", pkg: "com.amazon.amazonvideo.livingroom" },
  { name: "Netflix", banner: "../assets/apps/netflix-banner.svg", pkg: "", note: "Not on this TV" },
  { name: "YouTube", banner: "../assets/apps/youtube-banner.png", pkg: "com.google.android.youtube.tv" },
  { name: "JioHotstar", banner: "../assets/apps/jiohotstar-banner.png", pkg: "in.startv.hotstar" },
  { name: "Spotify", banner: "../assets/apps/spotify-banner.png", pkg: "com.spotify.tv.android" },
  { name: "App Store", banner: "../assets/apps/appstore-banner.png", pkg: "tv.cloudwalker.market" },
  { name: "Media Center", banner: "../assets/apps/media-banner.png", pkg: "com.cvte.tv.media" },
];

// sourceId is the number CVTE's input switch takes (see CLAUDE.md, "Inputs").
const INPUTS = [
  { name: "HDMI 1", icon: "plugs-connected", state: "Connected", live: true, source: 4 },
  { name: "HDMI 2", icon: "plug", state: "Not connected", live: false, source: 5 },
  { name: "AV", icon: "television-simple", state: "Not connected", live: false, source: 3 },
];

const SHORTCUTS = [
  { name: "Picture", icon: "image", says: "Opening the picture menu on the TV" },
  { name: "Sound", icon: "speaker-high", says: "Opening the sound menu on the TV" },
  { name: "Settings", icon: "gear-six", says: "Opening Settings on the TV" },
];

// A made-up queue, the same one the TV prototype's island plays. Covers and lengths come from Apple's
// public music search when the page loads; nothing is stored, and a drawn cover stands in offline.
const SONGS = [
  { title: "Blinding Lights", artist: "The Weeknd", q: "Blinding Lights The Weeknd", ms: 200000 },
  { title: "Kesariya", artist: "Arijit Singh", q: "Kesariya Arijit Singh", ms: 268000 },
  { title: "Espresso", artist: "Sabrina Carpenter", q: "Espresso Sabrina Carpenter", ms: 175000 },
  { title: "Levitating", artist: "Dua Lipa", q: "Levitating Dua Lipa", ms: 203000 },
  { title: "With You", artist: "AP Dhillon", q: "With You AP Dhillon", ms: 196000 },
];

const ORDINALS = ["first", "second", "third", "fourth", "fifth", "sixth", "seventh"];
const STORE_KEY = "baron-tv-phone-v1";
const DEVICE_W = 432;
const DEVICE_H = 912;

// Touch pad feel, in phone pixels.
const SLOP = 10; // movement before a touch counts as a swipe
const STEP = 56; // travel per D-pad step while the finger keeps going
const FLICK = 0.45; // px per ms: a short quick swipe still moves one step
const TAP_MS = 500;

const ROW = 56; // dock list row height

const $ = (id) => document.getElementById(id);
const device = $("device");
const pad = $("pad");
const padHint = $("padHint");
const touchEl = $("touch");
const statusEl = $("status");
const appGrid = $("appGrid");
const dockList = $("dockList");
const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)");
const clamp = (n, lo, hi) => Math.max(lo, Math.min(n, hi));

let fitScale = 1;

// ---- Saved choices: per viewer, and only conveniences ---------------------------------------------

function load() {
  try {
    return JSON.parse(localStorage.getItem(STORE_KEY)) || {};
  } catch {
    return {};
  }
}

const saved = load();
const prefs = {
  tab: saved.tab || "remote",
  dock: Array.isArray(saved.dock) && saved.dock.length === APPS.length && APPS.every((a) => saved.dock.includes(a.name)) ? saved.dock : APPS.map((a) => a.name),
  glow: saved.glow || "refined",
  island: saved.island || "black",
};

function save() {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(prefs));
  } catch {
    // Private window or blocked storage: nothing is remembered, everything still works.
  }
}

// ---- Icons ----------------------------------------------------------------------------------------

function glyph(icon, weight) {
  const span = document.createElement("span");
  span.className = "glyph";
  span.dataset.icon = icon;
  if (weight) span.dataset.weight = weight;
  span.setAttribute("aria-hidden", "true");
  return span;
}

function paintGlyphs(root = document) {
  for (const g of root.querySelectorAll(".glyph")) {
    g.style.setProperty("--src", `url("../assets/icons/phosphor/${g.dataset.weight || "regular"}/${g.dataset.icon}.svg")`);
  }
}

// ---- What the TV would do --------------------------------------------------------------------------

let sayTimer = 0;
function say(text) {
  statusEl.textContent = text;
  statusEl.classList.add("show");
  clearTimeout(sayTimer);
  sayTimer = setTimeout(() => statusEl.classList.remove("show"), 1600);
}

// ---- Haptics ---------------------------------------------------------------------------------------------

// One helper with named patterns, as a polished remote does it: the lighter the action, the lighter
// the tick, and only actions that commit something get more than one pulse. navigator.vibrate works
// in Android Chrome; elsewhere it's missing or ignored, so every call is guarded. Each pattern comes
// with a small visible response (pulse()), so desktop review still shows the feedback, on the same
// frame as the vibration.
//
// In the native build (Kotlin, Jetpack Compose) each name maps to View.performHapticFeedback with a
// HapticFeedbackConstants value, or to LocalHapticFeedback.current.performHapticFeedback with the
// matching HapticFeedbackType (Compose 1.8 and later has most of these; older versions only have
// LongPress and TextHandleMove, so fall back to the View call):
//   tick     a D-pad step from the touch pad    CLOCK_TICK                     (TextHandleMove)
//   tap      OK, Back, Home, Input, Menu, media VIRTUAL_KEY                    (VirtualKey)
//   repeat   each volume step while held        KEYBOARD_TAP                   (KeyboardTap)
//   confirm  an app opening on the TV           CONFIRM, API 30+               (Confirm)
//   reject   an app that can't run on this TV   REJECT, API 30+                (Reject)
//   lift     picking up a dock row              GESTURE_THRESHOLD_ACTIVATE, API 34+, else LONG_PRESS
//   slot     the dragged row passing a slot,    SEGMENT_TICK, API 34+, else CLOCK_TICK (SegmentTick)
//            and each tenth of the volume slider
//   select   a tab or a picker choice           SEGMENT_FREQUENT_TICK, API 34+ (SegmentFrequentTick)
const HAPTICS = {
  tick: 6,
  tap: 10,
  repeat: 5,
  confirm: [12, 60, 18],
  reject: [8, 50, 8, 50, 8],
  lift: 14,
  slot: 4,
  select: 7,
};

const EASE_OUT = "cubic-bezier(0.23, 1, 0.32, 1)";

// The visible half of a haptic: a brief, small press on the element that caused it. Reject shakes a
// little instead, as a wrong passcode does. With reduced motion, a short dim stands in for movement.
function pulse(el, kind) {
  if (!el) return;
  if (reduceMotion.matches) {
    el.animate([{ opacity: 0.7 }, { opacity: 1 }], { duration: 180, easing: "ease-out" });
  } else if (kind === "reject") {
    el.animate([{ translate: "0 0" }, { translate: "-3px 0" }, { translate: "3px 0" }, { translate: "-2px 0" }, { translate: "0 0" }], { duration: 260, easing: "ease-in-out" });
  } else {
    el.animate([{ scale: kind === "repeat" || kind === "slot" ? 0.97 : 0.95 }, { scale: 1 }], { duration: 180, easing: EASE_OUT });
  }
}

function haptic(kind, el) {
  try {
    if (navigator.vibrate) navigator.vibrate(HAPTICS[kind]);
  } catch {
    // Not allowed here (no user gesture yet, or a frame without permission): no vibration.
  }
  pulse(el, kind);
}

// On the TV: fetch(`http://${tv}:5511/input/?keycode=${KEYS[key].code}`).
function send(key) {
  say(`${KEYS[key].says} sent to the TV`);
}

// ---- Remote: the touch pad ---------------------------------------------------------------------------

const NUDGE = { up: "0 -4px", down: "0 4px", left: "-4px 0", right: "4px 0" };

function flashCue(dir) {
  const cue = pad.querySelector(`.cue-${dir}`);
  const from = reduceMotion.matches ? { opacity: 0.7 } : { opacity: 0.7, translate: NUDGE[dir] };
  cue.animate([from, { opacity: 0.28, translate: "0 0" }], { duration: 420, easing: "cubic-bezier(0.23, 1, 0.32, 1)" });
}

function step(dir) {
  send(dir);
  flashCue(dir);
  haptic("tick");
}

function tap(p) {
  send("ok");
  haptic("tap");
  const ring = document.createElement("span");
  ring.className = "ripple";
  ring.style.translate = `${p.x}px ${p.y}px`;
  pad.append(ring);
  const frames = reduceMotion.matches ? [{ opacity: 0.45 }, { opacity: 0 }] : [{ scale: 0.45, opacity: 0.5 }, { scale: 1, opacity: 0 }];
  ring.animate(frames, { duration: 420, easing: "cubic-bezier(0.23, 1, 0.32, 1)" }).onfinish = () => ring.remove();
}

function hideHint() {
  padHint.classList.add("gone");
}

// The pad's coordinates in phone pixels, whatever the device frame is scaled to.
function toPad(e) {
  const r = pad.getBoundingClientRect();
  return { x: (e.clientX - r.left) / fitScale, y: (e.clientY - r.top) / fitScale, t: performance.now() };
}

function dirOf(dx, dy) {
  return Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "right" : "left") : dy > 0 ? "down" : "up";
}

let touch = null;

pad.addEventListener("pointerdown", (e) => {
  if (touch) return; // a second finger changes nothing
  pad.setPointerCapture(e.pointerId);
  const p = toPad(e);
  touch = { id: e.pointerId, start: p, origin: p, moved: false, stepped: false, recent: [p] };
  touchEl.style.translate = `${p.x}px ${p.y}px`;
  touchEl.classList.add("on");
  pad.classList.add("pressed");
  hideHint();
});

pad.addEventListener("pointermove", (e) => {
  if (!touch || e.pointerId !== touch.id) return;
  const p = toPad(e);
  touch.recent.push(p);
  if (touch.recent.length > 6) touch.recent.shift();
  touchEl.style.translate = `${p.x}px ${p.y}px`;
  if (!touch.moved && Math.hypot(p.x - touch.start.x, p.y - touch.start.y) > SLOP) touch.moved = true;
  if (!touch.moved) return;
  const dx = p.x - touch.origin.x;
  const dy = p.y - touch.origin.y;
  if (Math.max(Math.abs(dx), Math.abs(dy)) >= STEP) {
    step(dirOf(dx, dy));
    touch.stepped = true;
    touch.origin = p;
  }
});

function endTouch(e, cancelled) {
  if (!touch || e.pointerId !== touch.id) return;
  const p = toPad(e);
  if (!cancelled) {
    if (!touch.moved && p.t - touch.start.t < TAP_MS) {
      tap(p);
    } else if (touch.moved && !touch.stepped) {
      // Too short for a step, but quick: the flick still counts once.
      const a = touch.recent[0];
      const dt = Math.max(1, p.t - a.t);
      const speed = Math.hypot(p.x - a.x, p.y - a.y) / dt;
      const dx = p.x - touch.start.x;
      const dy = p.y - touch.start.y;
      if (speed > FLICK || Math.hypot(dx, dy) > STEP * 0.6) step(dirOf(dx, dy));
    }
  }
  touchEl.classList.remove("on");
  pad.classList.remove("pressed");
  touch = null;
}

pad.addEventListener("pointerup", (e) => endTouch(e, false));
pad.addEventListener("pointercancel", (e) => endTouch(e, true));

pad.addEventListener("keydown", (e) => {
  const dir = { ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right" }[e.key];
  if (dir) {
    e.preventDefault();
    hideHint();
    step(dir);
  } else if (e.key === "Enter" || e.key === " ") {
    e.preventDefault();
    hideHint();
    tap({ x: pad.offsetWidth / 2, y: pad.offsetHeight / 2 });
  }
});

// ---- Remote: keys. Volume repeats while held, as the TV's own remote does. --------------------------

for (const button of document.querySelectorAll("[data-key]")) {
  const key = button.dataset.key;
  if (button.hasAttribute("data-repeat")) {
    let hold = 0;
    let again = 0;
    const stop = () => {
      clearTimeout(hold);
      clearInterval(again);
    };
    const glyphEl = button.querySelector(".glyph");
    button.addEventListener("pointerdown", () => {
      send(key);
      haptic("tap", glyphEl);
      hold = setTimeout(() => {
        again = setInterval(() => {
          send(key);
          haptic("repeat", glyphEl);
        }, 160);
      }, 420);
    });
    for (const type of ["pointerup", "pointerleave", "pointercancel"]) button.addEventListener(type, stop);
    // Keyboard and switch access: one step per press.
    button.addEventListener("click", (e) => {
      if (e.detail !== 0) return;
      send(key);
      haptic("tap", glyphEl);
    });
  } else {
    button.addEventListener("click", () => {
      send(key);
      haptic("tap", button.querySelector(".key-face") || button);
    });
  }
}

// ---- Apps -------------------------------------------------------------------------------------------

const appByName = (name) => APPS.find((a) => a.name === name);

function bannerImg(src) {
  const img = new Image();
  img.alt = "";
  img.decoding = "async";
  img.src = src;
  return img;
}

// On the TV: GET http://<tv>:5511/play?packageName=<pkg>, or the launcher's own server.
function renderApps() {
  appGrid.replaceChildren(
    ...prefs.dock.map((name) => {
      const app = appByName(name);
      const tile = document.createElement("button");
      tile.type = "button";
      tile.className = "app-tile";
      tile.setAttribute("aria-label", app.note ? `${app.name}, ${app.note.toLowerCase()}` : `Open ${app.name} on the TV`);
      const card = document.createElement("span");
      card.className = "app-card";
      card.append(bannerImg(app.banner));
      const label = document.createElement("span");
      label.className = "app-name";
      label.textContent = app.name;
      tile.append(card, label);
      if (app.note) {
        const note = document.createElement("span");
        note.className = "app-note";
        note.textContent = app.note;
        tile.append(note);
      }
      tile.addEventListener("click", () => {
        haptic(app.pkg ? "confirm" : "reject", card);
        say(app.pkg ? `Opening ${app.name} on the TV` : `${app.name} can't run on this TV`);
      });
      return tile;
    }),
  );
}

// ---- Now playing --------------------------------------------------------------------------------------

const view = document.querySelector(".view-playing");
const npArt = $("npArt");
const npToggle = $("npToggle");
const np = { i: 0, at: 61000, playing: true };

function drawnCover(i) {
  const canvas = document.createElement("canvas");
  canvas.width = 300;
  canvas.height = 300;
  const ctx = canvas.getContext("2d");
  const hue = [350, 28, 205, 300, 160][i % 5];
  const g = ctx.createLinearGradient(0, 0, 300, 300);
  g.addColorStop(0, `hsl(${hue} 70% 52%)`);
  g.addColorStop(1, `hsl(${(hue + 40) % 360} 65% 22%)`);
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 300, 300);
  const r = ctx.createRadialGradient(90, 80, 10, 90, 80, 180);
  r.addColorStop(0, "rgb(255 255 255 / 0.28)");
  r.addColorStop(1, "rgb(255 255 255 / 0)");
  ctx.fillStyle = r;
  ctx.fillRect(0, 0, 300, 300);
  return canvas.toDataURL("image/png");
}

SONGS.forEach((s, i) => {
  s.cover = drawnCover(i);
});

async function loadCovers() {
  await Promise.all(
    SONGS.map(async (s) => {
      try {
        const url = new URL("https://itunes.apple.com/search");
        url.search = new URLSearchParams({ term: s.q, entity: "song", limit: "1", country: "in" });
        const res = await fetch(url);
        if (!res.ok) return;
        const hit = (await res.json()).results?.[0];
        if (!hit) return;
        s.cover = hit.artworkUrl100.replace("100x100bb", "600x600bb");
        if (hit.trackTimeMillis) s.ms = hit.trackTimeMillis;
      } catch {
        // Offline: the drawn cover stays.
      }
    }),
  );
  showSong(false);
}

const mmss = (ms) => {
  const s = Math.max(0, Math.round(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};

function paintProgress(jump) {
  const s = SONGS[np.i];
  const fill = $("npFill");
  if (jump) fill.style.transition = "none";
  fill.style.scale = `${Math.min(1, np.at / s.ms)} 1`;
  if (jump) {
    fill.getBoundingClientRect();
    fill.style.transition = "";
  }
  $("npAt").textContent = mmss(np.at);
  $("npLeft").textContent = `-${mmss(s.ms - np.at)}`;
}

function showSong(fadeIn) {
  const s = SONGS[np.i];
  $("npTitle").textContent = s.title;
  $("npArtist").textContent = s.artist;
  if (npArt.getAttribute("src") !== s.cover) {
    npArt.src = s.cover;
    if (fadeIn && !reduceMotion.matches) {
      npArt.addEventListener("load", () => npArt.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 260, easing: "ease-out" }), { once: true });
    }
  }
  paintProgress(true);
}

function setPlaying(on) {
  np.playing = on;
  view.toggleAttribute("data-paused", !on);
  npToggle.setAttribute("aria-label", on ? "Pause" : "Play");
  const g = npToggle.querySelector(".glyph");
  g.dataset.icon = on ? "pause" : "play";
  paintGlyphs(npToggle);
}

function nextSong(by) {
  np.i = (np.i + by + SONGS.length) % SONGS.length;
  np.at = 0;
  showSong(true);
}

// On the TV: the launcher's server calls the media session's play, pause, skip to next or previous.
for (const button of document.querySelectorAll(".np-btn")) {
  button.addEventListener("click", () => {
    const act = button.dataset.act;
    if (act === "toggle") {
      setPlaying(!np.playing);
      say(np.playing ? "Playing on the TV" : "Paused on the TV");
    } else if (act === "next") {
      nextSong(1);
      say("Next song on the TV");
    } else if (np.at > 3000) {
      np.at = 0;
      paintProgress(true);
      say("Back to the start on the TV");
    } else {
      nextSong(-1);
      say("Previous song on the TV");
    }
    haptic("tap", button.querySelector(".glyph"));
  });
}

setInterval(() => {
  if (!np.playing) return;
  np.at += 1000;
  if (np.at >= SONGS[np.i].ms) nextSong(1);
  else paintProgress(false);
}, 1000);

// The glow takes the cover's most vivid colour, picked the way the TV's glow does it, then set at one
// lightness with its chroma capped, so no cover is louder than another.
function rgbToHsv(r, g, b) {
  const max = Math.max(r, g, b) / 255;
  const min = Math.min(r, g, b) / 255;
  const d = max - min;
  let h = 0;
  if (d > 0) {
    if (max === r / 255) h = 60 * (((g - b) / 255 / d) % 6);
    else if (max === g / 255) h = 60 * ((b - r) / 255 / d + 2);
    else h = 60 * ((r - g) / 255 / d + 4);
  }
  return [(h + 360) % 360, max === 0 ? 0 : d / max, max];
}

function vividColor(img) {
  try {
    const n = 12;
    const canvas = document.createElement("canvas");
    canvas.width = n;
    canvas.height = n;
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    ctx.drawImage(img, 0, 0, n, n);
    const px = ctx.getImageData(0, 0, n, n).data;
    let best = [68, 68, 68];
    let bestScore = -1;
    for (let i = 0; i < px.length; i += 4) {
      const [, s, v] = rgbToHsv(px[i], px[i + 1], px[i + 2]);
      const score = s * Math.min(1, v * 1.4);
      if (score > bestScore) {
        bestScore = score;
        best = [px[i], px[i + 1], px[i + 2]];
      }
    }
    return best;
  } catch {
    return [68, 68, 68];
  }
}

function toOklch([r, g, b]) {
  const lin = (c) => {
    const v = c / 255;
    return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  const [R, G, B] = [lin(r), lin(g), lin(b)];
  const l = Math.cbrt(0.4122214708 * R + 0.5363325363 * G + 0.0514459929 * B);
  const m = Math.cbrt(0.2119034982 * R + 0.6806995451 * G + 0.1073969566 * B);
  const s = Math.cbrt(0.0883024619 * R + 0.2817188376 * G + 0.6299787005 * B);
  const L = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s;
  const a = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s;
  const bb = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s;
  return [L, Math.hypot(a, bb), ((Math.atan2(bb, a) * 180) / Math.PI + 360) % 360];
}

npArt.addEventListener("load", () => {
  const [, c, h0] = toOklch(vividColor(npArt));
  const h = h0 > 75 && h0 < 120 ? 68 : h0; // yellows lean to amber: dark yellow on black reads as olive
  const chroma = c < 0.03 ? 0 : Math.min(c, 0.16);
  const glow = $("npGlow");
  glow.style.setProperty("--np-1", `oklch(54% ${chroma.toFixed(3)} ${h.toFixed(1)} / 0.4)`);
  glow.style.setProperty("--np-2", `oklch(44% ${(chroma * 0.9).toFixed(3)} ${h.toFixed(1)} / 0.15)`);
});

// ---- Now playing: TV volume -----------------------------------------------------------------------------

// On the TV: the launcher's server sets the music stream's volume through AudioManager. The
// phone-remote server alone can only step it, with key codes 24 and 25.
const volume = $("npVolume");
const volFill = $("volFill");
let vol = 40;

function setVolume(v, announce) {
  const before = vol;
  vol = clamp(Math.round(v), 0, 100);
  if (announce && Math.floor(before / 10) !== Math.floor(vol / 10)) haptic("slot");
  volFill.style.scale = `${vol / 100} 1`;
  volume.setAttribute("aria-valuenow", String(vol));
  if (announce) say(`TV volume ${vol}`);
}

// The value follows the finger from where it was, so touching the track never makes it jump.
let held = null;
volume.addEventListener("pointerdown", (e) => {
  volume.setPointerCapture(e.pointerId);
  held = { id: e.pointerId, x0: e.clientX, v0: vol, width: volume.querySelector(".vol-track").getBoundingClientRect().width };
  volume.classList.add("held");
});
volume.addEventListener("pointermove", (e) => {
  if (!held || e.pointerId !== held.id) return;
  setVolume(held.v0 + ((e.clientX - held.x0) / held.width) * 100, true);
});
for (const type of ["pointerup", "pointercancel"]) {
  volume.addEventListener(type, (e) => {
    if (!held || e.pointerId !== held.id) return;
    held = null;
    volume.classList.remove("held");
  });
}
volume.addEventListener("keydown", (e) => {
  const by = { ArrowRight: 5, ArrowUp: 5, ArrowLeft: -5, ArrowDown: -5 }[e.key];
  if (!by) return;
  e.preventDefault();
  setVolume(vol + by, true);
});

// ---- TV: inputs and shortcuts ----------------------------------------------------------------------

function tileFor(item, onPress) {
  const tile = document.createElement("button");
  tile.type = "button";
  tile.className = "tile";
  if (item.live === false) tile.dataset.idle = "";
  const text = document.createElement("span");
  const name = document.createElement("span");
  name.className = "tile-name";
  name.textContent = item.name;
  text.append(name);
  if (item.state) {
    const state = document.createElement("span");
    state.className = "tile-state";
    state.textContent = item.state;
    text.append(state);
  }
  tile.append(glyph(item.icon), text);
  tile.addEventListener("click", () => {
    onPress();
    haptic("tap", tile);
  });
  return tile;
}

// On the TV: the launcher sends CVTE's tune broadcast with sourceId, twice (see CLAUDE.md).
$("inputTiles").replaceChildren(
  ...INPUTS.map((input) => tileFor(input, () => say(input.live ? `Switching the TV to ${input.name}` : `Nothing is plugged into ${input.name}`))),
);
$("shortcutTiles").replaceChildren(...SHORTCUTS.map((s) => tileFor(s, () => say(s.says))));

// ---- TV: dock order, dragged by the handle or moved with the arrow keys ---------------------------------

function renderDockList(focusName) {
  dockList.replaceChildren(
    ...prefs.dock.map((name) => {
      const app = appByName(name);
      const row = document.createElement("li");
      row.className = "dock-row";
      const thumb = document.createElement("span");
      thumb.className = "dock-thumb";
      thumb.append(bannerImg(app.banner));
      const label = document.createElement("span");
      label.className = "dock-name";
      label.textContent = name;
      const handle = document.createElement("button");
      handle.type = "button";
      handle.className = "dock-handle";
      handle.setAttribute("aria-label", `Move ${name}. Arrow up or down moves it one place.`);
      handle.append(glyph("dots-six-vertical"));
      handle.addEventListener("pointerdown", (e) => startDrag(e, row, handle));
      handle.addEventListener("keydown", (e) => {
        if (e.key !== "ArrowUp" && e.key !== "ArrowDown") return;
        e.preventDefault();
        haptic("slot");
        moveApp(name, prefs.dock.indexOf(name) + (e.key === "ArrowUp" ? -1 : 1), true);
      });
      row.append(thumb, label, handle);
      return row;
    }),
  );
  paintGlyphs(dockList);
  if (focusName) dockList.children[prefs.dock.indexOf(focusName)].querySelector(".dock-handle").focus();
}

// On the TV: the launcher's server writes dock_apps into the theme's config.json and Home reloads.
function moveApp(name, to, fromKeyboard) {
  const from = prefs.dock.indexOf(name);
  to = clamp(to, 0, prefs.dock.length - 1);
  if (to !== from) {
    prefs.dock.splice(to, 0, prefs.dock.splice(from, 1)[0]);
    save();
    renderApps();
  }
  renderDockList(fromKeyboard ? name : null);
  say(`${name} is now ${ORDINALS[to]} in the TV's dock`);
}

// Past the first or last place the row still follows, with more and more resistance.
const rubber = (over) => (over * 140 * 0.55) / (140 + 0.55 * over);

let drag = null;

function startDrag(e, row, handle) {
  if (drag) return;
  e.preventDefault();
  handle.setPointerCapture(e.pointerId);
  const rows = [...dockList.children];
  drag = { id: e.pointerId, row, handle, rows, from: rows.indexOf(row), to: rows.indexOf(row), y0: e.clientY };
  row.classList.add("lifted");
  haptic("lift");
  handle.addEventListener("pointermove", onDrag);
  handle.addEventListener("pointerup", endDrag);
  handle.addEventListener("pointercancel", endDrag);
}

function onDrag(e) {
  if (!drag || e.pointerId !== drag.id) return;
  const last = drag.rows.length - 1;
  const min = -drag.from * ROW;
  const max = (last - drag.from) * ROW;
  let dy = (e.clientY - drag.y0) / fitScale;
  if (dy < min) dy = min - rubber(min - dy);
  else if (dy > max) dy = max + rubber(dy - max);
  drag.row.style.translate = `0 ${dy}px`;
  const to = clamp(Math.round(drag.from + dy / ROW), 0, last);
  if (to === drag.to) return;
  drag.to = to;
  drag.rows.forEach((other, i) => {
    if (other === drag.row) return;
    let shift = 0;
    if (drag.from < to && i > drag.from && i <= to) shift = -ROW;
    if (drag.from > to && i < drag.from && i >= to) shift = ROW;
    other.style.translate = shift ? `0 ${shift}px` : "";
  });
  haptic("slot");
}

function endDrag(e) {
  if (!drag || e.pointerId !== drag.id) return;
  const { row, handle, from, to } = drag;
  handle.removeEventListener("pointermove", onDrag);
  handle.removeEventListener("pointerup", endDrag);
  handle.removeEventListener("pointercancel", endDrag);
  drag = null;
  // Settle into the new place, then commit the order.
  row.classList.remove("lifted");
  row.style.translate = `0 ${(to - from) * ROW}px`;
  const name = prefs.dock[from];
  haptic("tap");
  setTimeout(() => moveApp(name, to, false), reduceMotion.matches ? 0 : 210);
}

// ---- TV: how the home screen looks ---------------------------------------------------------------------

// On the TV: the launcher's server writes these into the theme's config.json.
const GLOW_SAYS = { refined: "Refined glow on the TV", original: "Original glow on the TV", off: "Glow turned off on the TV" };
const ISLAND_SAYS = { black: "Black island on the TV", grey: "Grey island on the TV" };

function bindPick(id, key, says) {
  const group = $(id);
  const current = group.querySelector(`input[value="${prefs[key]}"]`);
  if (current) current.checked = true;
  group.addEventListener("change", (e) => {
    prefs[key] = e.target.value;
    save();
    haptic("select", e.target.closest("label"));
    say(says[e.target.value]);
  });
}

bindPick("glowPick", "glow", GLOW_SAYS);
bindPick("islandPick", "island", ISLAND_SAYS);

$("tvPick").addEventListener("click", () => {
  haptic("tap", $("tvPick"));
  say("This is the only TV on your Wi-Fi");
});

// ---- Tabs, with the prototype's Screen control kept in step ----------------------------------------------

const tabs = [...document.querySelectorAll(".tab")];
const views = [...document.querySelectorAll(".view")];
const screenSeg = $("screenSeg");

function showTab(name, focus, felt) {
  if (felt && name !== prefs.tab) {
    const icon = tabs.find((t) => t.dataset.tab === name)?.querySelector(".tab-icon");
    haptic("select", icon);
  }
  prefs.tab = name;
  save();
  $("phone").dataset.tab = name;
  for (const tab of tabs) {
    const on = tab.dataset.tab === name;
    tab.setAttribute("aria-selected", String(on));
    tab.tabIndex = on ? 0 : -1;
    tab.querySelector(".glyph").dataset.weight = on ? "fill" : "regular";
    if (on && focus) tab.focus();
  }
  paintGlyphs(document.querySelector(".tabs"));
  for (const v of views) v.hidden = v.dataset.view !== name;
  $("views").scrollTop = 0;
  const radio = screenSeg.querySelector(`input[value="${name}"]`);
  if (radio) radio.checked = true;
}

tabs.forEach((tab, i) => {
  tab.addEventListener("click", () => showTab(tab.dataset.tab, false, true));
  tab.addEventListener("keydown", (e) => {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    e.preventDefault();
    const next = tabs[(i + (e.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length];
    showTab(next.dataset.tab, true, true);
  });
});

screenSeg.addEventListener("change", (e) => showTab(e.target.value));

// ---- Status bar clock, and fitting the handset to the window ---------------------------------------------

function tick() {
  const now = new Date();
  $("sysTime").textContent = `${now.getHours() % 12 || 12}:${String(now.getMinutes()).padStart(2, "0")}`;
}

function fit() {
  const chrome = document.querySelector(".bar").offsetHeight + document.querySelector(".hint").offsetHeight;
  const s = Math.min(1, (innerHeight - chrome - 32) / DEVICE_H, (innerWidth - 48) / DEVICE_W);
  fitScale = Math.max(0.5, s);
  device.style.setProperty("--fit", fitScale.toFixed(3));
}

addEventListener("resize", fit);

renderApps();
renderDockList();
setVolume(vol, false);
setPlaying(true);
showSong(false);
loadCovers();
paintGlyphs();
showTab(prefs.tab);
tick();
setInterval(tick, 15000);
fit();
