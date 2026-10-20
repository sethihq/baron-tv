// Baron TV home prototype. Page 1: clock, round controls, what's trending on your apps, the dock.
// Page 2, under the dock: inputs, shortcuts and the Bengaluru weather. Arrow keys are the D-pad,
// Enter or a click is OK, Esc or Backspace is Back. Nothing here talks to the TV; the weather comes
// from Open-Meteo and the shelf from TMDB, as the launcher would fetch them.

// Dock order as the user set it, then the TV's own apps. Each tile shows the app's TV banner from its
// APK; Netflix isn't on this TV, so its tile is drawn from the Netflix mark.
const APPS = [
  { name: "Prime Video", banner: "assets/apps/primevideo-banner.png" },
  { name: "Netflix", banner: "assets/apps/netflix-banner.svg" },
  { name: "YouTube", banner: "assets/apps/youtube-banner.png" },
  { name: "JioHotstar", banner: "assets/apps/jiohotstar-banner.png" },
  { name: "Spotify", banner: "assets/apps/spotify-banner.png" },
  { name: "App Store", banner: "assets/apps/appstore-banner.png" },
  { name: "Media Center", banner: "assets/apps/media-banner.png" },
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
const ORDER = ["buttons", "posters", "dock", "chips"];
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
const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)");

const state = {
  view: "new",
  row: "dock",
  zone: "grid",
  page: 0,
  input: 0,
  index: { buttons: 0, posters: 0, dock: 0, chips: 0 },
};
let posterItems = PLACEHOLDERS;
let reading = null;

const clamp = (n, lo, hi) => Math.max(lo, Math.min(n, hi));
const settings = () => Object.fromEntries(new FormData(controls));
const rowItems = (row) =>
  row === "buttons"
    ? rounds
    : row === "posters"
      ? [...postersEl.children]
      : row === "dock"
        ? [...dockEl.children]
        : [...document.querySelectorAll(".chip")];

function glyph(icon) {
  const span = document.createElement("span");
  span.className = "glyph";
  span.dataset.icon = icon;
  span.setAttribute("aria-hidden", "true");
  return span;
}

// Phosphor glyphs are CSS masks, so they take the text colour; the weight comes from the Icons control.
function paintGlyphs() {
  const weight = settings().ui;
  for (const g of screen.querySelectorAll(".glyph")) {
    g.style.setProperty("--src", `url("assets/icons/phosphor/${weight}/${g.dataset.icon}.svg")`);
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
      img.src = app.banner;
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

function chipFor(label, icon, onPress, idle) {
  const chip = document.createElement("button");
  chip.type = "button";
  chip.className = "chip";
  if (idle) chip.dataset.idle = "";
  const name = document.createElement("span");
  name.textContent = label;
  chip.append(glyph(icon), name);
  chip.addEventListener("click", () => {
    focusItem("chips", rowItems("chips").indexOf(chip));
    onPress();
  });
  return chip;
}

function renderChips() {
  inputChips.replaceChildren(
    ...INPUTS.map((input) =>
      chipFor(input.name, input.icon, () => toast(input.live ? `Switching to ${input.name}` : `Nothing is plugged into ${input.name}`), !input.live),
    ),
  );
  shortcutChips.replaceChildren(...SHORTCUTS.map((s) => chipFor(s.name, s.icon, () => toast(s.says))));
}

// ---- Geometry ------------------------------------------------------------------------------------

// An element's box in the content layer, before any focus scale.
function rectIn(el) {
  let x = 0;
  let y = 0;
  for (let n = el; n && n !== content; n = n.offsetParent) {
    x += n.offsetLeft;
    y += n.offsetTop;
  }
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
}

function glowFor(el) {
  const img = el.querySelector("img");
  const rgb = img && img.complete && img.naturalWidth ? vividColor(img) : FALLBACK_GLOW;
  if (!glowShown) {
    glowShown = true;
    setGlow(rgb);
    fade(ambient, 1, 600);
  } else if (rgb.join(",") !== glowKey) {
    fade(ambient, 0.35, 200, () => {
      setGlow(rgb);
      fade(ambient, 1, 500);
    });
  }
}

function hideGlow() {
  if (!glowShown) return;
  glowShown = false;
  fade(ambient, 0, 300);
}

// ---- Focus -----------------------------------------------------------------------------------------

function focusItem(row, i) {
  const items = rowItems(row);
  if (!items.length) return;
  const index = clamp(i, 0, items.length - 1);
  state.row = row;
  state.zone = "grid";
  state.index[row] = index;
  if (row === "buttons" || row === "posters") state.page = 0;
  if (row === "chips") state.page = 1;
  content.style.setProperty("--page-scroll", `${state.page ? -(dockEl.offsetTop - PAGE2_DOCK_TOP) : 0}px`);
  const el = items[index];
  el.focus({ preventScroll: true });
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
  focusItem("buttons", 0);
}

function focusInputRow(i) {
  state.input = clamp(i, 0, inputRows.length - 1);
  inputRows[state.input].focus({ preventScroll: true });
}

function move(key) {
  if (state.zone === "inputs") {
    if (key === "ArrowUp") focusInputRow(state.input - 1);
    if (key === "ArrowDown") focusInputRow(state.input + 1);
    return;
  }
  const row = state.row;
  const items = rowItems(row);
  const i = state.index[row];
  if (key === "ArrowLeft") {
    if (i > 0) focusItem(row, i - 1);
    return;
  }
  if (key === "ArrowRight") {
    if (i < items.length - 1) focusItem(row, i + 1);
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
    focusItem("buttons", i);
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
    else if (state.page === 1) focusItem("dock", state.index.dock);
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
  saver.on = true;
  clearTimeout(idleTimer);
  if (!inputs.hidden) closeInputs();
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
  armIdle();
}

function armIdle() {
  clearTimeout(idleTimer);
  if (!saver.on) idleTimer = setTimeout(startSaver, Number(settings().saver || 600) * 1000);
}

// While the screensaver is up, the first key or click only wakes it, as a remote does on the TV.
// Anything else restarts the idle clock.
addEventListener(
  "keydown",
  (event) => {
    if (saver.on) {
      event.preventDefault();
      event.stopImmediatePropagation();
      stopSaver();
    } else {
      armIdle();
    }
  },
  true,
);

addEventListener(
  "pointerdown",
  (event) => {
    if (saver.on) {
      event.preventDefault();
      event.stopImmediatePropagation();
      stopSaver();
    } else {
      armIdle();
    }
  },
  true,
);

let lastMoveArm = 0;
addEventListener("pointermove", () => {
  if (saver.on || performance.now() - lastMoveArm < 500) return;
  lastMoveArm = performance.now();
  armIdle();
});

document.getElementById("saverNow").addEventListener("click", startSaver);
controls.addEventListener("change", armIdle);

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
  paintGlyphs();
  renderWeather();
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

// Fit the 960x540 canvas to the window.
function fit() {
  const chrome = document.querySelector(".bar").offsetHeight + document.querySelector(".hint").offsetHeight;
  const scale = Math.min((innerWidth - 48) / 960, (innerHeight - chrome - 24) / 540);
  frame.style.setProperty("--fit", Math.max(0.4, scale).toFixed(3));
}

addEventListener("resize", fit);

renderDock();
renderPosters(PLACEHOLDERS);
renderChips();
apply();
tick();
setInterval(tick, 15000);
loadWeather();
setInterval(loadWeather, WEATHER_EVERY_MS);
loadTrending();
setInterval(loadTrending, TMDB_EVERY_MS);
fit();
// Start on YouTube in the dock once its banner is in, so the glow starts in its red.
const firstBanner = dockEl.querySelector("img");
if (firstBanner.complete) focusItem("dock", 0);
else firstBanner.addEventListener("load", () => focusItem("dock", 0), { once: true });
armIdle();
