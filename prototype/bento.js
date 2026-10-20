// Clock: Bento (bento.css). Fills the top-right bento: a watch dial, the time under a greeting, the sky
// right now in its own colour, and a line with the date and what the weather does next. It reads
// app.js's globals (reading, settings, controls, glyph, weatherIcon, paintGlyphs) and changes none of
// them. The dial ticks every second; the rest redraws when the weather reading, the day or the icon
// settings change.

(() => {
  const root = document.getElementById("bento");
  if (!root) return;

  const SVG = "http://www.w3.org/2000/svg";
  const hourHand = root.querySelector(".dial-h");
  const minuteHand = root.querySelector(".dial-m");
  const secondHand = root.querySelector(".dial-s");
  const greetEl = document.getElementById("btGreet");
  const timeEl = document.getElementById("btTime");
  const ampmEl = document.getElementById("btAmpm");
  const sky = document.getElementById("btSky");
  const skyIcon = document.getElementById("btSkyIcon");
  const tempEl = document.getElementById("btTemp");
  const line = document.getElementById("btLine");

  // Twelve dots round the dial, bigger at the quarters.
  const dots = root.querySelector(".dial-dots");
  for (let i = 0; i < 12; i++) {
    const a = (i * Math.PI) / 6;
    const dot = document.createElementNS(SVG, "circle");
    dot.setAttribute("cx", (50 + 38 * Math.sin(a)).toFixed(2));
    dot.setAttribute("cy", (50 - 38 * Math.cos(a)).toFixed(2));
    dot.setAttribute("r", i % 3 === 0 ? "3.8" : "2.4");
    dots.append(dot);
  }

  // The greeting, and a shorter way to say it for a narrow tile: it is never cut off.
  const greetingFor = (h) =>
    h >= 5 && h < 12 ? ["Good morning", "Morning"] : h >= 12 && h < 17 ? ["Good afternoon", "Afternoon"] : h >= 17 && h < 22 ? ["Good evening", "Evening"] : ["Good night", "Night"];

  function fitGreeting(h) {
    const [full, short] = greetingFor(h);
    greetEl.textContent = full;
    if (greetEl.scrollWidth > greetEl.clientWidth) greetEl.textContent = short;
  }

  // Open-Meteo's glyph names, as app.js's describe() gives them, to the square's sky.
  const SKY = {
    sun: "sun",
    moon: "night",
    "cloud-sun": "partly",
    "cloud-moon": "night",
    cloud: "cloud",
    "cloud-fog": "cloud",
    "cloud-snow": "cloud",
    "cloud-rain": "rain",
    "cloud-lightning": "storm",
  };

  const kindOf = (w) =>
    /lightning/.test(w.ph) ? "storm" : /rain/.test(w.ph) ? "rain" : /snow/.test(w.ph) ? "snow" : /^cloud(-fog)?$/.test(w.ph) ? "cloud" : "fair";
  const wet = (k) => k === "rain" || k === "storm" || k === "snow";
  const SHORT = { Thunderstorms: "Storms", "Light rain": "Rain", "Light showers": "Showers", "Heavy showers": "Downpour", "Freezing rain": "Rain" };

  // What the weather does next, from the coming hours: the first hour that changes its kind.
  // Words are [text, strong]: strong words are white, the rest stay grey.
  function nextFor(r) {
    const now = kindOf(r);
    const next = r.hours.find((h) => kindOf(h) !== now);
    if (!next) {
      if (wet(now)) return { icon: r.ph, words: [[SHORT[r.text] || r.text, 1], ["all evening", 0]] };
      return { icon: r.ph, words: [[now === "cloud" ? "Cloudy" : "Clear", 1], ["till late", 0]] };
    }
    const k = kindOf(next);
    const word = wet(k) ? SHORT[next.text] || next.text : wet(now) ? "Dry" : k === "cloud" ? "Clouds" : "Clearing";
    return { icon: next.ph, words: [[word, 1], ["from", 0], [next.label, 1]] };
  }

  function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function drawLine(r) {
    const now = new Date();
    const wday = new Intl.DateTimeFormat("en-GB", { weekday: "short" }).format(now).toUpperCase();
    const parts = [el("span", "bt-wday", wday), el("b", "", String(now.getDate())), el("span", "bt-sep", "·")];
    if (r) {
      const next = nextFor(r);
      const icon = weatherIcon({ ...r, ph: next.icon, mc: (r.hours.find((h) => h.ph === next.icon) || r).mc });
      parts.push(icon, ...next.words.map(([text, strong]) => el(strong ? "b" : "span", "", text)));
    } else {
      parts.push(el("span", "", "Bengaluru"));
    }
    line.replaceChildren(...parts);
    line.removeAttribute("data-tight");
    paintGlyphs();
    // A long forecast word loses the glyph first, then steps down a size.
    if (line.scrollWidth > line.clientWidth) {
      const icon = line.querySelector(".glyph, img");
      if (icon) icon.remove();
    }
    if (line.scrollWidth > line.clientWidth) line.setAttribute("data-tight", "");
  }

  function drawWeather() {
    const r = reading;
    sky.dataset.sky = r ? SKY[r.ph] || "cloud" : "none";
    tempEl.textContent = r ? `${r.temp}°` : "--°";
    skyIcon.replaceChildren(r ? weatherIcon(r) : glyph("cloud"));
    sky.setAttribute("aria-label", r ? `${r.temp} degrees, ${r.text}` : "Weather loading");
    drawLine(r);
  }

  // The second hand keeps counting past 59, so the tick from 59 to 0 doesn't spin it backwards.
  let turn = 0;
  let lastSecond = -1;
  let seen = { reading: undefined, day: "", look: "" };

  function tick() {
    const now = new Date();
    const h = now.getHours();
    const m = now.getMinutes();
    const s = now.getSeconds();
    hourHand.style.rotate = `${(h % 12) * 30 + m * 0.5}deg`;
    minuteHand.style.rotate = `${m * 6 + s * 0.1}deg`;
    turn = lastSecond < 0 ? s * 6 : turn + ((s - lastSecond + 60) % 60) * 6;
    lastSecond = s;
    secondHand.style.rotate = `${turn}deg`;
    fitGreeting(h);
    timeEl.textContent = `${h % 12 || 12}:${String(m).padStart(2, "0")}`;
    ampmEl.textContent = h < 12 ? "am" : "pm";

    const v = settings();
    const look = `${v.weather}|${v.ui}`;
    const day = now.toDateString();
    if (reading !== seen.reading || day !== seen.day || look !== seen.look) {
      seen = { reading, day, look };
      drawWeather();
    }
  }

  tick();
  setInterval(tick, 1000);
  // Open Runde arrives after the first layout, and the bento changes width when it moves between the
  // corner and the sidebar column (or appears after being hidden): fit the greeting and the line again.
  const refit = () => {
    fitGreeting(new Date().getHours());
    drawLine(reading);
  };
  document.fonts.ready.then(refit);
  let width = 0;
  new ResizeObserver(() => {
    if (root.clientWidth === width) return;
    width = root.clientWidth;
    if (width) refit();
  }).observe(root);
})();
