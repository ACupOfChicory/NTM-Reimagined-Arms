#!/usr/bin/env python3
"""Cut a 16x16 magazine picture into what the GL-54 magazine icon is made of (src/main/java/acup/gunfeel/gl54/MagTint.java).

python3 tools/gl54_mag_tint.py <picture.png> <columns> [out dir]

The saturated part of the picture (the grenades' tips) becomes grey masks, the rest stays as the grey base that is never
tinted. <columns> says where each grenade place is: eight x ranges "x0-x1" separated by commas, e.g. 4-4,5-5,...,11-11: the
mask N holds the coloured pixels in the Nth range. The brightest coloured pixel is 1.0, as in notes/gl54/textures/tint.py
(which cuts a single mask for one colour), so a type colour times the mask is the tint shown in the game.
Writes gl54_mag.png (base) and gl54_mag_mask_0..7.png into the out dir (default src/main/resources/assets/ntmgunfeel/textures/item).
"""
import os
import sys

import numpy as np
from PIL import Image

src, cols = sys.argv[1], sys.argv[2].split(",")
out = sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src/main/resources/assets/ntmgunfeel/textures/item")
a = np.asarray(Image.open(src).convert("RGBA")).astype(float)
rgb = a[..., :3]
coloured = (a[..., 3] > 0) & (rgb.max(2) - rgb.min(2) > 24)
lum = (0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]) / 255
mask = np.where(coloured, lum / lum[coloured].max(), 0)
base = a.copy()
base[coloured] = 0
Image.fromarray(base.astype(np.uint8)).save(os.path.join(out, "gl54_mag.png"))
for i, c in enumerate(cols):
    x0, x1 = (int(v) for v in c.split("-"))
    keep = np.zeros(coloured.shape, bool)
    keep[:, x0:x1 + 1] = True
    m = np.zeros_like(a)
    sel = coloured & keep
    m[..., :3] = (mask * 255)[..., None]
    m[..., 3] = np.where(sel, 255, 0)
    Image.fromarray(m.astype(np.uint8)).save(os.path.join(out, "gl54_mag_mask_%d.png" % i))
print("coloured px", int(coloured.sum()), "places", len(cols))
