"""Read a .glb (binary glTF 2.0) skeleton and its animations, and pose it.

Pure Python + numpy, no Blender. Only what Quaternius UAL needs: node TRS,
LINEAR/STEP samplers for translation/rotation/scale.
"""
import json
import struct

import numpy as np

_COMP = {5126: np.float32, 5123: np.uint16, 5125: np.uint32, 5121: np.uint8}
_NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def quat_to_mat(q):
    """glTF quaternion [x, y, z, w] -> 3x3 rotation matrix."""
    x, y, z, w = q / np.linalg.norm(q)
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def slerp(a, b, t):
    d = float(np.dot(a, b))
    if d < 0:
        b, d = -b, -d
    if d > 0.9995:
        r = a + t * (b - a)
        return r / np.linalg.norm(r)
    th = np.arccos(d)
    return (np.sin((1 - t) * th) * a + np.sin(t * th) * b) / np.sin(th)


class Glb:
    def __init__(self, path):
        with open(path, "rb") as f:
            raw = f.read()
        magic, _, _ = struct.unpack("<III", raw[:12])
        assert magic == 0x46546C67, "not a glb"
        off = 12
        self.bin = b""
        while off < len(raw):
            ln, kind = struct.unpack("<II", raw[off:off + 8])
            chunk = raw[off + 8:off + 8 + ln]
            if kind == 0x4E4F534A:
                self.j = json.loads(chunk)
            elif kind == 0x004E4942:
                self.bin = chunk
            off += 8 + ln
        self.nodes = self.j["nodes"]
        self.names = [n.get("name", f"node{i}") for i, n in enumerate(self.nodes)]
        self.index = {n: i for i, n in enumerate(self.names)}
        self.parent = [-1] * len(self.nodes)
        for i, n in enumerate(self.nodes):
            for c in n.get("children", []):
                self.parent[c] = i
        self.anims = {a["name"]: a for a in self.j.get("animations", [])}

    def accessor(self, i):
        a = self.j["accessors"][i]
        bv = self.j["bufferViews"][a["bufferView"]]
        dt = _COMP[a["componentType"]]
        n = _NCOMP[a["type"]]
        start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride", 0)
        item = np.dtype(dt).itemsize * n
        if stride and stride != item:
            rows = [np.frombuffer(self.bin, dt, n, start + k * stride) for k in range(a["count"])]
            arr = np.array(rows)
        else:
            arr = np.frombuffer(self.bin, dt, a["count"] * n, start).reshape(a["count"], n)
        return arr.astype(np.float64)

    def rest_trs(self, i):
        n = self.nodes[i]
        if "matrix" in n:
            m = np.array(n["matrix"]).reshape(4, 4).T
            t = m[:3, 3]
            s = np.linalg.norm(m[:3, :3], axis=0)
            r = m[:3, :3] / s
            return t, r, s
        t = np.array(n.get("translation", [0, 0, 0]), float)
        r = quat_to_mat(np.array(n.get("rotation", [0, 0, 0, 1]), float))
        s = np.array(n.get("scale", [1, 1, 1]), float)
        return t, r, s

    def clip(self, name):
        return Clip(self, self.anims[name])

    def world(self, local):
        """local: list of (t, R, s) per node -> list of 4x4 world matrices."""
        out = [None] * len(self.nodes)

        def get(i):
            if out[i] is None:
                t, r, s = local[i]
                m = np.eye(4)
                m[:3, :3] = r * s
                m[:3, 3] = t
                p = self.parent[i]
                out[i] = m if p < 0 else get(p) @ m
            return out[i]

        for i in range(len(self.nodes)):
            get(i)
        return out

    def rest_world(self):
        return self.world([self.rest_trs(i) for i in range(len(self.nodes))])


class Clip:
    def __init__(self, glb, anim):
        self.glb = glb
        self.name = anim["name"]
        self.channels = []
        self.length = 0.0
        self.times = set()
        for ch in anim["channels"]:
            sm = anim["samplers"][ch["sampler"]]
            ts = glb.accessor(sm["input"])[:, 0]
            vs = glb.accessor(sm["output"])
            interp = sm.get("interpolation", "LINEAR")
            if interp not in ("LINEAR", "STEP"):
                raise ValueError(f"{anim['name']}: {interp} samplers are not read")
            self.channels.append((ch["target"]["node"], ch["target"]["path"], ts, vs, interp))
            self.length = max(self.length, float(ts[-1]))
            self.times.update(np.round(ts, 5).tolist())
        self.times = sorted(self.times)

    def local(self, t):
        g = self.glb
        local = [list(g.rest_trs(i)) for i in range(len(g.nodes))]
        for node, path, ts, vs, interp in self.channels:
            if t <= ts[0]:
                v = vs[0]
            elif t >= ts[-1]:
                v = vs[-1]
            else:
                k = int(np.searchsorted(ts, t, side="right")) - 1
                u = (t - ts[k]) / (ts[k + 1] - ts[k])
                if interp == "STEP":
                    v = vs[k]
                elif path == "rotation":
                    v = slerp(vs[k], vs[k + 1], u)
                else:
                    v = vs[k] + u * (vs[k + 1] - vs[k])
            if path == "translation":
                local[node][0] = np.array(v)
            elif path == "rotation":
                local[node][1] = quat_to_mat(np.array(v))
            elif path == "scale":
                local[node][2] = np.array(v)
        return local

    def world(self, t):
        return self.glb.world(self.local(t))
