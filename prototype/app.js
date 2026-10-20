// Baron TV home prototype. Page 1: clock, round controls, what's trending on your apps, the dock.
// Page 2, under the dock: inputs, shortcuts and the Bengaluru weather. Arrow keys are the D-pad,
// Enter or a click is OK, Esc or Backspace is Back. Nothing here talks to the TV; the weather comes
// from Open-Meteo and the shelf from TMDB, as the launcher would fetch them.

// Dock order as the user set it, then the TV's own apps. The tiles are always covers ("tiles should
// always be covers"): one matching set drawn from each brand's official mark on its TV banner colour,
// every mark at the same optical size. (The banners each APK ships stay in assets/apps, unused.)
// Netflix isn't on this TV, so its tile is drawn.
const APPS = [
  { name: "Prime Video", cover: "primevideo" },
  { name: "Netflix", cover: "netflix" },
  { name: "YouTube", cover: "youtube" },
  { name: "JioHotstar", cover: "jiohotstar" },
  { name: "Spotify", cover: "spotify" },
  { name: "App Store", cover: "appstore" },
  { name: "Media Center", cover: "media" },
];
const INPUTS = [
  { name: "HDMI 1", icon: "plugs-connected", live: true },
  { name: "HDMI 2", icon: "plug", live: false },
  { name: "AV", icon: "television-simple", live: false },
];
const SHORTCUTS = [
  { name: "Wi-Fi", icon: "wifi-high", says: "Opening Wi-Fi settings" },
  { name: "Picture", icon: "image", says: "Opening the picture menu" },
  { name: "Sound", icon: "speaker-high", says: "Opening the sound menu" },
  { name: "Settings", icon: "gear-six", says: "Opening Settings" },
];
const PLACEHOLDERS = [
  { title: "What's trending shows here", meta: "Add a TMDB key to fill this shelf" },
  { title: "Poster", meta: "" },
  { title: "Poster", meta: "" },
];

// Focus moves through these rows top to bottom; Up and Down go to the nearest item in the next row.
const ORDER = ["buttons", "posters", "dock", "inputs", "shortcuts"];
// Page 2 scrolls the dock up to this distance from the top edge.
const PAGE2_DOCK_TOP = 34;

const FALLBACK_GLOW = [68, 68, 68];

const BENGALURU = { latitude: 12.9716, longitude: 77.5946 };
const WEATHER_EVERY_MS = 30 * 60 * 1000;
const RETRY_MS = 60 * 1000;
const TMDB_KEY_FILE = "local/tmdb-key.txt";
const TMDB_CACHE = "baron-tv-tmdb-v1";
const TMDB_EVERY_MS = 3 * 60 * 60 * 1000;

const screen = document.getElementById("screen");
const content = document.getElementById("content");
const postersEl = document.getElementById("posters");
const dockEl = document.getElementById("dock");
const inputChips = document.getElementById("inputChips");
const shortcutChips = document.getElementById("shortcutChips");
const ambient = document.getElementById("ambient");
const inputs = document.getElementById("inputs");
const toastEl = document.getElementById("toast");
const nowShot = document.getElementById("nowShot");
const frame = document.getElementById("frame");
const controls = document.getElementById("controls");
const rounds = [...document.querySelectorAll(".round")];
const inputRows = [...document.querySelectorAll(".input-row")];
const island = document.getElementById("island");
const sideDock = document.getElementById("sideDock");
const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)");

const state = {
  view: "new",
  row: "dock",
  zone: "grid",
  page: 0,
  input: 0,
  index: { buttons: 0, posters: 0, dock: 0, inputs: 0, shortcuts: 0 },
};
let posterItems = PLACEHOLDERS;
let reading = null;

const clamp = (n, lo, hi) => Math.max(lo, Math.min(n, hi));
const settings = () => Object.fromEntries(new FormData(controls));
// The now-playing island, while it shows, joins the top row in screen order: left of the round
// buttons, which sit at the right for every clock (the clock leads, top left). In the sidebar layout
// the round buttons' jobs move into the side dock, so the island is the top row.
const sidebar = () => screen.dataset.layout === "sidebar";
const topRow = () =>
  sidebar()
    ? island.hidden
      ? []
      : [island]
    : island.hidden
      ? rounds
      : [island, ...rounds];
const rowItems = (row) =>
  row === "buttons"
    ? topRow()
    : row === "posters"
      ? [...postersEl.children]
      : row === "dock"
        ? [...dockEl.children]
        : row === "side"
          ? sideItems()
          : row === "inputs"
            ? [...inputChips.children]
            : [...shortcutChips.children];

function glyph(icon) {
  const span = document.createElement("span");
  span.className = "glyph";
  span.dataset.icon = icon;
  span.setAttribute("aria-hidden", "true");
  return span;
}

// Phosphor glyphs are CSS masks, so they take the text colour; the weight comes from the Icons control,
// unless a glyph asks for its own (media controls are always filled, as players draw them).
function paintGlyphs() {
  const weight = settings().ui;
  for (const g of screen.querySelectorAll(".glyph")) {
    g.style.setProperty("--src", `url("assets/icons/phosphor/${g.dataset.weight || weight}/${g.dataset.icon}.svg")`);
  }
}

// ---- Building the rows ---------------------------------------------------------------------------

function renderDock() {
  dockEl.style.setProperty("--count", APPS.length);
  dockEl.replaceChildren(
    ...APPS.map((app, i) => {
      const tile = document.createElement("button");
      tile.type = "button";
      tile.className = "tile";
      tile.setAttribute("aria-label", app.name);
      const card = document.createElement("span");
      card.className = "card";
      const img = new Image();
      img.alt = "";
      img.decoding = "async";
      img.src = `assets/apps/covers/${app.cover}.svg`;
      card.append(img);
      tile.append(card);
      tile.addEventListener("click", () => {
        focusItem("dock", i);
        toast(`Opening ${app.name}`);
      });
      return tile;
    }),
  );
}

function renderPosters(items) {
  posterItems = items;
  postersEl.replaceChildren(
    ...items.map((item, i) => {
      const poster = document.createElement("button");
      poster.type = "button";
      poster.className = "poster";
      const art = document.createElement("span");
      art.className = "art";
      if (item.image) {
        const img = new Image();
        img.alt = "";
        img.decoding = "async";
        img.crossOrigin = "anonymous";
        img.src = item.image;
        art.append(img);
      } else {
        art.append(glyph("film-slate"));
      }
      const title = document.createElement("span");
      title.className = "p-title";
      title.textContent = item.title;
      poster.append(art, title);
      if (item.meta) {
        const meta = document.createElement("span");
        meta.className = "p-meta";
        meta.textContent = item.meta;
        poster.append(meta);
      }
      poster.addEventListener("click", () => {
        focusItem("posters", i);
        toast(item.app ? `Opening ${item.app}` : "Add a TMDB key to see what's trending");
      });
      return poster;
    }),
  );
  paintGlyphs();
  if (state.row === "posters" && state.zone === "grid") focusItem("posters", state.index.posters);
}

function chipFor(row, label, icon, onPress, idle) {
  const chip = document.createElement("button");
  chip.type = "button";
  chip.className = "chip";
  if (idle) chip.dataset.idle = "";
  const name = document.createElement("span");
  name.textContent = label;
  chip.append(glyph(icon), name);
  chip.addEventListener("click", () => {
    focusItem(row, rowItems(row).indexOf(chip));
    onPress();
  });
  return chip;
}

// The side dock's views: its tiles, or the Wi-Fi or Sound view that replaces them ("when Wi-Fi is
// clicked the whole panel should turn into the Wi-Fi devices nearby, same for Sound"), as a Control
// Centre module opens. Sound is the box itself turned into one volume bar ("only the sound bar should
// be there, the bento box itself should turn into the sound bar"). Demo networks; on the TV the
// launcher would list WifiManager's scan results.
const NETWORKS = [
  { name: "Home 5G", bars: "wifi-high", locked: true, on: true },
  { name: "Home 2.4G", bars: "wifi-high", locked: true },
  { name: "JioFiber-8812", bars: "wifi-medium", locked: true },
  { name: "Airtel_Xstream_4521", bars: "wifi-medium", locked: true },
  { name: "ACT-Fibernet-77", bars: "wifi-low", locked: true },
  { name: "Cafe Guest", bars: "wifi-low", locked: false },
];
const side = { view: "home", from: "", volume: 35, wifi: true, joining: "" };

function sideItems() {
  if (side.view === "home") return [...sideDock.querySelectorAll(":scope > div > .bento-tile")];
  return [...sideDock.querySelectorAll(`.side-view[data-view="${side.view}"] .sv-item`)];
}

// One row of a view: what leads it (a badge or an icon), the name with a note under it, what trails it.
function svRow(cls, lead, label, note, trail, on, onPress) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = `sv-item ${cls}`;
  if (on) b.dataset.on = "";
  const text = document.createElement("span");
  text.className = "sv-text";
  const name = document.createElement("span");
  name.className = "sv-name";
  name.textContent = label;
  text.append(name);
  if (note) {
    const small = document.createElement("small");
    small.textContent = note;
    text.append(small);
  }
  if (lead) b.append(lead);
  b.append(text, ...trail);
  b.addEventListener("click", () => {
    focusItem("side", sideItems().indexOf(b));
    onPress();
  });
  return b;
}

function svHead(title, trail) {
  const head = document.createElement("div");
  head.className = "sv-head";
  const back = document.createElement("button");
  back.type = "button";
  back.className = "sv-item sv-back";
  back.setAttribute("aria-label", "Back");
  back.append(glyph("caret-left"));
  back.addEventListener("click", closeSideView);
  const t = document.createElement("p");
  t.className = "sv-title";
  t.textContent = title;
  head.append(back, t);
  if (trail) head.append(trail);
  return head;
}

function svLabel(text) {
  const label = document.createElement("p");
  label.className = "sv-label";
  label.textContent = text;
  return label;
}

function svGroup(rows) {
  const group = document.createElement("div");
  group.className = "sv-group";
  group.append(...rows);
  return group;
}

// Signal strength as Apple draws it: the whole fan, the arcs it doesn't reach dimmed, so every network's
// icon is the same size.
function signal(bars) {
  const s = document.createElement("span");
  s.className = "sv-signal";
  s.append(glyph("wifi-high"), glyph(bars));
  return s;
}

function drawWifiView(view) {
  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "sv-item sv-switch";
  toggle.setAttribute("role", "switch");
  toggle.setAttribute("aria-label", "Wi-Fi");
  toggle.setAttribute("aria-checked", String(side.wifi));
  toggle.append(document.createElement("i"));
  toggle.addEventListener("click", () => {
    side.wifi = !side.wifi;
    toast(side.wifi ? "Wi-Fi on" : "Wi-Fi off");
    redrawSideView(".sv-switch");
  });
  const head = svHead("Wi-Fi", toggle);
  if (!side.wifi) {
    const off = document.createElement("p");
    off.className = "sv-empty";
    off.textContent = "Wi-Fi is off. Apps that need the internet won't load until it's back on.";
    return view.replaceChildren(head, off);
  }
  // The network in use, as Control Centre shows it: a filled badge, its name over its state.
  const current = NETWORKS.find((n) => n.on);
  const joining = side.joining === current.name;
  const badge = document.createElement("span");
  badge.className = "sv-badge";
  badge.append(glyph(current.bars));
  const hero = svRow("sv-hero", badge, current.name, joining ? "Connecting…" : "Connected", [], !joining, () =>
    toast(joining ? `Connecting to ${current.name}` : `Connected to ${current.name}`),
  );
  const others = NETWORKS.filter((n) => !n.on).map((n) =>
    svRow("sv-net", null, n.name, "", [...(n.locked ? [glyph("lock-simple")] : []), signal(n.bars)], false, () => {
      NETWORKS.forEach((x) => (x.on = x === n));
      side.joining = n.name;
      redrawSideView(".sv-hero");
      setTimeout(() => {
        if (side.joining !== n.name) return;
        side.joining = "";
        if (side.view === "wifi") redrawSideView();
        toast(`Connected to ${n.name}`);
      }, 1400);
    }),
  );
  view.replaceChildren(head, hero, svLabel("Other networks"), svGroup(others));
}

// Sound: the whole box becomes one tall volume bar, filling from the bottom in white as Control Centre's
// does. Up and Down move it in fives; on the Mac, a click or a drag sets it. Its glyph and number sit
// twice, light on the dark bar and dark on the white fill (clipped to the level), so they read on
// either, even split across the fill's edge.
function drawSoundView(view) {
  const bar = document.createElement("div");
  bar.className = "sv-item sv-level";
  bar.tabIndex = -1;
  bar.setAttribute("role", "slider");
  bar.setAttribute("aria-label", "Volume");
  bar.setAttribute("aria-orientation", "vertical");
  bar.setAttribute("aria-valuemin", "0");
  bar.setAttribute("aria-valuemax", "100");
  for (const cls of ["sv-lv", "sv-lv sv-lv-fill"]) {
    const layer = document.createElement("span");
    layer.className = cls;
    const caption = document.createElement("span");
    caption.className = "sv-lv-caption";
    caption.textContent = "Volume";
    const val = document.createElement("span");
    val.className = "sv-val";
    layer.append(glyph("speaker-simple-low"), caption, val);
    bar.append(layer);
  }
  const fromPointer = (event) => {
    const r = bar.getBoundingClientRect();
    setVolume(Math.round(((r.bottom - event.clientY) / r.height) * 20) * 5);
  };
  bar.addEventListener("pointerdown", (event) => {
    focusItem("side", sideItems().indexOf(bar));
    bar.setPointerCapture(event.pointerId);
    fromPointer(event);
  });
  bar.addEventListener("pointermove", (event) => {
    if (event.buttons) fromPointer(event);
  });
  view.replaceChildren(bar);
  setVolume(side.volume);
}

function setVolume(v) {
  side.volume = clamp(v, 0, 100);
  const bar = sideDock.querySelector(".sv-level");
  if (!bar) return;
  bar.setAttribute("aria-valuenow", String(side.volume));
  bar.style.setProperty("--v", side.volume);
  const icon = side.volume === 0 ? "speaker-simple-x" : side.volume < 50 ? "speaker-simple-low" : "speaker-simple-high";
  for (const g of bar.querySelectorAll(".glyph")) g.dataset.icon = icon;
  for (const n of bar.querySelectorAll(".sv-val")) n.textContent = String(side.volume);
  paintGlyphs();
}

// Leaving a view (Left, back to the shelf) closes it, as Control Centre's modules close when you look
// away; Back closes it too, and returns focus to its tile.
screen.addEventListener("focusin", (event) => {
  if (side.view === "home") return;
  if (event.target.closest(".side-view")) return;
  side.view = "home";
  sideDock.dataset.view = "home";
});

// Redraws the open view; focus stays on the same row, or goes to the one a selector names.
function redrawSideView(focus) {
  const at = sideItems().indexOf(document.activeElement);
  const view = sideDock.querySelector(`.side-view[data-view="${side.view}"]`);
  if (side.view === "wifi") drawWifiView(view);
  if (side.view === "sound") drawSoundView(view);
  paintGlyphs();
  const items = sideItems();
  const want = focus && at >= 0 ? items.findIndex((el) => el.matches(focus)) : at;
  if (want >= 0) focusItem("side", Math.min(want, items.length - 1));
}

function openSideView(name, fromLabel) {
  side.view = name;
  side.from = fromLabel;
  sideDock.dataset.view = name;
  redrawSideView();
  // The first row under the header: the network in use, or the volume; with Wi-Fi off, its switch.
  const items = sideItems();
  const first = items.findIndex((el) => !el.closest(".sv-head"));
  focusItem("side", first >= 0 ? first : items.length - 1);
}

function closeSideView() {
  if (side.view === "home") return;
  side.view = "home";
  sideDock.dataset.view = "home";
  const tile = sideItems().find((t) => t.textContent.trim() === side.from);
  focusItem("side", Math.max(0, sideItems().indexOf(tile)));
}

// The sidebar's dock: the inputs on one row, the TV's settings under them, in the bento's tiles.
function renderSideDock() {
  const tile = (label, icon, onPress, idle) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "bento-tile";
    if (idle) b.dataset.idle = "";
    else if (icon === "plugs-connected") b.dataset.live = "";
    const name = document.createElement("span");
    name.className = "bento-tile-label";
    name.textContent = label;
    b.append(glyph(icon), name);
    b.addEventListener("click", () => {
      focusItem("side", rowItems("side").indexOf(b));
      onPress();
    });
    return b;
  };
  const section = (label, cls, tiles) => {
    const head = document.createElement("p");
    head.className = "bento-dock-label";
    head.textContent = label;
    const row = document.createElement("div");
    row.className = cls;
    row.append(...tiles);
    return [head, row];
  };
  sideDock.replaceChildren(
    ...section(
      "Inputs",
      "bento-dock-row",
      INPUTS.map((input) => tile(input.name, input.icon, () => toast(input.live ? `Switching to ${input.name}` : `Nothing is plugged into ${input.name}`), !input.live)),
    ),
    ...section(
      "TV",
      "bento-dock-grid",
      SHORTCUTS.map((s) =>
        tile(s.name, s.icon, () => {
          if (s.name === "Wi-Fi") openSideView("wifi", s.name);
          else if (s.name === "Sound") openSideView("sound", s.name);
          else toast(s.says);
        }),
      ),
    ),
  );
  for (const name of ["wifi", "sound"]) {
    const view = document.createElement("div");
    view.className = "side-view";
    view.dataset.view = name;
    sideDock.append(view);
  }
  sideDock.dataset.view = side.view;
}

function renderChips() {
  inputChips.replaceChildren(
    ...INPUTS.map((input) =>
      chipFor("inputs", input.name, input.icon, () => toast(input.live ? `Switching to ${input.name}` : `Nothing is plugged into ${input.name}`), !input.live),
    ),
  );
  shortcutChips.replaceChildren(...SHORTCUTS.map((s) => chipFor("shortcuts", s.name, s.icon, () => toast(s.says))));
}

// ---- Geometry ------------------------------------------------------------------------------------

// An element's box in the content layer, before any focus scale. The island sits on the screen itself,
// whose origin is the content layer's.
function rectIn(el) {
  let x = 0;
  let y = 0;
  for (let n = el; n && n !== content && n !== screen; n = n.offsetParent) {
    x += n.offsetLeft;
    y += n.offsetTop;
  }
  // The island is centred with a translate, which offsets don't include.
  if (el === island) x -= el.offsetWidth / 2;
  return { x, y, w: el.offsetWidth, h: el.offsetHeight };
}

function nearestIn(row, fromEl) {
  const from = rectIn(fromEl);
  const cx = from.x + from.w / 2;
  let best = 0;
  let bestD = Infinity;
  rowItems(row).forEach((el, i) => {
    const r = rectIn(el);
    const d = Math.abs(r.x + r.w / 2 - cx);
    if (d < bestD) {
      bestD = d;
      best = i;
    }
  });
  return best;
}

// ---- Glow in the colour of the focused artwork, as AppAdapter.vividColor picks it ---------------------

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

function hsvToRgb(h, s, v) {
  const c = v * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = v - c;
  const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return [r, g, b].map((n) => Math.round((n + m) * 255));
}

const glowCache = new Map();

// The most saturated, not-too-dark pixel of the artwork at 12x12; a nearly grey one gives its
// average, lifted.
function vividColor(img) {
  if (glowCache.has(img.src)) return glowCache.get(img.src);
  let result = FALLBACK_GLOW;
  try {
    const n = 12;
    const canvas = document.createElement("canvas");
    canvas.width = n;
    canvas.height = n;
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    ctx.drawImage(img, 0, 0, n, n);
    const px = ctx.getImageData(0, 0, n, n).data;
    let best = FALLBACK_GLOW;
    let bestScore = -1;
    const sum = [0, 0, 0];
    for (let i = 0; i < px.length; i += 4) {
      sum[0] += px[i];
      sum[1] += px[i + 1];
      sum[2] += px[i + 2];
      const [, s, v] = rgbToHsv(px[i], px[i + 1], px[i + 2]);
      const score = s * Math.min(1, v * 1.4);
      if (score > bestScore) {
        bestScore = score;
        best = [px[i], px[i + 1], px[i + 2]];
      }
    }
    if (bestScore < 0.25) {
      const [h, s, v] = rgbToHsv(...sum.map((c) => c / (n * n)));
      result = hsvToRgb(h, s, Math.max(v, 0.55));
    } else {
      const [h, s, v] = rgbToHsv(...best);
      result = hsvToRgb(h, Math.min(1, s * 1.1), Math.max(0.5, v));
    }
  } catch {
    // An image the canvas may not read: grey glow.
  }
  glowCache.set(img.src, result);
  return result;
}

let glowShown = false;
let glowKey = "";

function fade(el, to, ms, done) {
  const from = getComputedStyle(el).opacity;
  const anim = el.animate([{ opacity: from }, { opacity: to }], {
    duration: reduceMotion.matches ? 0 : ms,
    easing: "cubic-bezier(0.23, 1, 0.32, 1)",
    fill: "forwards",
  });
  anim.onfinish = () => {
    el.style.opacity = String(to);
    anim.cancel();
    if (done) done();
  };
}

// sRGB to OKLCH, through Bjorn Ottosson's OKLab.
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

// Every glow at one lightness with capped chroma, so no app's glow is louder than another's. Yellows
// lean to amber: a dark yellow on black reads as olive.
function setGlow(rgb) {
  glowKey = rgb.join(",");
  const [, c, h0] = toOklch(rgb);
  const h = h0 > 75 && h0 < 120 ? 68 : h0;
  const chroma = c < 0.03 ? 0 : Math.min(c, 0.16);
  const tone = `62% ${chroma.toFixed(3)} ${h.toFixed(1)}`;
  ambient.style.setProperty("--amb-1", `oklch(${tone} / 0.34)`);
  ambient.style.setProperty("--amb-2", `oklch(${tone} / 0.13)`);
  // Refined (glow.css): a deeper, more saturated core behind the dock, and the far corner a step
  // round the colour wheel so the screen has depth instead of one flat tint.
  const deep = chroma ? Math.min(chroma * 1.15, 0.18) : 0;
  ambient.style.setProperty("--g-core", `oklch(50% ${deep.toFixed(3)} ${h.toFixed(1)} / 0.6)`);
  ambient.style.setProperty("--g-mid", `oklch(40% ${(deep * 0.9).toFixed(3)} ${h.toFixed(1)} / 0.26)`);
  ambient.style.setProperty("--g-far", `oklch(46% ${(deep * 0.75).toFixed(3)} ${((h + 335) % 360).toFixed(1)} / 0.16)`);
}

function glowFor(el) {
  if (settings().glow === "off") {
    hideGlow();
    return;
  }
  const img = el.querySelector("img");
  const rgb = img && img.complete && img.naturalWidth ? vividColor(img) : FALLBACK_GLOW;
  if (!glowShown) {
    glowShown = true;
    setGlow(rgb);
    fade(ambient, 1, 600);
  } else if (rgb.join(",") !== glowKey) {
    // The refined glow's colours are registered properties, so they morph on their own.
    if (settings().glow === "refined") setGlow(rgb);
    else {
      fade(ambient, 0.35, 200, () => {
        setGlow(rgb);
        fade(ambient, 1, 500);
      });
    }
  }
}

function hideGlow() {
  if (!glowShown) return;
  glowShown = false;
  fade(ambient, 0, 300);
}

// ---- Focus -----------------------------------------------------------------------------------------

// ctl: which island control takes focus if the island is the target (0 previous, 1 play, 2 next).
function focusItem(row, i, ctl = 1) {
  const wasIsland = state.zone === "island";
  if (row === "buttons" || row === "posters" || row === "side") state.page = 0;
  if (row === "inputs" || row === "shortcuts") state.page = 1;
  // The island only shows on page 1, so settle it before reading the top row.
  syncIsland();
  const items = rowItems(row);
  if (!items.length) return;
  const index = clamp(i, 0, items.length - 1);
  state.row = row;
  state.zone = "grid";
  state.index[row] = index;
  content.style.setProperty("--page-scroll", `${state.page ? -(dockEl.offsetTop - PAGE2_DOCK_TOP) : 0}px`);
  const el = items[index];
  if (el === island) {
    enterIsland(ctl);
    glowFor(island);
    return;
  }
  el.focus({ preventScroll: true });
  if (wasIsland) collapseIsland();
  if (row === "posters" || row === "dock") glowFor(el);
  else hideGlow();
}

function openInputs() {
  inputs.hidden = false;
  state.zone = "inputs";
  focusInputRow(0);
}

function closeInputs() {
  inputs.hidden = true;
  focusItem("buttons", rowItems("buttons").indexOf(rounds[0]));
}

function focusInputRow(i) {
  state.input = clamp(i, 0, inputRows.length - 1);
  inputRows[state.input].focus({ preventScroll: true });
}

// Sidebar layout: focus goes to the nearest item in the pressed direction, as Android's FocusFinder
// picks it: items in the same band (overlapping across the move) first, then the closest.
function boxOf(el) {
  const s = screen.getBoundingClientRect();
  const z = s.width / 960;
  const b = el.getBoundingClientRect();
  return { l: (b.left - s.left) / z, t: (b.top - s.top) / z, r: (b.right - s.left) / z, b: (b.bottom - s.top) / z };
}

function nearestToward(fromEl, key) {
  const a = boxOf(fromEl);
  const pool = [...topRow(), ...rowItems("posters"), ...rowItems("dock"), ...rowItems("side")];
  let best = null;
  let bestScore = Infinity;
  for (const el of pool) {
    if (el === fromEl || !el.offsetParent) continue;
    const b = boxOf(el);
    const across = key === "ArrowLeft" || key === "ArrowRight";
    const gap = { ArrowRight: b.l - a.r, ArrowLeft: a.l - b.r, ArrowDown: b.t - a.b, ArrowUp: a.t - b.b }[key];
    if (gap < -6) continue;
    const off = across ? Math.max(0, Math.max(a.t, b.t) - Math.min(a.b, b.b)) : Math.max(0, Math.max(a.l, b.l) - Math.min(a.r, b.r));
    const score = Math.max(0, gap) + off * 3;
    if (score < bestScore) {
      best = el;
      bestScore = score;
    }
  }
  return best;
}

function focusEl(el, ctl = 1) {
  for (const row of ["buttons", "posters", "dock", "side"]) {
    const i = rowItems(row).indexOf(el);
    if (i >= 0) return focusItem(row, i, ctl);
  }
}

function move(key) {
  if (document.activeElement?.classList.contains("sv-level") && (key === "ArrowUp" || key === "ArrowDown")) {
    setVolume(side.volume + (key === "ArrowUp" ? 5 : -5));
    return;
  }
  if (state.zone === "inputs") {
    if (key === "ArrowUp") focusInputRow(state.input - 1);
    if (key === "ArrowDown") focusInputRow(state.input + 1);
    return;
  }
  if (sidebar()) {
    if (state.zone === "island") {
      if (key === "ArrowLeft" && np.ctl > 0) return focusCtl(np.ctl - 1);
      if (key === "ArrowRight" && np.ctl < islCtls.length - 1) return focusCtl(np.ctl + 1);
    }
    const from = state.zone === "island" ? island : rowItems(state.row)[state.index[state.row]];
    const to = from && nearestToward(from, key);
    if (to) focusEl(to, key === "ArrowLeft" ? islCtls.length - 1 : key === "ArrowRight" ? 0 : 1);
    return;
  }
  // Inside the open island: Left and Right walk previous, play and next, then on to its neighbours.
  if (state.zone === "island") {
    const top = rowItems("buttons");
    const at = top.indexOf(island);
    if (key === "ArrowLeft") {
      if (np.ctl > 0) focusCtl(np.ctl - 1);
      else if (at > 0) focusItem("buttons", at - 1);
    }
    if (key === "ArrowRight") {
      if (np.ctl < islCtls.length - 1) focusCtl(np.ctl + 1);
      else if (at < top.length - 1) focusItem("buttons", at + 1);
    }
    if (key === "ArrowDown") focusItem("posters", nearestIn("posters", island));
    return;
  }
  const row = state.row;
  const items = rowItems(row);
  const i = state.index[row];
  if (key === "ArrowLeft") {
    if (i > 0) focusItem(row, i - 1, islCtls.length - 1);
    return;
  }
  if (key === "ArrowRight") {
    if (i < items.length - 1) focusItem(row, i + 1, 0);
    return;
  }
  const next = ORDER[ORDER.indexOf(row) + (key === "ArrowDown" ? 1 : -1)];
  if (next) focusItem(next, nearestIn(next, items[i]));
}

rounds.forEach((button, i) => {
  button.addEventListener("click", () => {
    if (i === 0) {
      if (inputs.hidden) openInputs();
      else closeInputs();
      return;
    }
    focusItem("buttons", rowItems("buttons").indexOf(button));
    toast(i === 1 ? "Opening Wi-Fi settings" : "Opening Settings");
  });
});

inputRows.forEach((row, i) => {
  row.addEventListener("click", (event) => {
    event.stopPropagation();
    focusInputRow(i);
    closeInputs();
    toast(`Switching to ${row.dataset.input}`);
  });
});

// Enter and Space reach the click handlers on their own, as they do for any button.
document.addEventListener("keydown", (event) => {
  if (state.view !== "new" || event.target.closest(".controls")) return;
  if (event.key.startsWith("Arrow")) {
    event.preventDefault();
    move(event.key);
  } else if (event.key === "Escape" || event.key === "Backspace") {
    event.preventDefault();
    if (!inputs.hidden) closeInputs();
    else if (state.row === "side" && side.view !== "home") closeSideView();
    else if (state.zone === "island" || state.page === 1) focusItem("dock", state.index.dock);
  }
});

screen.addEventListener("pointerdown", (event) => {
  if (!inputs.hidden && !event.target.closest(".btn-wrap")) closeInputs();
});

// ---- Trending on your apps, from TMDB ----------------------------------------------------------------

async function tmdbKey() {
  try {
    const res = await fetch(TMDB_KEY_FILE, { cache: "no-store" });
    return res.ok ? (await res.text()).trim() : "";
  } catch {
    return "";
  }
}

// A v4 read token (a JWT) goes in a header; a v3 key goes in the query.
async function tmdb(key, path, params = {}) {
  const url = new URL(`https://api.themoviedb.org/3${path}`);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const headers = { accept: "application/json" };
  if (key.startsWith("eyJ")) headers.Authorization = `Bearer ${key}`;
  else url.searchParams.set("api_key", key);
  const res = await fetch(url, { headers });
  if (!res.ok) throw new Error(`TMDB answered ${res.status} for ${path}`);
  return res.json();
}

const appFor = (name) => (/hotstar/i.test(name) ? "JioHotstar" : /prime video/i.test(name) ? "Prime Video" : "");

async function fetchTrending(key) {
  const [movieProviders, tvProviders] = await Promise.all([
    tmdb(key, "/watch/providers/movie", { watch_region: "IN" }),
    tmdb(key, "/watch/providers/tv", { watch_region: "IN" }),
  ]);
  const ours = [...movieProviders.results, ...tvProviders.results].filter((p) => appFor(p.provider_name) && !/channel/i.test(p.provider_name));
  const providerIds = [...new Set(ours.map((p) => p.provider_id))].join("|");
  const discover = {
    watch_region: "IN",
    with_watch_providers: providerIds,
    sort_by: "popularity.desc",
    include_adult: "false",
    language: "en-IN",
  };
  const [movies, shows, movieGenres, tvGenres] = await Promise.all([
    tmdb(key, "/discover/movie", discover),
    tmdb(key, "/discover/tv", discover),
    tmdb(key, "/genre/movie/list", { language: "en" }),
    tmdb(key, "/genre/tv/list", { language: "en" }),
  ]);
  const genres = new Map([...movieGenres.genres, ...tvGenres.genres].map((g) => [g.id, g.name]));
  const pool = [
    ...movies.results.map((r) => ({ ...r, type: "movie" })),
    ...shows.results.map((r) => ({ ...r, type: "tv" })),
  ].sort((a, b) => b.popularity - a.popularity);

  const featured = pool.find((r) => r.backdrop_path);
  const tall = pool.filter((r) => r !== featured && r.poster_path).slice(0, 2);
  const picks = [featured, ...tall].filter(Boolean);

  return Promise.all(
    picks.map(async (r, i) => {
      const where = await tmdb(key, `/${r.type}/${r.id}/watch/providers`);
      const india = where.results?.IN ?? {};
      const offer = [...(india.flatrate ?? []), ...(india.ads ?? []), ...(india.free ?? [])].find((p) => appFor(p.provider_name));
      const app = offer ? appFor(offer.provider_name) : "";
      const kind = r.type === "tv" ? "Series" : "Movie";
      const genre = genres.get(r.genre_ids?.[0]) ?? "";
      let seasons = "";
      if (i === 0 && r.type === "tv") {
        const details = await tmdb(key, `/tv/${r.id}`, { language: "en-IN" });
        const n = details.number_of_seasons;
        if (n) seasons = n === 1 ? "1 season" : `${n} seasons`;
      }
      return {
        title: r.title ?? r.name,
        meta: [kind, genre, seasons, app].filter(Boolean).join(" · "),
        image: i === 0 ? `https://image.tmdb.org/t/p/w780${r.backdrop_path}` : `https://image.tmdb.org/t/p/w342${r.poster_path}`,
        app: app || (r.type === "tv" ? "JioHotstar" : "Prime Video"),
      };
    }),
  );
}

async function loadTrending() {
  const key = await tmdbKey();
  if (!key) return;
  try {
    const cached = JSON.parse(localStorage.getItem(TMDB_CACHE) || "null");
    if (cached && Date.now() - cached.at < TMDB_EVERY_MS) {
      renderPosters(cached.items);
      return;
    }
  } catch {
    // No cache in a private window: fetch.
  }
  try {
    const items = await fetchTrending(key);
    if (items.length === 3) {
      renderPosters(items);
      try {
        localStorage.setItem(TMDB_CACHE, JSON.stringify({ at: Date.now(), items }));
      } catch {
        // Blocked storage: it just fetches again next time.
      }
    }
  } catch (error) {
    console.warn(error);
    setTimeout(loadTrending, RETRY_MS);
  }
}

// ---- Weather, from Open-Meteo ------------------------------------------------------------------------

// WMO weather codes, as Open-Meteo reports them, to words and an icon from each set.
function describe(code, isDay) {
  const dn = (day, night) => (isDay ? day : night);
  if (code === 0) return { text: "Clear", ph: dn("sun", "moon"), mc: dn("clear-day", "clear-night") };
  if (code === 1) return { text: "Mostly clear", ph: dn("sun", "moon"), mc: dn("mostly-clear-day", "mostly-clear-night") };
  if (code === 2) return { text: "Partly cloudy", ph: dn("cloud-sun", "cloud-moon"), mc: dn("partly-cloudy-day", "partly-cloudy-night") };
  if (code === 3) return { text: "Cloudy", ph: "cloud", mc: "cloudy" };
  if (code === 45 || code === 48) return { text: "Foggy", ph: "cloud-fog", mc: "fog" };
  if (code >= 51 && code <= 57) return { text: "Drizzle", ph: "cloud-rain", mc: "drizzle" };
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) return { text: "Snow", ph: "cloud-snow", mc: "snow" };
  if (code >= 95) return { text: "Thunderstorms", ph: "cloud-lightning", mc: "thunderstorms-rain" };
  const rain = {
    61: "Light rain",
    63: "Rain",
    65: "Heavy rain",
    66: "Freezing rain",
    67: "Freezing rain",
    80: "Light showers",
    81: "Showers",
    82: "Heavy showers",
  };
  if (rain[code]) return { text: rain[code], ph: "cloud-rain", mc: "rain" };
  return { text: "Weather", ph: "cloud", mc: "cloudy" };
}

// Meteocons use their colour set: its pale clouds are drawn for a dark screen.
function weatherIcon(w) {
  if (settings().weather === "meteocons") {
    const img = new Image();
    img.alt = "";
    img.src = `assets/icons/meteocons/flat/${w.mc}.svg`;
    return img;
  }
  return glyph(w.ph);
}

const clockTime = (iso) => {
  const [h, m] = iso.slice(11, 16).split(":").map(Number);
  return `${h % 12 || 12}:${String(m).padStart(2, "0")} ${h < 12 ? "am" : "pm"}`;
};
const hourLabel = (iso) => {
  const h = Number(iso.slice(11, 13));
  return `${h % 12 || 12} ${h < 12 ? "am" : "pm"}`;
};

async function loadWeather() {
  const params = new URLSearchParams({
    latitude: BENGALURU.latitude,
    longitude: BENGALURU.longitude,
    current: "temperature_2m,weather_code,is_day",
    hourly: "temperature_2m,weather_code,is_day",
    daily: "temperature_2m_max,temperature_2m_min,sunrise,sunset",
    forecast_days: "2",
    timezone: "Asia/Kolkata",
  });
  try {
    const res = await fetch(`https://api.open-meteo.com/v1/forecast?${params}`);
    if (!res.ok) throw new Error(`Open-Meteo answered ${res.status}`);
    const data = await res.json();
    const nowHour = data.current.time.slice(0, 13);
    const start = data.hourly.time.findIndex((t) => t.slice(0, 13) === nowHour) + 1;
    reading = {
      temp: Math.round(data.current.temperature_2m),
      ...describe(data.current.weather_code, data.current.is_day === 1),
      high: Math.round(data.daily.temperature_2m_max[0]),
      low: Math.round(data.daily.temperature_2m_min[0]),
      sunrise: clockTime(data.daily.sunrise[0]),
      sunset: clockTime(data.daily.sunset[0]),
      hours: data.hourly.time.slice(start, start + 8).map((t, k) => ({
        label: hourLabel(t),
        temp: Math.round(data.hourly.temperature_2m[start + k]),
        ...describe(data.hourly.weather_code[start + k], data.hourly.is_day[start + k] === 1),
      })),
    };
    renderWeather();
  } catch {
    // Offline or the service hiccuped: keep the last reading, and with none yet, try again soon.
    if (!reading) setTimeout(loadWeather, RETRY_MS);
  }
}

function renderWeather() {
  if (!reading) return;
  document.getElementById("wxIcon").replaceChildren(weatherIcon(reading));
  document.getElementById("wxTemp").textContent = `${reading.temp}°`;
  document.getElementById("wxText").textContent = `${reading.text} in Bengaluru`;
  document.getElementById("weather").hidden = false;

  const gauge = document.getElementById("cxTemp");
  const span = reading.high - reading.low;
  gauge.style.setProperty("--t", span > 0 ? clamp((reading.temp - reading.low) / span, 0, 1).toFixed(3) : "0.5");
  document.getElementById("cxDeg").textContent = `${reading.temp}°`;
  document.getElementById("cxGlyph").replaceChildren(weatherIcon(reading));
  gauge.setAttribute("aria-label", `${reading.temp} degrees, ${reading.text}, high ${reading.high}, low ${reading.low}`);
  gauge.hidden = false;

  document.getElementById("wxBigIcon").replaceChildren(weatherIcon(reading));
  document.getElementById("wxBigTemp").textContent = `${reading.temp}°`;
  document.getElementById("wxCond").textContent = reading.text;
  document.getElementById("wxRange").textContent = `High ${reading.high}°   Low ${reading.low}°`;
  document.getElementById("wxHours").replaceChildren(
    ...reading.hours.map((h) => {
      const li = document.createElement("li");
      const time = document.createElement("span");
      time.className = "wx-hour-time";
      time.textContent = h.label;
      const icon = document.createElement("span");
      icon.className = "wx-hour-icon";
      icon.append(weatherIcon(h));
      const temp = document.createElement("span");
      temp.className = "wx-hour-temp";
      temp.textContent = `${h.temp}°`;
      li.append(time, icon, temp);
      return li;
    }),
  );
  const sun = document.getElementById("wxSun");
  const rise = document.createElement("span");
  rise.append(glyph("sun-horizon"), `Sunrise ${reading.sunrise}`);
  const set = document.createElement("span");
  set.append(glyph("sun-horizon"), `Sunset ${reading.sunset}`);
  sun.replaceChildren(rise, set);
  paintGlyphs();
}

// ---- Toast, clock ----------------------------------------------------------------------------------

let toastTimer;
function toast(text) {
  toastEl.textContent = text;
  toastEl.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove("show"), 1600);
}

function tick() {
  const now = new Date();
  const h = now.getHours();
  const m = now.getMinutes();
  let greeting = "Good night";
  if (h >= 5 && h < 12) greeting = "Good morning";
  else if (h >= 12 && h < 17) greeting = "Good afternoon";
  else if (h >= 17 && h < 22) greeting = "Good evening";
  document.getElementById("greeting").textContent = greeting;
  document.getElementById("time").textContent = `${h % 12 || 12}:${String(m).padStart(2, "0")}`;
  document.getElementById("ampm").textContent = h < 12 ? "am" : "pm";
  document.getElementById("date").textContent = new Intl.DateTimeFormat("en-GB", {
    weekday: "long",
    day: "numeric",
    month: "long",
  }).format(now);
  document.getElementById("watchGreet").textContent = greeting;
  document.getElementById("watchTime").textContent = `${h % 12 || 12}:${String(m).padStart(2, "0")}`;
  document.getElementById("watchAmpm").textContent = h < 12 ? "am" : "pm";
  document.getElementById("cxDay").textContent = new Intl.DateTimeFormat("en-GB", { weekday: "short" }).format(now).toUpperCase();
  document.getElementById("cxNum").textContent = String(now.getDate());
}

// ---- Idle screensaver: the DVD logo -----------------------------------------------------------------

// Vivid tones on black; every bounce picks another one.
const SAVER_COLORS = [25, 60, 95, 145, 195, 250, 300, 340].map((h) => `oklch(72% 0.19 ${h})`);
const SAVER_SPEED = 105; // canvas pixels a second, along each axis
const SAVER_JUMP_MS = 10000; // with reduced motion: a new spot this often instead of gliding
const SAVER_W = 150;
const SAVER_H = 78;

const saverEl = document.getElementById("saver");
const saverLogo = document.getElementById("saverLogo");
const saver = { on: false, raf: 0, jump: 0, x: 0, y: 0, vx: 0, vy: 0, last: 0, color: 0 };
let idleTimer = 0;

function paintSaver() {
  saverLogo.style.transform = `translate3d(${saver.x}px, ${saver.y}px, 0)`;
}

function nextSaverColor() {
  saver.color = (saver.color + 1 + Math.floor(Math.random() * (SAVER_COLORS.length - 1))) % SAVER_COLORS.length;
  saverLogo.style.color = SAVER_COLORS[saver.color];
}

function stepSaver(now) {
  const dt = Math.min(0.05, (now - saver.last) / 1000);
  saver.last = now;
  const maxX = 960 - SAVER_W;
  const maxY = 540 - SAVER_H;
  saver.x += saver.vx * dt;
  saver.y += saver.vy * dt;
  let hitX = false;
  let hitY = false;
  if (saver.x <= 0 || saver.x >= maxX) {
    saver.x = clamp(saver.x, 0, maxX);
    saver.vx = saver.x === 0 ? SAVER_SPEED : -SAVER_SPEED;
    hitX = true;
  }
  if (saver.y <= 0 || saver.y >= maxY) {
    saver.y = clamp(saver.y, 0, maxY);
    saver.vy = saver.y === 0 ? SAVER_SPEED : -SAVER_SPEED;
    hitY = true;
  }
  if (hitX || hitY) nextSaverColor();
  // The corner everyone waits for.
  if (hitX && hitY) {
    saverLogo.animate([{ scale: 1 }, { scale: 1.18 }, { scale: 1 }], { duration: 420, easing: "cubic-bezier(0.23, 1, 0.32, 1)" });
  }
  paintSaver();
  saver.raf = requestAnimationFrame(stepSaver);
}

function randomSpot() {
  saver.x = Math.random() * (960 - SAVER_W);
  saver.y = Math.random() * (540 - SAVER_H);
}

function startSaver() {
  if (saver.on || state.view !== "new") return;
  if (state.zone === "island") focusItem("dock", state.index.dock);
  saver.on = true;
  clearTimeout(idleTimer);
  if (!inputs.hidden) closeInputs();
  collapseIsland();
  syncIsland();
  randomSpot();
  saver.vx = Math.random() < 0.5 ? -SAVER_SPEED : SAVER_SPEED;
  saver.vy = Math.random() < 0.5 ? -SAVER_SPEED : SAVER_SPEED;
  nextSaverColor();
  paintSaver();
  saverEl.hidden = false;
  if (reduceMotion.matches) {
    saver.jump = setInterval(() => {
      randomSpot();
      nextSaverColor();
      paintSaver();
    }, SAVER_JUMP_MS);
  } else {
    saver.last = performance.now();
    saver.raf = requestAnimationFrame(stepSaver);
  }
}

function stopSaver() {
  if (!saver.on) return;
  saver.on = false;
  cancelAnimationFrame(saver.raf);
  clearInterval(saver.jump);
  saverEl.hidden = true;
  syncIsland();
  armIdle();
}

function armIdle() {
  clearTimeout(idleTimer);
  if (saver.on || musicMode.on) return;
  const v = settings();
  // While music plays, the screen dims to its edge light instead of starting the DVD screensaver.
  if (musicPlaying() && v.musicdim !== "off") idleTimer = setTimeout(enterMusicMode, Number(v.musicdim || 8) * 1000);
  else idleTimer = setTimeout(startSaver, Number(v.saver || 600) * 1000);
}

// While the screensaver is up, the first key or click only wakes it, as a remote does on the TV.
// Anything else restarts the idle clock.
addEventListener(
  "keydown",
  (event) => {
    if (saver.on || musicMode.on) {
      event.preventDefault();
      event.stopImmediatePropagation();
      if (saver.on) stopSaver();
      else exitMusicMode();
    } else {
      armIdle();
    }
  },
  true,
);

addEventListener(
  "pointerdown",
  (event) => {
    if (saver.on || musicMode.on) {
      event.preventDefault();
      event.stopImmediatePropagation();
      if (saver.on) stopSaver();
      else exitMusicMode();
    } else {
      armIdle();
    }
  },
  true,
);

let lastMoveArm = 0;
addEventListener("pointermove", () => {
  if (saver.on || musicMode.on || performance.now() - lastMoveArm < 500) return;
  lastMoveArm = performance.now();
  armIdle();
});

document.getElementById("saverNow").addEventListener("click", startSaver);
controls.addEventListener("change", armIdle);

// ---- Now playing on Spotify, Dynamic Island style -------------------------------------------------

// On the TV the launcher reads Spotify's media session; the prototype plays this queue instead. Covers
// and lengths come from Apple's public music search when the page loads, so none are stored; until
// then, and offline, each song has a drawn cover.
const SONGS = [
  { title: "Blinding Lights", artist: "The Weeknd", q: "Blinding Lights The Weeknd", ms: 200000 },
  { title: "Kesariya", artist: "Arijit Singh", q: "Kesariya Arijit Singh Brahmastra", ms: 268000 },
  { title: "Espresso", artist: "Sabrina Carpenter", q: "Espresso Sabrina Carpenter", ms: 175000 },
  { title: "Levitating", artist: "Dua Lipa", q: "Levitating Dua Lipa", ms: 203000 },
  { title: "With You", artist: "AP Dhillon", q: "With You AP Dhillon", ms: 196000 },
];
const COVER_HUES = [350, 30, 210, 300, 160];
const PEEK_MS = 4500; // how long a new song keeps the island open

const islCompact = document.getElementById("islCompact");
const islCtls = [...island.querySelectorAll(".isl-ctl")];
const islToggle = document.getElementById("islToggle");
const islFill = document.getElementById("islFill");
const islArt = island.querySelector("img");
const np = { i: 0, at: 0, ctl: 1, peek: 0, was: "" };

const isPlaying = () => settings().spotify === "playing";

function drawnCover(i) {
  const canvas = document.createElement("canvas");
  canvas.width = 300;
  canvas.height = 300;
  const ctx = canvas.getContext("2d");
  const hue = COVER_HUES[i % COVER_HUES.length];
  const fill = ctx.createLinearGradient(0, 0, 300, 300);
  fill.addColorStop(0, `hsl(${hue} 72% 58%)`);
  fill.addColorStop(1, `hsl(${(hue + 40) % 360} 70% 24%)`);
  ctx.fillStyle = fill;
  ctx.fillRect(0, 0, 300, 300);
  return canvas.toDataURL("image/png");
}

async function loadCovers() {
  await Promise.all(
    SONGS.map(async (song) => {
      try {
        const url = new URL("https://itunes.apple.com/search");
        url.search = new URLSearchParams({ term: song.q, entity: "song", limit: "1", country: "in" });
        const res = await fetch(url);
        const hit = res.ok ? (await res.json()).results?.[0] : null;
        if (!hit) return;
        song.cover = hit.artworkUrl100.replace("100x100bb", "300x300bb");
        if (hit.trackTimeMillis) song.ms = hit.trackTimeMillis;
        if (hit.previewUrl) song.preview = hit.previewUrl;
      } catch {
        // Offline: the drawn cover stays.
      }
    }),
  );
  showSong();
}

const mmss = (ms) => {
  const s = Math.max(0, Math.round(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};

// While a preview plays, the island shows its real time; otherwise the demo clock's.
function paintProgress(jump) {
  const live = previewing();
  const at = live ? ear.audio.currentTime * 1000 : np.at;
  const ms = live ? ear.audio.duration * 1000 : SONGS[np.i].ms;
  if (jump) islFill.style.transition = "none";
  islFill.style.scale = `${Math.min(1, at / ms)} 1`;
  if (jump) {
    islFill.getBoundingClientRect();
    islFill.style.transition = "";
  }
  document.getElementById("islAt").textContent = mmss(at);
  document.getElementById("islLeft").textContent = `-${mmss(ms - at)}`;
}

// The pill is as wide as what it shows, so a new song resizes it with the same morph. It hangs on the
// round buttons' centre line, so it reads as part of the top row it belongs to.
function sizeIsland() {
  if (island.hidden) return;
  island.style.setProperty("--wc", `${islCompact.offsetWidth}px`);
  if (sidebar()) {
    // Centred in the gap between the bento on the left and the dock on the right, level with their tops.
    const left = rectIn(document.getElementById("bento"));
    const right = rectIn(sideDock);
    island.style.setProperty("--isl-x", `${Math.round((left.x + left.w + right.x) / 2)}px`);
    island.style.setProperty("--isl-top", `${Math.round(right.y)}px`);
  } else {
    const button = rectIn(rounds[0]);
    island.style.setProperty("--isl-top", `${Math.round(button.y + button.h / 2 - 17)}px`);
    // With the bento or the widget, centred in the gap between it (left) and the buttons (right); the
    // classic clock is wide, so there it keeps the screen's centre and the date steps aside.
    const clock = { bento: "#bento", widget: ".watch" }[screen.dataset.clock];
    if (clock) {
      const left = rectIn(document.querySelector(clock));
      const right = rectIn(document.querySelector(".buttons"));
      island.style.setProperty("--isl-x", `${Math.round((left.x + left.w + right.x) / 2)}px`);
    } else island.style.removeProperty("--isl-x");
  }
}

function showSong() {
  const song = SONGS[np.i];
  for (const img of island.querySelectorAll("img")) img.src = song.cover;
  document.getElementById("islLine").textContent = song.title;
  document.getElementById("islTitle").textContent = song.title;
  document.getElementById("islArtist").textContent = song.artist;
  sizeIsland();
  paintProgress(true);
  syncSound();
}

// The bars take the cover's colour, lifted to read on black; with the island in focus, so does the glow.
islArt.addEventListener("load", () => {
  const [, c, h0] = toOklch(vividColor(islArt));
  const h = h0 > 75 && h0 < 120 ? 68 : h0;
  island.style.setProperty("--isl-accent", c < 0.03 ? "oklch(92% 0 none)" : `oklch(80% ${Math.min(c, 0.17).toFixed(3)} ${h.toFixed(1)})`);
  if (state.zone === "island") glowFor(island);
});

// Shown while Spotify is on, on page 1 and over the screensaver.
function syncIsland() {
  const v = settings();
  const before = state.row === "buttons" && state.zone === "grid" ? rowItems("buttons")[state.index.buttons] : null;
  const show = v.spotify !== "off" && state.view === "new" && (saver.on || state.page === 0);
  island.hidden = !show;
  island.toggleAttribute("data-playing", v.spotify === "playing");
  island.dataset.look = "black";
  island.classList.toggle("over-saver", saver.on);
  const icon = islToggle.querySelector(".glyph");
  icon.dataset.icon = v.spotify === "playing" ? "pause" : "play";
  icon.style.setProperty("--src", `url("assets/icons/phosphor/fill/${icon.dataset.icon}.svg")`);
  islToggle.setAttribute("aria-label", v.spotify === "playing" ? "Pause" : "Play");
  sizeIsland();
  syncEdge();
  if (before) state.index.buttons = Math.max(0, rowItems("buttons").indexOf(before));
  if (!show && state.zone === "island") {
    state.zone = "grid";
    collapseIsland();
    state.index.buttons = 0;
    rounds[0].focus({ preventScroll: true });
    hideGlow();
  }
}

function enterIsland(ctl) {
  state.zone = "island";
  clearTimeout(np.peek);
  island.dataset.size = "expanded";
  focusCtl(ctl);
}

function focusCtl(i) {
  np.ctl = clamp(i, 0, islCtls.length - 1);
  islCtls[np.ctl].focus({ preventScroll: true });
}

function collapseIsland() {
  clearTimeout(np.peek);
  island.dataset.size = "compact";
}

// A new song opens the island for a moment, as the phone does; focus stays where it is.
function peekIsland() {
  if (island.hidden || saver.on || state.zone === "island") return;
  island.dataset.size = "expanded";
  clearTimeout(np.peek);
  np.peek = setTimeout(() => {
    if (state.zone !== "island") collapseIsland();
  }, PEEK_MS);
}

function changeSong(step) {
  np.i = (np.i + step + SONGS.length) % SONGS.length;
  np.at = 0;
  showSong();
  peekIsland();
}

function setPlaying(on) {
  controls.querySelector(`input[name="spotify"][value="${on ? "playing" : "paused"}"]`).checked = true;
  save();
  syncIsland();
}

islCtls.forEach((ctl, i) => {
  ctl.addEventListener("click", (event) => {
    event.stopPropagation();
    if (state.zone !== "island") focusItem("buttons", rowItems("buttons").indexOf(island), i);
    focusCtl(i);
    if (ctl.dataset.act === "toggle") setPlaying(!isPlaying());
    else if (ctl.dataset.act === "next") changeSong(1);
    // Previous restarts the song after its first few seconds, as players do.
    else if ((previewing() ? ear.audio.currentTime * 1000 : np.at) > 3000) {
      np.at = 0;
      if (previewing()) ear.audio.currentTime = 0;
      paintProgress(true);
    } else changeSong(-1);
  });
});

island.addEventListener("click", () => {
  if (state.zone !== "island") focusItem("buttons", rowItems("buttons").indexOf(island));
});

function tickSong() {
  if (!isPlaying()) return;
  // A playing preview keeps its own time, and moves on when it ends.
  if (previewing()) {
    paintProgress(false);
    return;
  }
  np.at += 1000;
  if (np.at >= SONGS[np.i].ms) changeSong(1);
  else paintProgress(false);
}

document.getElementById("songNext").addEventListener("click", () => {
  if (settings().spotify === "off") setPlaying(true);
  changeSong(1);
});

// ---- Edge glow while music plays ----------------------------------------------------------------------

// After the Mac rim-light apps (Lumn, Open Glow, Rimshot), Apple's Siri glow and the user's reference of
// glowing recap cards: vivid light round the screen's edges, lightest right at the edge and deepening
// into saturated colour as it fades into the black middle. It is never a straight band: its depth
// rolls along the edge like a slow tide and pools in the corners, each kick sends a swell of light out
// from the bottom edge both ways (the highs send smaller ones from the top), the bass deepens it and
// the highs make it sparkle. The hues spread round the cover's main colour, so they never clash. A
// soft inner shadow under it gives depth. Music only: on the TV the launcher would check the playing
// stream's AudioAttributes (MUSIC, not MOVIE), so a trailer or a film's intro stays dark.
//
// Drawn on a canvas at half resolution (the light is soft, so a quarter of the pixels will do): soft
// dabs of light along the rounded edge, added together. No line is drawn at the edge itself: the user
// read it as a hard outline. The TV port would draw the same with one pre-blurred dab bitmap, tinted,
// instead of gradients.

const edge = document.getElementById("edge");
const edgeLight = edge.querySelector(".edge-light");
const lightCtx = edgeLight.getContext("2d");
const EW = edgeLight.width;
const EH = edgeLight.height;
const EDGE_BPM = [86, 94, 104, 103, 90];
const DABS = 220;
const paletteCache = new Map();

// The cover's colours, most vivid first: up to three, each at least 35 degrees round the wheel from the
// others. A one-colour cover gives one.
function coverColors(img) {
  if (paletteCache.has(img.src)) return paletteCache.get(img.src);
  let pair = [FALLBACK_GLOW, FALLBACK_GLOW];
  try {
    const n = 16;
    const canvas = document.createElement("canvas");
    canvas.width = n;
    canvas.height = n;
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    ctx.drawImage(img, 0, 0, n, n);
    const px = ctx.getImageData(0, 0, n, n).data;
    const seen = [];
    for (let i = 0; i < px.length; i += 4) {
      const rgb = [px[i], px[i + 1], px[i + 2]];
      const [h, s, v] = rgbToHsv(...rgb);
      seen.push({ rgb, h, score: s * Math.min(1, v * 1.4) });
    }
    seen.sort((a, b) => b.score - a.score);
    // Up to three: the most vivid colour, then the most vivid ones at least 35 degrees from those taken.
    const apart = (a, b) => Math.min(Math.abs(a - b), 360 - Math.abs(a - b));
    const taken = [seen[0]];
    for (const c of seen) {
      if (taken.length === 3) break;
      if (c.score > 0.2 && taken.every((t) => apart(c.h, t.h) >= 35)) taken.push(c);
    }
    pair = taken.map((c) => c.rgb);
  } catch {
    // A cover the canvas may not read: grey.
  }
  paletteCache.set(img.src, pair);
  return pair;
}

// Three neighbouring shades of the cover's main colour, so the rim never clashes: the colour itself,
// one a little round the wheel each way, the far one lighter. A grey cover gives a soft white.

const light = {
  raf: 0,
  mode: "beat",
  sync: "song",
  next: 0,
  count: 0,
  bass: 0.3,
  high: 0,
  swells: [],
  // Three OKLCH colours, easing towards the cover's.
  now: [
    [62, 0.2, 300],
    [56, 0.22, 255],
    [70, 0.16, 345],
  ],
  goal: null,
};
light.goal = light.now.map((c) => [...c]);

// Points round the screen's rounded edge, evenly spaced, clockwise from the top centre (so the bottom
// centre is halfway). They sit a few pixels outside the screen, so what shows of each dab of light is
// already soft: the very edge never reads as a line. Corners are flagged so the light can pool there.
const rim = (() => {
  const o = 3; // how far outside the edge the light sources sit, at half resolution
  const r = 10 + o; // the screen's 20 dp corner, at half resolution, pushed out with them
  const W = EW + 2 * o;
  const H = EH + 2 * o;
  const w = W - 2 * r;
  const h = H - 2 * r;
  const q = (Math.PI / 2) * r;
  const total = 2 * w + 2 * h + 4 * q;
  const pt = (x, y, corner) => ({ x: x - o, y: y - o, corner });
  const arc = (cx, cy, a) => pt(cx + r * Math.cos(a), cy + r * Math.sin(a), true);
  const at = (d) => {
    if (d < w) return pt(r + d, 0, false);
    d -= w;
    if (d < q) return arc(W - r, r, -Math.PI / 2 + d / r);
    d -= q;
    if (d < h) return pt(W, r + d, false);
    d -= h;
    if (d < q) return arc(W - r, H - r, d / r);
    d -= q;
    if (d < w) return pt(W - r - d, H, false);
    d -= w;
    if (d < q) return arc(r, H - r, Math.PI / 2 + d / r);
    d -= q;
    if (d < h) return pt(0, H - r - d, false);
    d -= h;
    return arc(r, r, Math.PI + d / r);
  };
  return Array.from({ length: DABS }, (_, i) => at(((i / DABS) * total + w / 2) % total));
})();

const mix = (a, b, k) => a + (b - a) * k;
const mixHue = (a, b, k) => (a + ((((b - a + 540) % 360) - 180) * k) + 360) % 360;

// Three colours, as the user liked them ("too many colours now, I liked the earlier one"): the cover's
// main colour and two spread round the wheel from it, in a loop main, second, third, second. Any hue
// that lands on olive leans to amber, since dark yellow reads as olive on black.
const unOlive = (x) => {
  const y = ((x % 360) + 360) % 360;
  return y > 75 && y < 120 ? 68 : y;
};

function paletteAt(u) {
  const loop = [0, 1, 2, 1];
  const x = (((u % 1) + 1) % 1) * 4;
  const i = Math.floor(x);
  const f = x - i;
  const s = f * f * (3 - 2 * f);
  const a = light.now[loop[i]];
  const b = light.now[loop[(i + 1) % 4]];
  return [mix(a[0], b[0], s), mix(a[1], b[1], s), mixHue(a[2], b[2], s)];
}

// A colour as CSS; lift moves it towards white.
function colorOf([l, c, h], alpha, lift = 0) {
  return `oklch(${mix(l, 97, lift).toFixed(1)}% ${(c * (1 - lift * 0.6)).toFixed(3)} ${h.toFixed(1)} / ${alpha.toFixed(3)})`;
}

// The cover's main colour, and two more spread round the wheel from it, vivid on black. A grey cover
// gives a soft, cool silver.
function setEdgeColors() {
  if (!islArt.complete || !islArt.naturalWidth) return;
  const [, c, h] = toOklch(coverColors(islArt)[0]);
  const k = c < 0.03 ? 0.15 : 1;
  light.goal = [
    [62, 0.2 * k, unOlive(h)],
    [56, 0.22 * k, unOlive(h + 305)],
    [70, 0.16 * k, unOlive(h + 45)],
  ];
}

function drawLight(now) {
  const time = now / 1000;
  for (let i = 0; i < light.goal.length; i++) {
    const [a, b] = [light.now[i], light.goal[i]];
    light.now[i] = [mix(a[0], b[0], 0.04), mix(a[1], b[1], 0.04), mixHue(a[2], b[2], 0.04)];
  }
  light.swells = light.swells.filter((s) => now - s.born < 2600);
  // Music mode eases the light up to full: deeper, brighter, breathing slowly with the song.
  light.fullK = (light.fullK || 0) + ((light.full || 0) - (light.fullK || 0)) * 0.03;
  const breath = 1 + (0.05 + light.fullK * 0.15) * Math.sin((time * 2 * Math.PI) / 5.5);
  lightCtx.clearRect(0, 0, EW, EH);
  lightCtx.globalCompositeOperation = "lighter";
  const deep = 1 + light.bass * 0.5;
  const turn = time / 50;
  for (let i = 0; i < DABS; i++) {
    const p = rim[i];
    const t = i / DABS;
    // The tide: three slow waves running round at different speeds, so the light's inner edge rolls.
    const tide =
      0.5 * Math.sin(2 * Math.PI * 3 * t + time * 0.5) +
      0.3 * Math.sin(2 * Math.PI * 5 * t - time * 0.75 + 1.3) +
      0.2 * Math.sin(2 * Math.PI * 8 * t + time * 1.2 + 0.4);
    let swell = 0;
    for (const s of light.swells) {
      const age = (now - s.born) / 1000;
      const amp = s.amp * Math.exp(-age / 0.85);
      const reach = age * 0.3; // a third of the way round each second, both ways
      for (const c of [s.at + reach, s.at - reach]) {
        let d = Math.abs(t - (((c % 1) + 1) % 1));
        d = Math.min(d, 1 - d);
        swell += amp * Math.exp(-(d * d) / 0.0018);
      }
    }
    // On the home screen the light rises from the bottom, where the glow has always been ("it should be
    // at the bottom like earlier"): full along the bottom edge, fading up the sides, none across the top.
    // Music mode lights the whole rim.
    const rise = clamp((p.y / EH - 0.15) / 0.85, 0, 1);
    const lit = mix(rise * rise * (3 - 2 * rise), 1, light.fullK);
    if (lit < 0.01) continue;
    const w = (14 * (1 + tide * 0.45) * deep * (p.corner ? 1.3 : 1) + 14 * swell) * (1 + light.fullK * 0.7) * breath * (0.6 + 0.4 * lit);
    const sparkle = 1 + light.high * 0.4 * Math.sin(i * 12.9898 + time * 21);
    const alpha = lit * Math.min(0.3 + light.fullK * 0.14, (0.085 + swell * 0.09) * sparkle * (1 + light.fullK * 0.6) * breath);
    const col = paletteAt(t + turn);
    const g = lightCtx.createRadialGradient(p.x, p.y, 0, p.x, p.y, w);
    g.addColorStop(0, colorOf(col, alpha, 0.25));
    g.addColorStop(0.3, colorOf(col, alpha * 0.85));
    g.addColorStop(0.7, colorOf(col, alpha * 0.28));
    g.addColorStop(1, colorOf(col, 0));
    lightCtx.fillStyle = g;
    lightCtx.beginPath();
    lightCtx.arc(p.x, p.y, w, 0, Math.PI * 2);
    lightCtx.fill();
  }
  lightCtx.globalCompositeOperation = "source-over";
}

// ---- The beat, from real sound --------------------------------------------------------------------------

// Glow follows: Song plays the track's 30 s Apple Music preview and listens to it, Mic listens to the
// room (music playing anywhere, such as Spotify on this Mac), Tempo fakes it from each song's felt
// tempo. Listening is an FFT, as the Mac rim-light apps do it: a kick is a jump in the 40 to 150 Hz
// band above its recent average (spectral flux against an adaptive threshold), and the highs' own
// jumps, 4 to 10 kHz, send the small swells from the top. On the TV, android.media.audiofx.Visualizer on
// the output mix gives the same spectrum of whatever the TV is playing.
const ear = {
  ctx: null,
  analyser: null,
  audio: null,
  song: null,
  mic: null,
  micSrc: null,
  micAsk: null,
  micOn: false,
  bins: null,
  prev: null,
  low: [],
  high: [],
  lastKick: 0,
  lastHat: 0,
  unlocked: false,
};

function earGraph() {
  if (ear.ctx) return true;
  const Ctx = window.AudioContext || window.webkitAudioContext;
  if (!Ctx) return false;
  ear.ctx = new Ctx();
  ear.analyser = ear.ctx.createAnalyser();
  ear.analyser.fftSize = 2048;
  ear.analyser.smoothingTimeConstant = 0.2;
  ear.bins = new Uint8Array(ear.analyser.frequencyBinCount);
  ear.prev = new Uint8Array(ear.analyser.frequencyBinCount);
  ear.audio = new Audio();
  ear.audio.crossOrigin = "anonymous";
  ear.audio.preload = "auto";
  ear.audio.volume = 0.7;
  ear.audio.addEventListener("ended", () => changeSong(1));
  ear.song = ear.ctx.createMediaElementSource(ear.audio);
  ear.song.connect(ear.ctx.destination);
  return true;
}

function listenTo(source) {
  for (const node of [ear.song, ear.micSrc]) {
    if (!node) continue;
    try {
      node.disconnect(ear.analyser);
    } catch {
      // It wasn't connected.
    }
  }
  if (source === "song") ear.song.connect(ear.analyser);
  if (source === "mic" && ear.micSrc) ear.micSrc.connect(ear.analyser);
  ear.micOn = source === "mic" && !!ear.micSrc;
}

function openMic() {
  ear.micAsk ||= navigator.mediaDevices
    .getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } })
    .then((stream) => {
      ear.mic = stream;
      ear.micSrc = ear.ctx.createMediaStreamSource(stream);
      return true;
    })
    .catch(() => {
      ear.micAsk = null;
      toast("The microphone isn't allowed, so the glow keeps the song's tempo");
      return false;
    });
  return ear.micAsk;
}

// Browsers only let a page make sound after the viewer's first key or click.
function unlockEar() {
  if (ear.unlocked || !earGraph()) return;
  ear.unlocked = true;
  ear.ctx.resume();
  syncSound();
}
addEventListener("keydown", unlockEar, true);
addEventListener("pointerdown", unlockEar, true);

const previewing = () => !!ear.audio && !ear.audio.paused && ear.audio.duration > 0 && ear.audio.dataset.i === String(np.i);
const hearing = () => (light.sync === "song" ? previewing() : light.sync === "mic" && ear.micOn);

// What should be sounding, and what the ear listens to, after any change.
function syncSound() {
  const v = settings();
  const music = v.spotify === "playing" && v.audio !== "video" && state.view === "new";
  if (ear.audio) {
    const song = SONGS[np.i];
    if (v.sync === "song" && music && ear.unlocked && song.preview) {
      if (ear.audio.dataset.i !== String(np.i)) {
        ear.audio.src = song.preview;
        ear.audio.dataset.i = String(np.i);
      }
      if (ear.audio.paused) ear.audio.play().catch(() => {});
    } else if (!ear.audio.paused) ear.audio.pause();
  }
  if (!ear.analyser) return;
  if (v.sync === "mic" && ear.unlocked) openMic().then((ok) => listenTo(ok ? "mic" : null));
  else listenTo(v.sync === "song" ? "song" : null);
}

function onset(hist, x, floor) {
  hist.push(x);
  if (hist.length > 45) hist.shift();
  const mean = hist.reduce((s, v) => s + v, 0) / hist.length;
  const sd = Math.sqrt(hist.reduce((s, v) => s + (v - mean) ** 2, 0) / hist.length);
  return x > Math.max(floor, mean + 1.6 * sd) ? x - mean : 0;
}

// A kick's swell, from the bottom centre; the bot dances to it too.
function kickSwell(now, amp) {
  botKick(now, amp);
  return { at: 0.5, born: now, amp };
}

function hear(now) {
  ear.analyser.getByteFrequencyData(ear.bins);
  const hz = ear.ctx.sampleRate / ear.analyser.fftSize;
  const band = (lo, hi) => {
    const a = Math.max(1, Math.round(lo / hz));
    const b = Math.min(ear.bins.length - 1, Math.round(hi / hz));
    let flux = 0;
    let level = 0;
    for (let i = a; i <= b; i++) {
      const d = ear.bins[i] - ear.prev[i];
      if (d > 0) flux += d;
      level += ear.bins[i];
    }
    const n = (b - a + 1) * 255;
    return [flux / n, level / n];
  };
  const [lowFlux, low] = band(40, 150);
  const [highFlux, high] = band(4000, 10000);
  ear.prev.set(ear.bins);
  const kick = onset(ear.low, lowFlux, 0.035);
  if (kick && now - ear.lastKick > 240) {
    ear.lastKick = now;
    light.swells.push(kickSwell(now, Math.min(1, 0.55 + kick * 5)));
  }
  const hat = onset(ear.high, highFlux, 0.02);
  if (hat && now - ear.lastHat > 420 && now - ear.lastKick > 120) {
    ear.lastHat = now;
    botHat();
    light.swells.push({ at: 0, born: now, amp: 0.35 });
  }
  light.bass += (clamp((low - 0.45) / 0.4, 0, 1) - light.bass) * 0.2;
  light.high += (clamp((high - 0.25) / 0.45, 0, 1) - light.high) * 0.25;
  if (light.swells.length > 10) light.swells.shift();
}

// Steady light sends no swells, but the bot still dances to the song's felt tempo.
function botTempo(now) {
  if (now < light.next) return;
  light.next = now + 60000 / EDGE_BPM[np.i % EDGE_BPM.length];
  if (light.count % 2 === 0) botKick(now, 0.9);
  else botHat();
  light.count = (light.count + 1) % 4;
}

// Without sound to hear: each song's felt tempo, the first and third beats from the bottom.
function tempo(now) {
  if (now >= light.next) {
    light.next = now + 60000 / EDGE_BPM[np.i % EDGE_BPM.length];
    if (light.count % 2 === 0) light.swells.push(kickSwell(now, 0.9));
    else {
      botHat();
      light.swells.push({ at: 0, born: now, amp: 0.35 });
    }
    light.count = (light.count + 1) % 4;
  }
  light.bass = 0.3 + 0.1 * Math.sin(now / 900);
  light.high = 0.15;
}

function stepLight(now) {
  if (light.mode === "beat") {
    if (light.sync !== "tempo" && hearing()) hear(now);
    else tempo(now);
  } else {
    light.bass += (0.25 - light.bass) * 0.05;
    light.high *= 0.9;
    botTempo(now);
  }
  drawLight(now);
  if (botShowing()) drawBot(now);
  light.raf = requestAnimationFrame(stepLight);
}

// ---- Music mode -----------------------------------------------------------------------------------------

// While a song plays and the remote rests, the middle of the screen fades to black and only the edge
// light stays, at full strength and breathing with the song. Music only, as the light is.
const musicMode = { on: false };

function musicPlaying() {
  const v = settings();
  return v.spotify === "playing" && v.audio !== "video" && v.edge !== "off" && state.view === "new";
}

function enterMusicMode() {
  if (musicMode.on || saver.on || !musicPlaying()) return;
  if (state.zone === "island") focusItem("dock", state.index.dock);
  if (!inputs.hidden) closeInputs();
  musicMode.on = true;
  screen.classList.add("music-mode");
  light.full = 1;
  syncEdge();
}

function exitMusicMode() {
  if (!musicMode.on) return;
  musicMode.on = false;
  screen.classList.remove("music-mode");
  light.full = 0;
  syncEdge();
  armIdle();
}

document.getElementById("musicNow").addEventListener("click", () => {
  if (!musicPlaying()) setPlaying(true);
  // After this click's own wake handling, so the click doesn't end it at once.
  setTimeout(enterMusicMode, 0);
});

// ---- The beat bot -----------------------------------------------------------------------------------------

// Round eyes and a small smile that dance to the song in the middle of music mode, in the spirit of the
// ESP32 desk-bot faces (FluxGarage RoboEyes is GPL-3.0, so none of its code is used; this is drawn from
// scratch). The face is the first one, which was the cuter one, in flat white with no glow ("remove the
// glow"); everything new is life around it ("it should blink and dance, more reactions").
//
// It dances on the kicks, a new dance every few seconds: a nod, a sway, hops, side steps, eyes bouncing
// in turn, looking left and right, eyes circling. Its mood changes every few bars: plain, smiling,
// chilled (a contented squint, smaller moves) or curious (one eye bigger, head on one side, a sideways
// smile). In between it does little things on the beat: winks, blinks twice, laughs, glances about, goes
// heart-eyed. And it hears the song: a drop makes it gasp and then beam, a quiet stretch makes it
// sleepy, and the beat coming back wakes it with a start. Springs carry every move, so it lands softly.
const botCanvas = document.getElementById("bot");
const botCtx = botCanvas.getContext("2d");
const BOT_W = 360;
const BOT_H = 240;
const BOT_MOVES = ["nod", "sway", "hop", "step", "bounce", "look", "circle"];
const BOT_MOODS = ["plain", "smile", "chill", "curious", "smile"];
const BOT_GESTURES = ["wink", "blink", "giggle", "glance", "hearts"];
const BOT_GESTURE_MS = { wink: 750, blink: 500, giggle: 1100, glance: 1300, hearts: 1900 };
const bot = {
  last: 0,
  beats: 0,
  move: "nod",
  moveUntil: 0,
  mood: "plain",
  moodUntil: 0,
  gesture: "",
  gestureAt: 0,
  gestureUntil: 0,
  gestureNext: 0,
  // The body: springs for position, tilt and squash.
  x: 0,
  xv: 0,
  xGoal: 0,
  y: 0,
  yv: 0,
  tilt: 0,
  tiltV: 0,
  tiltGoal: 0,
  squash: 0,
  squashV: 0,
  // Each eye bounces on its own in the bounce dance.
  eyeL: 0,
  eyeLV: 0,
  eyeR: 0,
  eyeRV: 0,
  // Where the eyes look.
  gx: 0,
  gy: 0,
  idleX: 0,
  idleY: 0,
  nextLook: 0,
  lookSide: 1,
  spin: 0,
  spinGoal: 0,
  // Expressions, eased between 0 and 1.
  smile: 0,
  joy: 0,
  chill: 0,
  curious: 0,
  wink: 0,
  giggle: 0,
  love: 0,
  sleepy: 0,
  surprise: 0,
  joyUntil: 0,
  joyNext: 0,
  surpriseUntil: 0,
  surpriseNext: 0,
  blinkAt: -1e9,
  twice: false,
  nextBlink: 0,
  lastKick: 0,
  bassSlow: 0.3,
};

const botShowing = () => musicMode.on && botCanvas.hasAttribute("data-on");

const pickNew = (list, current) => {
  const options = list.filter((x) => x !== current);
  return options[Math.floor(Math.random() * options.length)];
};

function botBlink(now, twice = false) {
  bot.blinkAt = now;
  bot.twice = twice;
  bot.nextBlink = now + 1600 + Math.random() * 3200;
}

function botSurprise(now) {
  bot.surpriseUntil = now + 800;
  bot.joyUntil = now + 2400;
  bot.yv -= 260;
}

function botGesture(now, name) {
  bot.gesture = name;
  bot.gestureAt = now;
  bot.gestureUntil = now + BOT_GESTURE_MS[name];
  bot.gestureNext = now + 3000 + Math.random() * 3000;
  if (name === "blink") botBlink(now, true);
}

function botKick(now, amp) {
  const woke = now - bot.lastKick > 3500 && bot.sleepy > 0.3;
  bot.lastKick = now;
  if (!botShowing()) return;
  // The beat coming back after a quiet stretch: awake with a start.
  if (woke) botSurprise(now);
  bot.beats += 1;
  // A new dance every few seconds and a new mood every few bars, always on a beat, with a blink.
  if (now > bot.moveUntil) {
    bot.move = pickNew(BOT_MOVES, bot.move);
    bot.moveUntil = now + 4000 + Math.random() * 2500;
    botBlink(now);
  }
  if (now > bot.moodUntil) {
    bot.mood = pickNew(BOT_MOODS, bot.mood);
    bot.moodUntil = now + 7000 + Math.random() * 5000;
  }
  const side = bot.beats % 2 ? 1 : -1;
  const a = amp * (bot.mood === "chill" ? 0.6 : 1);
  bot.xGoal = 0;
  bot.tiltGoal = 0;
  if (bot.move === "nod") {
    // Down on the beat, as a head nods.
    bot.yv += 240 * a;
    bot.squashV -= 4 * a;
  } else if (bot.move === "sway") {
    bot.tiltGoal = side * (7 + 5 * a);
    bot.xGoal = side * 8;
    bot.squashV -= 2 * a;
  } else if (bot.move === "hop") {
    bot.yv -= 480 * a;
    bot.squashV += 4 * a;
  } else if (bot.move === "step") {
    bot.xGoal = side * 26;
    bot.tiltGoal = side * 3;
    bot.yv += 120 * a;
    bot.squashV -= 2 * a;
  } else if (bot.move === "bounce") {
    if (side > 0) bot.eyeLV -= 380 * a;
    else bot.eyeRV -= 380 * a;
    bot.squashV -= 1.5 * a;
  } else if (bot.move === "look") {
    bot.lookSide = side;
    bot.yv += 100 * a;
  } else if (bot.move === "circle") {
    bot.spinGoal += Math.PI / 2;
    bot.yv += 80 * a;
  }
  // Something small every few seconds; the biggest hits sometimes bring hearts or a delighted squint.
  if (now > bot.gestureUntil) {
    if (amp > 0.95 && now > bot.joyNext) {
      bot.joyNext = now + 5000;
      if (Math.random() < 0.5) botGesture(now, "hearts");
      else bot.joyUntil = now + 1300;
    } else if (now > bot.gestureNext) botGesture(now, pickNew(BOT_GESTURES, bot.gesture));
  }
}

// The off-beats: a small tick, and now and then the eyes flick to the side and back.
function botHat() {
  if (!botShowing()) return;
  bot.squashV -= 1;
  if (Math.random() < 0.3) bot.gx += (Math.random() < 0.5 ? -1 : 1) * 7;
}

// A damped spring: returns the new [value, velocity] towards the goal.
function spring(x, v, goal, k, c, dt) {
  const a = -k * (x - goal) - c * v;
  v += a * dt;
  return [x + v * dt, v];
}

const ease = (x, goal, rate, dt) => x + (goal - x) * Math.min(1, dt * rate);

// From 0 to 1 with a little overshoot, for the hearts popping in.
const popOut = (t) => 1 + 2.7 * (t - 1) ** 3 + 1.7 * (t - 1) ** 2;

function drawHeart(cx, cy, s) {
  const ctx = botCtx;
  ctx.beginPath();
  ctx.moveTo(cx, cy + 0.42 * s);
  ctx.bezierCurveTo(cx - 0.62 * s, cy + 0.02 * s, cx - 0.56 * s, cy - 0.56 * s, cx, cy - 0.24 * s);
  ctx.bezierCurveTo(cx + 0.56 * s, cy - 0.56 * s, cx + 0.62 * s, cy + 0.02 * s, cx, cy + 0.42 * s);
  ctx.fill();
}

function drawBot(now) {
  const dt = Math.min(0.05, (now - (bot.last || now)) / 1000);
  bot.last = now;

  // What the song is doing: a sudden jump in the bass (a drop), or a long quiet.
  bot.bassSlow += (light.bass - bot.bassSlow) * Math.min(1, dt * 0.8);
  if (light.bass - bot.bassSlow > 0.32 && now > bot.surpriseNext) {
    botSurprise(now);
    bot.surpriseNext = now + 9000;
  }
  const quiet = now - bot.lastKick > 3500;
  const gesture = now < bot.gestureUntil ? bot.gesture : "";

  // Where the eyes look: darting about in a glance, the look dance, circling, or idle glances.
  let tx;
  let ty;
  if (gesture === "glance") {
    tx = (Math.floor((now - bot.gestureAt) / 320) % 2 ? 1 : -1) * 24;
    ty = -3;
  } else if (bot.move === "look" && !quiet) {
    tx = bot.lookSide * 22;
    ty = -2;
  } else if (bot.move === "circle" && !quiet) {
    bot.spin = ease(bot.spin, bot.spinGoal, 7, dt);
    tx = Math.cos(bot.spin) * 18;
    ty = Math.sin(bot.spin) * 9;
  } else {
    if (now > bot.nextLook) {
      bot.idleX = (Math.random() * 2 - 1) * 14;
      bot.idleY = (Math.random() * 2 - 1) * 6;
      bot.nextLook = now + 1500 + Math.random() * 2500;
    }
    tx = bot.idleX;
    ty = bot.idleY;
  }
  bot.gx = ease(bot.gx, tx, 10, dt);
  bot.gy = ease(bot.gy, ty, 10, dt);

  // Blinks every couple of seconds, sometimes twice.
  if (now > bot.nextBlink) botBlink(now, Math.random() < 0.25);
  const since = (now - bot.blinkAt) / 1000;
  const blinkShape = (t) => (t < 0 ? 0 : t < 0.07 ? t / 0.07 : t < 0.19 ? 1 - (t - 0.07) / 0.12 : 0);
  const blink = Math.max(blinkShape(since), bot.twice ? blinkShape(since - 0.24) : 0);

  // Springs.
  [bot.x, bot.xv] = spring(bot.x, bot.xv, bot.xGoal, 90, 12, dt);
  [bot.y, bot.yv] = spring(bot.y, bot.yv, 0, 170, 12, dt);
  [bot.tilt, bot.tiltV] = spring(bot.tilt, bot.tiltV, bot.tiltGoal, 70, 10, dt);
  [bot.squash, bot.squashV] = spring(bot.squash, bot.squashV, 0, 220, 13, dt);
  [bot.eyeL, bot.eyeLV] = spring(bot.eyeL, bot.eyeLV, 0, 200, 12, dt);
  [bot.eyeR, bot.eyeRV] = spring(bot.eyeR, bot.eyeRV, 0, 200, 12, dt);

  // Expressions.
  const mood = quiet ? "" : bot.mood;
  bot.surprise = ease(bot.surprise, now < bot.surpriseUntil ? 1 : 0, 12, dt);
  bot.joy = ease(bot.joy, now < bot.joyUntil && now > bot.surpriseUntil ? 1 : 0, 9, dt);
  bot.sleepy = ease(bot.sleepy, quiet ? 1 : 0, quiet ? 1.2 : 6, dt);
  bot.smile = ease(bot.smile, mood === "smile" ? 1 : 0, 4, dt);
  bot.chill = ease(bot.chill, mood === "chill" ? 1 : 0, 4, dt);
  bot.curious = ease(bot.curious, mood === "curious" ? 1 : 0, 4, dt);
  bot.wink = ease(bot.wink, gesture === "wink" ? 1 : 0, 14, dt);
  bot.giggle = ease(bot.giggle, gesture === "giggle" ? 1 : 0, 10, dt);
  bot.love = ease(bot.love, gesture === "hearts" ? 1 : 0, 7, dt);

  const ctx = botCtx;
  ctx.setTransform(2, 0, 0, 2, 0, 0);
  ctx.clearRect(0, 0, BOT_W, BOT_H);
  const shake = Math.sin(now / 38) * 3 * bot.giggle;
  ctx.translate(BOT_W / 2 + bot.x, BOT_H / 2 - 8 + bot.y + shake + bot.sleepy * 6);
  ctx.rotate(((bot.tilt + bot.curious * 7 + bot.sleepy * 5) * Math.PI) / 180);
  ctx.fillStyle = "oklch(96% 0 none)";
  ctx.strokeStyle = "oklch(96% 0 none)";
  ctx.lineCap = "round";

  const sq = clamp(bot.squash, -0.4, 0.4);
  const big = 1 + bot.surprise * 0.22;
  const joy = Math.max(bot.joy, bot.giggle) * (1 - bot.surprise);
  // Shorter eyes: a contented squint when chilled, lifted by a smile, half shut and breathing when sleepy.
  const short = (1 - 0.25 * bot.chill) * (1 - 0.12 * bot.smile) * (1 - bot.sleepy * (0.55 + 0.04 * Math.sin(now / 650)));
  // Heart eyes: the eyes shrink away, then hearts pop in and throb on the beat.
  const eyeScale = clamp(1 - bot.love * 2, 0, 1);
  const heartScale = bot.love > 0.5 ? popOut((bot.love - 0.5) * 2) : 0;
  for (const side of [-1, 1]) {
    const left = side < 0;
    const x = side * 52 + bot.gx;
    const y = bot.gy + (left ? bot.eyeL : bot.eyeR) - 3 * bot.smile + 5 * bot.sleepy;
    if (heartScale > 0) {
      drawHeart(x, y, 64 * big * heartScale * (1 - sq * 0.4));
      continue;
    }
    if (eyeScale < 0.03) continue;
    const size = big * eyeScale * (left ? 1 - 0.1 * bot.curious : 1 + 0.16 * bot.curious);
    const w = 66 * size * (1 - sq * 0.3);
    const h = Math.max(4, 76 * size * short * (1 + sq * 0.6) * (1 - blink * 0.95));
    // Happy: the eye closes to a line, and the line bends up into an arch like ^, thick with round ends
    // (a wink is one of them). No fading between the two, so it never goes grey on the way.
    const arch = left ? joy : Math.max(joy, bot.wink);
    if (arch < 0.5) {
      const t = arch * 2;
      const hh = h * (1 - t) + 10 * t;
      ctx.beginPath();
      ctx.roundRect(x - w / 2, y - hh / 2, w, hh, Math.min(24 + 10 * bot.surprise, hh / 2, w / 2));
      ctx.fill();
    } else {
      const k = (arch - 0.5) * 2;
      const reach = w * (0.42 - 0.06 * k);
      ctx.lineWidth = (10 + 5 * k) * size;
      ctx.beginPath();
      ctx.moveTo(x - reach, y + 10 * k);
      ctx.quadraticCurveTo(x, y - 30 * k, x + reach, y + 10 * k);
      ctx.stroke();
    }
  }

  // The smile: wider when happy, open in a laugh, an "o" when it gasps, a short flat line when sleepy,
  // pulled to one side when curious or winking.
  const grin = Math.max(bot.smile, joy, bot.love, bot.chill * 0.5);
  const mx = bot.gx * 0.5 + 6 * bot.wink - 6 * bot.curious;
  const my = 50 + bot.gy * 0.5;
  const mw = (11 + 6 * grin) * (1 - 0.4 * bot.sleepy) * (1 - 0.3 * bot.curious);
  const md = (7 + 6 * grin + 10 * bot.giggle) * (1 - bot.sleepy) * (1 - 0.6 * bot.curious);
  ctx.lineWidth = 3.5;
  if (bot.surprise < 0.98) {
    ctx.beginPath();
    ctx.moveTo(mx - mw, my);
    ctx.quadraticCurveTo(mx, my + md, mx + mw, my);
    if (bot.giggle > 0.02) {
      ctx.globalAlpha = bot.giggle * (1 - bot.surprise);
      ctx.fill();
    }
    ctx.globalAlpha = 1 - bot.surprise;
    ctx.stroke();
  }
  if (bot.surprise > 0.02) {
    ctx.globalAlpha = bot.surprise;
    ctx.beginPath();
    ctx.ellipse(mx, my + 4, 5.5, 7, 0, 0, Math.PI * 2);
    ctx.stroke();
  }
  ctx.globalAlpha = 1;
}

function syncEdge() {
  const v = settings();
  const on = v.edge !== "off" && v.spotify === "playing" && v.audio !== "video" && state.view === "new";
  edge.toggleAttribute("data-on", on);
  edge.classList.toggle("over-saver", saver.on);
  botCanvas.toggleAttribute("data-on", v.bot !== "off");
  light.mode = v.edge || "beat";
  light.sync = v.sync || "song";
  if (musicMode.on && !on) {
    musicMode.on = false;
    screen.classList.remove("music-mode");
    light.full = 0;
  }
  const moving = on && !reduceMotion.matches;
  if (moving && !light.raf) {
    light.next = performance.now();
    light.raf = requestAnimationFrame(stepLight);
  } else if (!moving && light.raf) {
    cancelAnimationFrame(light.raf);
    light.raf = 0;
  }
  if (on && reduceMotion.matches) {
    drawLight(0);
    drawBot(performance.now());
  }
  syncSound();
}

islArt.addEventListener("load", setEdgeColors);

// ---- Prototype controls ----------------------------------------------------------------------------

const STORE_KEY = "baron-tv-prototype-v4";

function load() {
  try {
    return JSON.parse(localStorage.getItem(STORE_KEY)) || {};
  } catch {
    return {};
  }
}

function save() {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(settings()));
  } catch {
    // Private window or blocked storage: the choice just isn't remembered.
  }
}

function apply() {
  const v = settings();
  state.view = v.view;
  screen.hidden = v.view !== "new";
  nowShot.hidden = v.view !== "now";
  screen.dataset.font = v.font;
  screen.dataset.clock = v.clock || "widget";
  screen.dataset.layout = v.layout || "sidebar";
  ambient.dataset.glow = v.glow || "refined";
  if (v.glow === "off") hideGlow();
  paintGlyphs();
  renderWeather();
  syncIsland();
  // Music starting opens the island for a moment.
  if (np.was === "off" && v.spotify === "playing") setTimeout(peekIsland, 350);
  np.was = v.spotify;
}

for (const [name, value] of Object.entries(load())) {
  const input = controls.querySelector(`input[name="${name}"][value="${value}"]`);
  if (input) input.checked = true;
}

controls.addEventListener("change", () => {
  apply();
  save();
  if (state.view === "new") focusItem(state.row, state.index[state.row]);
});

// Fit the 960x540 canvas to the space beside the controls panel, or above it on a narrow window.
const stacked = matchMedia("(max-width: 1040px)");
function fit() {
  const stage = document.querySelector(".stage");
  const width = stage.clientWidth - 48;
  const height = (stacked.matches ? innerHeight * 0.75 : innerHeight) - 48;
  frame.style.setProperty("--fit", Math.max(0.4, Math.min(width / 960, height / 540)).toFixed(3));
}

addEventListener("resize", fit);

renderDock();
renderPosters(PLACEHOLDERS);
renderChips();
renderSideDock();
SONGS.forEach((song, i) => (song.cover = drawnCover(i)));
showSong();
apply();
loadCovers();
setInterval(tickSong, 1000);
// Open Runde arrives after the first layout; size the pill again on the real font.
document.fonts.ready.then(sizeIsland);
tick();
setInterval(tick, 15000);
loadWeather();
setInterval(loadWeather, WEATHER_EVERY_MS);
loadTrending();
setInterval(loadTrending, TMDB_EVERY_MS);
fit();
// Start on YouTube in the dock once its cover is in, so the glow starts in its red.
const firstBanner = dockEl.querySelector("img");
if (firstBanner.complete) focusItem("dock", 0);
else firstBanner.addEventListener("load", () => focusItem("dock", 0), { once: true });
armIdle();
