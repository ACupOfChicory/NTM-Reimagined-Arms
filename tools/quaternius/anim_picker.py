"""The picker page: every clip retarget.py takes, on the Minecraft biped with each way of carrying an arm and a leg
over (retarget.MODES), beside the rig's own skeleton, for the owner to choose per clip.

Run:  python3 -I tools/quaternius/anim_picker.py <page dir> TAG=<file.glb> ...   (the TAGs as for retarget.py)

Writes <page dir>/index.html (from anim_picker.html) and <page dir>/data/<TAG>.js, one per pack: plain scripts, so
the page opens from disk (file://) with no server and no network. The page saves the choice as anim-choices.json,
which `retarget.py --choices` reads.

Per clip, 30 frames a second from 0 to its length:
- `sk`: the rig's joints from the pelvis out (MC model pixels: X the model's left, Y down, Z back; the rest pose's
  lowest joint at Y 24, the hips' rest place at X = Z = 0), and `links`, each joint's parent among them;
- `head`, `body`: the parts' turns (quaternions x, y, z, w); `piv`: the pivots of the body (and head), the arms and
  the legs from the hips, pixels; `hip`: the hips' X and Z;
- `limb[part][mode]`: each arm's and leg's turn in each mode;
- `hipY[arms mode × 4 + legs mode]`: the hips' Y for that pair (the lowest box corner sits on the ground as the rig's
  lowest joint does: retarget.Skeleton.hip_y).
"""
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import retarget as r  # noqa: E402
from gltf_skel import Glb  # noqa: E402

FPS = 30
UPPER_BOXES = ["head", "body", "right_arm", "left_arm"]


def mat_to_quat(m):
    """A rotation matrix -> (x, y, z, w)."""
    t = m[0, 0] + m[1, 1] + m[2, 2]
    if t > 0:
        s = math.sqrt(t + 1.0) * 2
        q = ((m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s, (m[1, 0] - m[0, 1]) / s, 0.25 * s)
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = math.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        q = (0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s, (m[2, 1] - m[1, 2]) / s)
    elif m[1, 1] > m[2, 2]:
        s = math.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        q = ((m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s, (m[0, 2] - m[2, 0]) / s)
    else:
        s = math.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        q = ((m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s, (m[1, 0] - m[0, 1]) / s)
    q = np.array(q)
    return q / np.linalg.norm(q)


def quat_to_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def stick_joints(sk):
    """The joints drawn as the rig's skeleton: each bone the retarget reads and its parents up to the pelvis."""
    g = sk.g
    skins = g.j.get("skins")
    joints = {i for s in skins for i in s["joints"]} if skins else set(range(len(g.nodes)))
    top = g.index[sk.bones["pelvis"]]
    keep = set()
    for name in sk.bones.values():
        i = g.index[name]
        while i >= 0 and i in joints and i not in keep:
            keep.add(i)
            if i == top:
                break
            i = g.parent[i]
    keep.add(top)
    order = sorted(keep)
    links = []
    for i in order:
        p = g.parent[i]
        while p >= 0 and p not in keep:
            p = g.parent[p]
        links.append(order.index(p) if p >= 0 else -1)
    return order, links


def flat(rows, nd):
    return [round(float(x), nd) for row in rows for x in row]


def clip_data(sk, tag, name, order, links):
    clip = sk.g.clip(name)
    n = max(2, int(math.ceil(clip.length * FPS - 1e-6)) + 1)  # the last sample at the end itself
    times = [min(clip.length, i / FPS) for i in range(n)]
    hips0 = sk.hips(sk.rest)
    legs_now = r.LEG_ALIAS[r.pick_legs(sk, clip)]
    sk_rows, head, body, piv, hip, hip_y = [], [], [], [], [], []
    limb = {part: {m: [] for m in r.MODES} for part in r.LIMB}
    for t in times:
        world = clip.world(t)
        p = sk.pose(world)
        rot, pv, hp = sk.parts(p, "end", "end")
        row = []
        for i in order:
            v = r.C @ world[i][:3, 3]
            # the rig's rest lowest joint on the ground (Y 24), as the biped's lowest box corner is
            row += [sk.scale * (v[0] - hips0[0]), 24.0 + sk.scale * (v[1] + sk.ground0), sk.scale * (v[2] - hips0[2])]
        sk_rows.append(row)
        head.append(mat_to_quat(rot["head"]))
        body.append(mat_to_quat(rot["body"]))
        piv.append(np.concatenate([pv[k] for k in ("body", "right_arm", "left_arm", "right_leg", "left_leg")]))
        hip.append((hp[0], hp[2]))
        mats = {part: {m: sk.limb_rot(p, part, m) for m in r.MODES} for part in r.LIMB}
        for part in r.LIMB:
            for m in r.MODES:
                limb[part][m].append(mat_to_quat(mats[part][m]))
        lows_up, lows_leg = {}, {}
        for m in r.MODES:
            rr = dict(rot)
            for part in r.LIMB:
                rr[part] = mats[part][m]
            lows_up[m] = sk.lowest_corner(rr, pv, UPPER_BOXES)
            lows_leg[m] = sk.lowest_corner(rr, pv, r.LOWER)
        hip_y.append([sk.hip_y(p, max(lows_up[a], lows_leg[b])) for a in r.MODES for b in r.MODES])
    short = name.split("|")[-1]
    loop = short.endswith("_Loop") or short in r.LOOPS
    return {
        "key": f"{tag}/{name}", "pack": tag, "name": short, "length": round(clip.length, 4), "n": n,
        "loop": loop, "now": {"arms": "end", "legs": legs_now},
        "links": links, "sk": flat(sk_rows, 2), "head": flat(head, 4), "body": flat(body, 4),
        "piv": flat(piv, 2), "hip": flat(hip, 2), "hipY": flat(hip_y, 2),
        "limb": {part: {m: flat(v, 4) for m, v in ms.items()} for part, ms in limb.items()},
    }


def main():
    out = sys.argv[1]
    packs = [a.split("=", 1) for a in sys.argv[2:]]
    os.makedirs(os.path.join(out, "data"), exist_ok=True)
    tags = []
    for tag, path in packs:
        rig, _ = r.PACKS[tag]
        sk = r.Skeleton(Glb(path), rig)
        order, links = stick_joints(sk)
        clips = [clip_data(sk, tag, name, order, links) for name in r.SELECTED[tag] if name in sk.g.anims]
        missing = [name for name in r.SELECTED[tag] if name not in sk.g.anims]
        if missing:
            raise SystemExit(f"{tag}: no {missing} in {path}")
        with open(os.path.join(out, "data", f"{tag}.js"), "w") as f:
            f.write("ANIM_DATA.push(...")
            json.dump(clips, f, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
            f.write(");\n")
        tags.append(tag)
        print(f"{tag}: {len(clips)} clips")
    page = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "anim_picker.html"), encoding="utf-8").read()
    scripts = "\n".join(f'<script src="data/{t}.js"></script>' for t in tags)
    with open(os.path.join(out, "index.html"), "w", encoding="utf-8") as f:
        f.write(page.replace("<!--DATA-->", scripts))


if __name__ == "__main__":
    main()
