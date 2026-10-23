// Renders every decal style of the generator page (page.html) into the mod's textures/effect/, with the author's
// settings from the page's last "send to mod" (exports/latest.json): the style picked there keeps its dials, the
// other styles use the page's defaults; each kind's common settings (size, seed, ...) are shared by its styles.
// The generator code is taken from page.html itself, so the files match the page's previews.
// Usage (repo root): node review/decals/render.js
"use strict";
const fs = require("fs"), path = require("path"), zlib = require("zlib");

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
  if (S.cats[cat].styles[e.style]) Object.assign(S.cats[cat].styles[e.style], e.params);
}
const seedFor = (cat, i) => (S.cats[cat].common.seed * 7919 + i * 104729 + 1) >>> 0;

function png(img) {
  const { width: w, height: h, data } = img;
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    Buffer.from(data.buffer, y * w * 4, w * 4).copy(raw, y * (w * 4 + 1) + 1);
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

const out = path.join(here, "..", "..", "src", "main", "resources", "assets", "ntmgunfeel", "textures", "effect");
let n = 0;
for (const cat in api.CATS) {
  const c = api.CATS[cat], cs = S.cats[cat];
  for (const sk in api.STYLES[cat]) {
    for (let i = 0; i < c.n; i++) {
      const name = c.n === 1 ? c.prefix + sk : c.prefix + sk + "_" + i;
      fs.writeFileSync(path.join(out, name + ".png"), png(api.renderTexture(cat, sk, cs.styles[sk], cs.common, seedFor(cat, i))));
      n++;
    }
  }
}
fs.writeFileSync(path.join(here, "settings-used.json"), JSON.stringify(S.cats, null, 2) + "\n");
console.log(n, "textures written to", path.relative(process.cwd(), out));
