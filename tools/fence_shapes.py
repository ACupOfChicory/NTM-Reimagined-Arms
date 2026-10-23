#!/usr/bin/env python3
"""The cells a bullet meets in fences, fence gates and walls, from the vanilla block models, without the game.

  fence_shapes.py CLIENT_JAR [SHAPES_FILE]

CLIENT_JAR: Minecraft's client jar (~/.gradle/caches/neoformruntime/artifacts/minecraft_<ver>_client.jar).
SHAPES_FILE: src/main/resources/ntmgunfeel-bullet-shapes.properties (default); its "~family[...]" lines are
replaced, the rest kept.

A fence's collision box is a solid wall up to 1.5 blocks (so nobody jumps it), and bullets met it there: between the
rails too. These lines give, per family and per state, the 16 x 16 x 16 cells its model fills: PassableClip uses
them for every block of that class (FenceBlock, FenceGateBlock, WallBlock), other mods' included, so a bullet goes
through the gaps (пролёт) and meets only the rails and posts. Families: fence (oak_fence's model), fence_gate,
wall, and bamboo_fence and bamboo_fence_gate, whose models are their own. The models' boxes are filled whole (their
textures are opaque); a rotated box or an x rotation stops the tool, as they would need more than this.
"""
import base64
import itertools
import json
import struct
import sys
import zipfile
import zlib

FAMILIES = {
    "fence": "oak_fence",
    "fence_gate": "oak_fence_gate",
    "wall": "cobblestone_wall",
    "bamboo_fence": "bamboo_fence",
    "bamboo_fence_gate": "bamboo_fence_gate",
}
# the properties that change the model (not waterlogged, powered)
PROPS = {
    "fence": {p: ["false", "true"] for p in ("east", "north", "south", "west")},
    "fence_gate": {"facing": ["east", "north", "south", "west"], "in_wall": ["false", "true"], "open": ["false", "true"]},
    "wall": {**{p: ["low", "none", "tall"] for p in ("east", "north", "south", "west")}, "up": ["false", "true"]},
}
PROPS["bamboo_fence"] = PROPS["fence"]
PROPS["bamboo_fence_gate"] = PROPS["fence_gate"]


class Models:
    def __init__(self, jar):
        self.zip = zipfile.ZipFile(jar)
        self.cache = {}

    def json(self, path):
        return json.loads(self.zip.read("assets/minecraft/" + path))

    def elements(self, name):
        name = name.split(":")[-1]
        if name not in self.cache:
            m = self.json(f"models/{name}.json")
            if "elements" in m:
                self.cache[name] = m["elements"]
            elif "parent" in m:
                self.cache[name] = self.elements(m["parent"])
            else:
                self.cache[name] = []
        return self.cache[name]


def matches(when, state):
    if "OR" in when:
        return any(matches(w, state) for w in when["OR"])
    if "AND" in when:
        return all(matches(w, state) for w in when["AND"])
    return all(state.get(k) in str(v).split("|") for k, v in when.items())


def models_of(blockstate, state):
    """(model, y rotation) for each part the state draws"""
    out = []
    if "variants" in blockstate:
        for key, v in blockstate["variants"].items():
            want = dict(kv.split("=") for kv in key.split(",")) if key else {}
            if all(state.get(k) == val for k, val in want.items()):
                out.append(v[0] if isinstance(v, list) else v)
                break
    for part in blockstate.get("multipart", []):
        if "when" not in part or matches(part["when"], state):
            a = part["apply"]
            out.append(a[0] if isinstance(a, list) else a)
    for a in out:
        if a.get("x", 0) != 0:
            sys.exit(f"x rotation in {a}")
    return [(a["model"], a.get("y", 0) % 360) for a in out]


def turn(x, z, y):
    """a point (in pixels) turned by the model's y rotation about the block's middle"""
    for _ in range(y // 90):
        x, z = 16 - z, x
    return x, z


def cells(lo, hi):
    a = max(0, min(15, int(lo // 1)))
    b = max(0, min(15, -int(-hi // 1) - 1))
    return range(a, max(a, b) + 1)


def shape(models, parts):
    bits = [0] * 64
    for model, y in parts:
        for e in models.elements(model):
            r = e.get("rotation")
            if r and r.get("angle", 0) != 0:
                sys.exit(f"rotated box in {model}")
            (x0, y0, z0), (x1, y1, z1) = e["from"], e["to"]
            ax, az = turn(x0, z0, y)
            bx, bz = turn(x1, z1, y)
            for cx in cells(min(ax, bx), max(ax, bx)):
                for cy in cells(y0, y1):
                    for cz in cells(min(az, bz), max(az, bz)):
                        k = cx + 16 * (cy + 16 * cz)
                        bits[k >> 6] |= 1 << (k & 63)
    return bits


def pack(bits):
    """as PassableClip.pack: the 64 longs big-endian, deflated, base64 after z"""
    raw = struct.pack(">64Q", *bits)
    return "z" + base64.b64encode(zlib.compress(raw, 9)).decode()


def lines(models):
    out = []
    for family, block in FAMILIES.items():
        bs = models.json(f"blockstates/{block}.json")
        props = PROPS[family]
        names = sorted(props)
        for values in itertools.product(*(props[n] for n in names)):
            state = dict(zip(names, values))
            key = "~" + family + "[" + ",".join(f"{n}={state[n]}" for n in names) + "]"
            out.append(key.replace("=", "\\=") + " = " + pack(shape(models, models_of(bs, state))))
    return out


HEAD = "# fences, fence gates, walls (any mod's): their models' cells per family and state, made by tools/fence_shapes.py"


def main():
    jar = sys.argv[1]
    target = sys.argv[2] if len(sys.argv) > 2 else "src/main/resources/ntmgunfeel-bullet-shapes.properties"
    with open(target, encoding="utf-8") as f:
        kept = [l for l in f.read().splitlines() if not l.startswith("~") and l != HEAD]
    made = lines(Models(jar))
    with open(target, "w", encoding="utf-8") as f:
        f.write("\n".join(kept + [HEAD] + made) + "\n")
    print(f"{len(made)} shapes to {target}")


if __name__ == "__main__":
    main()
