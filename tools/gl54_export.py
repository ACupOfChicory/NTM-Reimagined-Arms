#!/usr/bin/env python3
"""Moves the GL-54 model from a .blend into the mod's resources, in the formats NTM reads.

    blender -b <model>.blend --python tools/gl54_export.py -- <assets/ntmgunfeel directory> [model name]

Writes, under the directory given (name = "gl54" by default):
    items/gun_<name>.json                      only its "parts" and the muzzle's place are set, the rest is kept
    models/weapons/<name>.obj                  the parts as objects (g/o), for people and for tools
    models/weapons/<name>.hbm_mesh             the same, in the file Meshes.load reads (ObjMesh)
    textures/models/weapons/<name>.png         a palette: one flat colour per material of the model
    models/weapons/animations/<name>.json      the actions, as AnimationLoader reads them (offset + anim)
    models/weapons/<name>_casing.json          the ejected case: where it comes out, how fast, how it turns

A new model is put in by running this again on its .blend, no code changes, as long as it keeps the names:
every mesh object is a part (the part's name is the object's name; EjectedCase is not a part, it only gives the
numbers of the case), the actions are named "<Animation>_<Object>" (Fire_BoltCarrier) or are the object's one
action named "<Animation>", and the barrel looks along +X with +Z up, as in the first model.

Axes: the .blend has +X forward, +Z up, +Y to the gun's left; NTM's models have +Z forward, +Y up, +X to the gun's
left. (x, y, z) of the .blend becomes (y, z, x), which is a turn and not a mirror, so the faces keep their winding.
Units: metres in the .blend, UNITS_PER_METER NTM model units.
"""
import json
import math
import os
import struct
import sys
import zlib

# the model's size in NTM units: the 0.70 m of the first model come out as 8.4, as long as Congo Lake
UNITS_PER_METER = 12.0
# the point of the .blend (metres) that becomes the origin of the NTM model: the gun's middle, the barrel's axis
# 1.4 units above it, as Congo Lake's
ORIGIN_M = (-0.10, 0.0, -0.05)
FPS_FALLBACK = 60.0
CASING_OBJECT = "EjectedCase"
# NTM's interpolation names (BusAnimationKeyframe.IType) and the .blend's that it has
INTERPOLATIONS = {"CONSTANT", "LINEAR", "BEZIER", "SINE", "QUAD", "CUBIC", "QUART", "QUINT", "EXPO", "CIRC",
                  "BOUNCE", "ELASTIC", "BACK"}
EASINGS = {"AUTO", "EASE_IN", "EASE_OUT", "EASE_IN_OUT"}
HANDLES = {"FREE", "ALIGNED", "VECTOR", "AUTO", "AUTO_CLAMPED"}
PALETTE_CELLS = 8      # the palette is 8 x 8 cells
PALETTE_CELL = 8       # of 8 x 8 pixels
TICK_S = 0.05
MESH_MAGIC = 1212304719
MESH_VERSION = 1


# ---- axes (no Blender needed: the tests run these)

def axis_point(p, origin=ORIGIN_M, scale=UNITS_PER_METER):
    """A point of the .blend (x forward, y left, z up; metres) as NTM's (x left, y up, z forward; units)."""
    return ((p[1] - origin[1]) * scale, (p[2] - origin[2]) * scale, (p[0] - origin[0]) * scale)


def axis_vector(v, scale=1.0):
    """A direction or a speed: no origin."""
    return (v[1] * scale, v[2] * scale, v[0] * scale)


# the .blend's axis (0 x, 1 y, 2 z) as NTM's (0 x, 1 y, 2 z): x -> z, y -> x, z -> y
AXIS_TO_NTM = (2, 0, 1)
AXIS_NAME = "xyz"


def linear_to_srgb(c):
    c = min(1.0, max(0.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055


def palette_cell_centre(index):
    """The (u, v) of the middle of palette cell `index`, v counted down from the top of the picture."""
    col, row = index % PALETTE_CELLS, index // PALETTE_CELLS
    return ((col + 0.5) / PALETTE_CELLS, (row + 0.5) / PALETTE_CELLS)


def write_png(path, width, height, rgba_rows):
    """A plain 8-bit RGBA PNG without a library; rgba_rows: a list of bytes, one per row (4 bytes a pixel)."""
    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + row for row in rgba_rows)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


# ---- NTM's mesh file (ObjMesh): tables, then the groups with indices as zig-zag varints relative to the last one

def varint_delta(value):
    z = (value << 1) ^ (value >> 31) if value >= 0 else ((-value) << 1) - 1
    out = bytearray()
    while True:
        low = z & 0x7F
        z >>= 7
        if z:
            out.append(low | 0x80)
        else:
            out.append(low)
            return bytes(out)


def encode_mesh(groups):
    """groups: [(name, faces)], a face = [(position, uv, normal), ...] 3 or 4 corners of tuples of floats,
    uv as the picture has it (v down from the top), and the face's own normal as the last item: (corners, normal)."""
    positions, uvs, normals = [], [], []
    index = {"p": {}, "u": {}, "n": {}}

    def slot(kind, table, value):
        key = tuple(round(v, 6) for v in value)
        found = index[kind].get(key)
        if found is None:
            found = len(table)
            index[kind][key] = found
            table.append(key)
        return found

    coded = []
    for name, faces in groups:
        out = []
        for corners, face_normal in faces:
            fn = slot("n", normals, face_normal)
            out.append((fn, [(slot("p", positions, p), slot("u", uvs, u), slot("n", normals, n)) for p, u, n in corners]))
        coded.append((name, out))
    body = bytearray(struct.pack(">ii", MESH_MAGIC, MESH_VERSION))
    for table, width in ((positions, 3), (uvs, 2), (normals, 3)):
        body += struct.pack(">i", len(table))
        for row in table:
            body += struct.pack(">%df" % width, *row)
    body += struct.pack(">i", len(coded))
    for (name, out), (_, faces) in zip(coded, groups):
        # the indices count from zero again in every group
        last = {"p": 0, "u": 0, "n": 0, "f": 0}
        raw = name.encode("utf-8")
        body += struct.pack(">i", len(raw)) + raw
        pts = [positions[p] for _, cs in out for p, _, _ in cs]
        lo = [min(p[i] for p in pts) for i in range(3)] if pts else [0, 0, 0]
        hi = [max(p[i] for p in pts) for i in range(3)] if pts else [0, 0, 0]
        body += struct.pack(">6f", *lo, *hi)
        body += struct.pack(">i", len(out))
        for fn, cs in out:
            body.append(len(cs))
            body += varint_delta(fn - last["f"])
            last["f"] = fn
            for p, u, n in cs:
                body += varint_delta(p - last["p"]) + varint_delta(u - last["u"]) + varint_delta(n - last["n"])
                last["p"], last["u"], last["n"] = p, u, n
    return bytes(body)


def encode_obj(name, groups):
    """The same groups as a Wavefront .obj (NTM's reader takes no exponent notation: fixed digits only)."""
    lines = ["# %s: made by tools/gl54_export.py, do not edit by hand" % name]
    positions, uvs, normals = [], [], []
    seen = {"p": {}, "u": {}, "n": {}}

    def slot(kind, table, value, fmt):
        key = fmt % value if isinstance(value, tuple) else fmt % (value,)
        found = seen[kind].get(key)
        if found is None:
            table.append(key)
            found = len(table)
            seen[kind][key] = found
        return found
    body = []
    for gname, faces in groups:
        body.append("o %s" % gname)
        for corners, _ in faces:
            cs = []
            for p, u, n in corners:
                pi = slot("p", positions, p, "%.6f %.6f %.6f")
                ui = slot("u", uvs, (u[0], 1.0 - u[1]), "%.6f %.6f")
                ni = slot("n", normals, n, "%.6f %.6f %.6f")
                cs.append("%d/%d/%d" % (pi, ui, ni))
            body.append("f " + " ".join(cs))
    lines += ["v " + p for p in positions] + ["vt " + u for u in uvs] + ["vn " + n for n in normals] + body
    return "\n".join(lines) + "\n"


# ---- animation (as AnimationLoader reads it: a keyframe's second number is the time since the one before)

def frame_ms(frame, start, fps):
    return int(round((frame - start) / fps * 1000.0))


def keyframes_json(points, start, fps, convert, rotation=False):
    """points: [(frame, value, interpolation, easing, (hl_frame, hl_value), (hr_frame, hr_value), hl_type, hr_type)]"""
    out = []
    last_ms = 0
    previous_interp = None
    for frame, value, interp, easing, hl, hr, hl_type, hr_type in points:
        ms = frame_ms(frame, start, fps)
        key = [round(convert(value), 6), ms - last_ms, interp if interp in INTERPOLATIONS else "LINEAR",
               easing if easing in EASINGS else "AUTO"]
        last_ms = ms
        if previous_interp == "BEZIER":
            key += [round(frame_ms(hl[0], start, fps) - 0.0, 3), round(convert(hl[1]), 6),
                    hl_type if hl_type in HANDLES else "AUTO_CLAMPED"]
        if key[2] == "BEZIER":
            key += [round(frame_ms(hr[0], start, fps) - 0.0, 3), round(convert(hr[1]), 6),
                    hr_type if hr_type in HANDLES else "AUTO_CLAMPED"]
        previous_interp = key[2]
        out.append(key)
    return out


def animation_name(action_name, object_name):
    suffix = "_" + object_name
    return action_name[:-len(suffix)] if action_name.endswith(suffix) and len(action_name) > len(suffix) else action_name


# ---- Blender part (only here is bpy needed)

def export(blend_out, name):
    import bpy
    import bmesh
    import mathutils

    scene = bpy.context.scene
    fps = scene.render.fps / (scene.render.fps_base or 1.0) or FPS_FALLBACK
    scene.frame_set(scene.frame_start)
    graph = bpy.context.evaluated_depsgraph_get()

    objects = [o for o in bpy.data.objects if o.type == "MESH" and o.name != CASING_OBJECT]
    objects.sort(key=lambda o: o.name)
    materials = []
    for o in objects:
        for m in o.data.materials:
            if m is not None and m not in materials:
                materials.append(m)
    materials.sort(key=lambda m: m.name)
    if len(materials) > PALETTE_CELLS * PALETTE_CELLS:
        raise SystemExit("more materials than palette cells: %d" % len(materials))
    cell_of = {m.name: i for i, m in enumerate(materials)}

    def colour(m):
        if m.use_nodes and m.node_tree:
            for n in m.node_tree.nodes:
                if n.type == "BSDF_PRINCIPLED":
                    c = n.inputs["Base Color"].default_value
                    return (c[0], c[1], c[2])
        return (m.diffuse_color[0], m.diffuse_color[1], m.diffuse_color[2])

    groups = []
    for o in objects:
        eval_obj = o.evaluated_get(graph)
        me = eval_obj.to_mesh()
        bm = bmesh.new()
        bm.from_mesh(me)
        bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 4])
        bm.to_mesh(me)
        bm.free()
        world = o.matrix_world
        normal_matrix = world.to_3x3().inverted_safe().transposed()
        normals = me.corner_normals
        faces = []
        for poly in me.polygons:
            slot = o.material_slots[poly.material_index].material if poly.material_index < len(o.material_slots) else None
            cell = cell_of.get(slot.name, 0) if slot else 0
            uv = palette_cell_centre(cell)
            corners = []
            for li in poly.loop_indices:
                loop = me.loops[li]
                p = axis_point(tuple(world @ me.vertices[loop.vertex_index].co))
                n = normal_matrix @ mathutils.Vector(normals[li].vector)
                n.normalize()
                corners.append((p, uv, axis_vector(tuple(n))))
            fn = normal_matrix @ poly.normal
            fn.normalize()
            faces.append((corners, axis_vector(tuple(fn))))
        eval_obj.to_mesh_clear()
        groups.append((o.name, faces))

    def path(*parts):
        p = os.path.join(blend_out, *parts)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        return p

    with open(path("models", "weapons", name + ".obj"), "w") as f:
        f.write(encode_obj(name, groups))
    with open(path("models", "weapons", name + ".hbm_mesh"), "wb") as f:
        f.write(encode_mesh(groups))
    rows = []
    for py in range(PALETTE_CELLS * PALETTE_CELL):
        row = bytearray()
        for px in range(PALETTE_CELLS * PALETTE_CELL):
            i = (py // PALETTE_CELL) * PALETTE_CELLS + px // PALETTE_CELL
            if i < len(materials):
                r, g, b = colour(materials[i])
                row += bytes((round(linear_to_srgb(r) * 255), round(linear_to_srgb(g) * 255), round(linear_to_srgb(b) * 255), 255))
            else:
                row += bytes((255, 0, 255, 255))
        rows.append(bytes(row))
    write_png(path("textures", "models", "weapons", name + ".png"), PALETTE_CELLS * PALETTE_CELL, PALETTE_CELLS * PALETTE_CELL, rows)

    # animations
    offsets, anims = {}, {}
    start = scene.frame_start
    for o in objects:
        ad = o.animation_data
        if not ad or not ad.action:
            continue
        rest = axis_point(tuple(o.matrix_world.translation))
        offsets[o.name] = [round(v, 6) for v in rest]
        for fc_action in [ad.action]:
            anim = animation_name(fc_action.name, o.name)
            channels = {}
            for fc in _fcurves(fc_action):
                channels[(fc.data_path, fc.array_index)] = fc
            bus = {}
            loc = {}
            for src in range(3):
                dst = AXIS_TO_NTM[src]
                fc = channels.get(("location", src))
                if fc is not None:
                    pts = _points(fc)
                    loc[AXIS_NAME[dst]] = keyframes_json(pts, start, fps,
                                                         lambda v, s=src: (v - ORIGIN_M[s]) * UNITS_PER_METER)
                else:
                    loc[AXIS_NAME[dst]] = [[round(rest[dst], 6), 0, "LINEAR", "AUTO"]]
            bus["location"] = {k: loc[k] for k in "xyz"}
            rot = {}
            for src in range(3):
                fc = channels.get(("rotation_euler", src))
                if fc is not None:
                    rot[AXIS_NAME[AXIS_TO_NTM[src]]] = keyframes_json(_points(fc), start, fps, math.degrees, True)
            if rot:
                if len(rot) > 1:
                    print("WARNING: %s turns about several axes; NTM applies them in another order than Blender" % o.name)
                bus["rotation_euler"] = rot
            scl = {}
            for src in range(3):
                fc = channels.get(("scale", src))
                if fc is not None:
                    scl[AXIS_NAME[AXIS_TO_NTM[src]]] = keyframes_json(_points(fc), start, fps, lambda v: v)
            if scl:
                bus["scale"] = scl
            anims.setdefault(anim, {})[o.name] = bus
    with open(path("models", "weapons", "animations", name + ".json"), "w") as f:
        json.dump({"offset": offsets, "anim": anims}, f, indent=1)
        f.write("\n")

    update_item_json(blend_out, name, groups)
    with open(path("models", "weapons", name + "_casing.json"), "w") as f:
        json.dump(casing_numbers(bpy, fps), f, indent=1)
        f.write("\n")
    print("exported %s: %d parts, %d materials, animations %s" % (name, len(groups), len(materials), sorted(anims)))


BARREL_PART = "TitaniumBarrel"


def muzzle_of(groups):
    """The barrel's far end in the NTM model: (y of its middle, z of its end); the whole model's without that part."""
    chosen = [g for g in groups if g[0] == BARREL_PART] or groups
    ys = [c[0][1] for _, faces in chosen for corners, _ in faces for c in corners]
    zs = [c[0][2] for _, faces in chosen for corners, _ in faces for c in corners]
    return ((min(ys) + max(ys)) / 2.0, max(zs))


def update_item_json(assets, name, groups):
    path = os.path.join(assets, "items", "gun_%s.json" % name)
    if not os.path.exists(path):
        return
    with open(path) as f:
        item = json.load(f)
    model = item.get("model", {})
    model["parts"] = [g[0] for g in groups]
    y, z = muzzle_of(groups)
    for m in model.get("equipped_muzzles", []):
        if "matrix" in m and len(m["matrix"]) == 16:
            m["matrix"][7], m["matrix"][11] = round(y, 4), round(z, 4)
    with open(path, "w") as f:
        json.dump(item, f, indent=2)
        f.write("\n")


def _fcurves(action):
    """The action's curves, in Blender 5 (layers/strips/channelbags) and before."""
    if hasattr(action, "fcurves") and len(action.fcurves):
        return list(action.fcurves)
    out = []
    for layer in getattr(action, "layers", []):
        for strip in layer.strips:
            for bag in strip.channelbags:
                out += list(bag.fcurves)
    return out


def _points(fc):
    return [(k.co[0], k.co[1], k.interpolation, k.easing, tuple(k.handle_left), tuple(k.handle_right),
             k.handle_left_type, k.handle_right_type) for k in fc.keyframe_points]


def casing_numbers(bpy, fps):
    """The ejected case: when it appears (ticks into the animation), where (metres, the gun's frame: front, height,
    side), how fast it leaves (blocks a tick, the same frame) and how it turns (degrees a tick)."""
    obj = bpy.data.objects.get(CASING_OBJECT)
    scene = bpy.context.scene
    if obj is None or not obj.animation_data or not obj.animation_data.action:
        return {"present": False}
    fcs = {(fc.data_path, fc.array_index): fc for fc in _fcurves(obj.animation_data.action)}

    def at(path, index, frame, default):
        fc = fcs.get((path, index))
        return fc.evaluate(frame) if fc is not None else default
    start = scene.frame_start
    frames = sorted({k.co[0] for fc in fcs.values() for k in fc.keyframe_points})
    visible = [f for f in frames if min(at("scale", i, f, 1.0) for i in range(3)) > 0.5]
    appear = visible[0] if visible else frames[0]
    after = [f for f in frames if f > appear]
    nxt = after[0] if after else appear + 1

    def loc(frame):
        return tuple(at("location", i, frame, obj.location[i]) for i in range(3))
    p0, p1 = loc(appear), loc(nxt)
    rest = loc(start)
    dt = (nxt - appear) / fps
    metres_per_s = tuple((b - a) / dt for a, b in zip(p0, p1))
    blocks_per_tick = tuple(v * TICK_S for v in metres_per_s)
    # the turn over the first quarter second after it appears
    end = min(max(frames), appear + 0.25 * fps)
    span = max((end - appear) / fps / TICK_S, 1e-6)
    turn = tuple(math.degrees(at("rotation_euler", i, end, 0.0) - at("rotation_euler", i, appear, 0.0)) / span for i in range(3))
    return {
        "present": True,
        "tick": int(round((appear - start) / fps / TICK_S)),
        "frame": {"front": "x of the .blend", "height": "z of the .blend", "side": "y of the .blend (left is positive)"},
        "position": {"front": round(p0[0] - 0.0, 5), "height": round(p0[2], 5), "side": round(p0[1], 5)},
        # the same two places in the NTM model (x to the gun's left, y up, z forward; model units): where the case rests
        # (the ejection window, before the shot) and where it first shows
        "rest": dict(zip("xyz", [round(v, 5) for v in axis_point(rest)])),
        "port": dict(zip("xyz", [round(v, 5) for v in axis_point(p0)])),
        "origin": {"front": ORIGIN_M[0], "height": ORIGIN_M[2], "side": ORIGIN_M[1]},
        "velocity": {"front": round(blocks_per_tick[0], 5), "height": round(blocks_per_tick[2], 5),
                     "side": round(blocks_per_tick[1], 5)},
        "turn": {"roll": round(turn[0], 3), "pitch": round(turn[1], 3), "yaw": round(turn[2], 3)},
    }


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:]
    if not argv:
        raise SystemExit(__doc__)
    export(argv[0], argv[1] if len(argv) > 1 else "gl54")


if __name__ == "__main__":
    main()
