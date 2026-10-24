// Renders every decal style of the generator page (page.html) into the mod's textures/effect/, with the author's
// settings from the page's last "send to mod" (exports/latest.json; a setting missing there keeps the page's
// default). Each style is one texture, a grid of 8 x 8 variants (Decals.GRID): hole_<style>.png, scratch_<style>.png,
// scorch_<style>.png; "old" (hole_old.png) is the mod's first 8 holes (old/bullet_hole_0..7.png), repeated over the
// grid. Black only: alpha is how dark a pixel is.
// The generator code is taken from page.html itself, so the files match the page's previews.
// Usage (repo root): notes/node/bin/node review/decals/render.js
"use strict";
const fs = require("fs"), path = require("path"), zlib = require("zlib");

const GRID = 8;
const here = __dirname;
const html = fs.readFileSync(path.join(here, "page.html"), "utf8");
const script = html.slice(html.indexOf("<script>") + 8, html.lastIndexOf("</script>"));
const cut = (from, to) => {
  const a = script.indexOf(from), b = script.indexOf(to, a);
  if (a < 0 || b < 0) throw new Error("page.html changed: " + from);
  return script.slice(a, b);
};
// noise, STYLES, COMMON, CATS, defaults; then renderTexture
const code = cut("const TAU", "/* ------------------------------------------------------------------ state */")
  + cut("function defaults()", "function load()")
  + cut("function renderTexture", "function toCanvas");

class ImageData {
  constructor(w, h) { this.width = w; this.height = h; this.data = new Uint8ClampedArray(w * h * 4); }
}
const api = new Function("ImageData", code + "\nreturn { STYLES, CATS, defaults, renderTexture };")(ImageData);

const S = api.defaults();
const exp = JSON.parse(fs.readFileSync(path.join(here, "exports", "latest.json"), "utf8")).settings;
for (const cat in api.CATS) {
  const e = exp[cat];
  if (!e) continue;
  Object.assign(S.cats[cat].common, e.common);
  // version 1 sent one style's dials (style + params), later ones every style's
  if (e.styles) for (const sk in e.styles) { if (S.cats[cat].styles[sk]) Object.assign(S.cats[cat].styles[sk], e.styles[sk]); }
  else if (S.cats[cat].styles[e.style]) Object.assign(S.cats[cat].styles[e.style], e.params);
}
const seedFor = (cat, i) => (S.cats[cat].common.seed * 7919 + i * 104729 + 1) >>> 0;

function png(w, h, data) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    Buffer.from(data.buffer, data.byteOffset + y * w * 4, w * 4).copy(raw, y * (w * 4 + 1) + 1);
  }
  const chunk = (type, body) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(body.length);
    const tb = Buffer.concat([Buffer.from(type, "ascii"), body]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(zlib.crc32(tb));
    return Buffer.concat([len, tb, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}

// an 8-bit RGBA, non-interlaced PNG (the old holes) -> {w, h, data}
function readPng(file) {
  const b = fs.readFileSync(file);
  let o = 8, w = 0, h = 0;
  const idat = [];
  while (o < b.length) {
    const len = b.readUInt32BE(o), type = b.toString("ascii", o + 4, o + 8), body = b.subarray(o + 8, o + 8 + len);
    if (type === "IHDR") {
      w = body.readUInt32BE(0); h = body.readUInt32BE(4);
      if (body[8] !== 8 || body[9] !== 6 || body[12] !== 0) throw new Error(file + ": not 8-bit RGBA non-interlaced");
    } else if (type === "IDAT") idat.push(body);
    o += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat)), data = new Uint8ClampedArray(w * h * 4), st = w * 4;
  for (let y = 0; y < h; y++) {
    const f = raw[y * (st + 1)], row = y * (st + 1) + 1;
    for (let x = 0; x < st; x++) {
      const v = raw[row + x], a = x >= 4 ? data[y * st + x - 4] : 0, up = y > 0 ? data[(y - 1) * st + x] : 0;
      const c = x >= 4 && y > 0 ? data[(y - 1) * st + x - 4] : 0;
      let p;
      if (f === 0) p = 0; else if (f === 1) p = a; else if (f === 2) p = up; else if (f === 3) p = (a + up) >> 1;
      else { const q = a + up - c, pa = Math.abs(q - a), pb = Math.abs(q - up), pc = Math.abs(q - c); p = pa <= pb && pa <= pc ? a : pb <= pc ? up : c; }
      data[y * st + x] = (v + p) & 255;
    }
  }
  return { w, h, data };
}

// 64 cells (from cell(i) -> {w, h, data}) into one grid texture, black with each cell's alpha
function atlas(cell) {
  const first = cell(0), n = first.w, size = n * GRID, out = new Uint8ClampedArray(size * size * 4);
  for (let i = 0; i < GRID * GRID; i++) {
    const c = i === 0 ? first : cell(i);
    if (c.w !== n || c.h !== n) throw new Error("cells of one style differ in size");
    const ox = (i % GRID) * n, oy = Math.floor(i / GRID) * n;
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
      const s = (y * n + x) * 4, d = ((oy + y) * size + ox + x) * 4;
      out[d] = out[d + 1] = out[d + 2] = 0; out[d + 3] = c.data[s + 3];
    }
  }
  return png(size, size, out);
}

const outDir = path.join(here, "..", "..", "src", "main", "resources", "assets", "ntmgunfeel", "textures", "effect");
// the per-variant files of before (hole_spall_0.png ...) and the old holes' own files go: the grids replace them
for (const f of fs.readdirSync(outDir)) if (/^(hole_[a-z]+_\d+|bullet_hole_\d+)\.png$/.test(f)) fs.unlinkSync(path.join(outDir, f));

let n = 0;
for (const cat in api.CATS) {
  const c = api.CATS[cat], cs = S.cats[cat];
  for (const sk in api.STYLES[cat]) {
    const cell = (i) => { const img = api.renderTexture(cat, sk, cs.styles[sk], cs.common, seedFor(cat, i)); return { w: img.width, h: img.height, data: img.data }; };
    fs.writeFileSync(path.join(outDir, c.prefix + sk + ".png"), atlas(cell));
    n++;
  }
}
const old = Array.from({ length: 8 }, (_, i) => readPng(path.join(here, "old", "bullet_hole_" + i + ".png")));
fs.writeFileSync(path.join(outDir, "hole_old.png"), atlas((i) => old[i % old.length]));
n++;

fs.writeFileSync(path.join(here, "settings-used.json"), JSON.stringify({ cats: S.cats }, null, 2) + "\n");
console.log(n, "textures of", GRID * GRID, "variants written to", path.relative(process.cwd(), outDir));
