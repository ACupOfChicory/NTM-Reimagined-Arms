"""The core of reading motion recordings: the skeleton, the frames, forward kinematics.

Taken from the owner's enarche project (tools/motion/mocap.py, commit d981423), with the owner's consent; the code is
as it was there, its comments put into English.

Reads BVH and a CMU ASF/AMC pair into one and the same form: a skeleton with its rest pose given by offsets and
each joint's local turn on every frame. The code after it does not know which format a recording came from. The
standard library and numpy only.

Units: metres and degrees out; seconds instead of frames. The axes and the order of turns are described in each
function's docstring, as this is where they are easiest to mix up.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

import numpy as np


@dataclass
class Skeleton:
	"""The joints' hierarchy at rest. The bones' ends are joints of their own, leaves."""
	names: list[str]
	parent: list[int]               # -1 at the root
	offset: np.ndarray              # (J,3) offset from the parent, metres, in the parent's axes
	is_end: list[bool]              # a leaf with no channels of its own (a bone's end)

	def index(self, name: str) -> int:
		try:
			return self.names.index(name)
		except ValueError:
			raise KeyError(f"no bone '{name}' in the skeleton") from None


@dataclass
class Motion:
	"""A recording's frames: the root's place and the joints' local turns."""
	skeleton: Skeleton
	fps: float
	root_pos: np.ndarray            # (N,3) metres
	local_rot: np.ndarray           # (N,J,3,3) the joint's turn in its parent's axes
	name: str = ""
	meta: dict = field(default_factory=dict)

	@property
	def frames(self) -> int:
		return self.root_pos.shape[0]


def slice_motion(mo: Motion, a: int, b: int) -> Motion:
	"""Frames a..b-1 of a recording (to cut it by the table Frame_Cuts.csv)."""
	return Motion(mo.skeleton, mo.fps, mo.root_pos[a:b], mo.local_rot[a:b], mo.name, mo.meta)


# ---------------------------------------------------------------- turns

def rot_axis(axis: str, deg: np.ndarray) -> np.ndarray:
	"""Matrices of a turn by deg degrees about the axis 'x'|'y'|'z' (a right-handed frame, the turn anticlockwise
	looking from the axis's end). Shape (...,3,3)."""
	a = np.radians(np.asarray(deg, dtype=np.float64))
	c, s = np.cos(a), np.sin(a)
	m = np.zeros(a.shape + (3, 3))
	m[..., 0, 0] = m[..., 1, 1] = m[..., 2, 2] = 1.0
	if axis == "x":
		m[..., 1, 1] = c; m[..., 1, 2] = -s
		m[..., 2, 1] = s; m[..., 2, 2] = c
	elif axis == "y":
		m[..., 0, 0] = c; m[..., 0, 2] = s
		m[..., 2, 0] = -s; m[..., 2, 2] = c
	elif axis == "z":
		m[..., 0, 0] = c; m[..., 0, 1] = -s
		m[..., 1, 0] = s; m[..., 1, 1] = c
	else:
		raise ValueError(f"unknown axis '{axis}'")
	return m


def euler_product(order: str, angles_deg: np.ndarray) -> np.ndarray:
	"""The product R_o1 · R_o2 · R_o3 in the order of the letters of order ('zxy' and so on); angles_deg (...,3) in
	the same order. Column vectors: the LAST matrix is applied to a vector first. That is how BVH writes the order
	of its channels."""
	m = None
	for i, ax in enumerate(order):
		r = rot_axis(ax, angles_deg[..., i])
		m = r if m is None else m @ r
	return m


def mat_to_quat(m: np.ndarray) -> np.ndarray:
	"""(...,3,3) -> (...,4) quaternions (w,x,y,z), w >= 0."""
	m = np.asarray(m, dtype=np.float64)
	t = m[..., 0, 0] + m[..., 1, 1] + m[..., 2, 2]
	out = np.empty(m.shape[:-2] + (4,))
	# four branches by the largest diagonal term: stable at a turn of 180°
	w2 = 1 + t
	x2 = 1 + m[..., 0, 0] - m[..., 1, 1] - m[..., 2, 2]
	y2 = 1 - m[..., 0, 0] + m[..., 1, 1] - m[..., 2, 2]
	z2 = 1 - m[..., 0, 0] - m[..., 1, 1] + m[..., 2, 2]
	best = np.argmax(np.stack([w2, x2, y2, z2], axis=-1), axis=-1)
	for k in range(4):
		sel = best == k
		if not sel.any():
			continue
		mm = m[sel]
		if k == 0:
			s = 2 * np.sqrt(np.maximum(w2[sel], 1e-300))
			q = np.stack([s / 4, (mm[:, 2, 1] - mm[:, 1, 2]) / s,
				(mm[:, 0, 2] - mm[:, 2, 0]) / s, (mm[:, 1, 0] - mm[:, 0, 1]) / s], -1)
		elif k == 1:
			s = 2 * np.sqrt(np.maximum(x2[sel], 1e-300))
			q = np.stack([(mm[:, 2, 1] - mm[:, 1, 2]) / s, s / 4,
				(mm[:, 0, 1] + mm[:, 1, 0]) / s, (mm[:, 0, 2] + mm[:, 2, 0]) / s], -1)
		elif k == 2:
			s = 2 * np.sqrt(np.maximum(y2[sel], 1e-300))
			q = np.stack([(mm[:, 0, 2] - mm[:, 2, 0]) / s,
				(mm[:, 0, 1] + mm[:, 1, 0]) / s, s / 4, (mm[:, 1, 2] + mm[:, 2, 1]) / s], -1)
		else:
			s = 2 * np.sqrt(np.maximum(z2[sel], 1e-300))
			q = np.stack([(mm[:, 1, 0] - mm[:, 0, 1]) / s,
				(mm[:, 0, 2] + mm[:, 2, 0]) / s, (mm[:, 1, 2] + mm[:, 2, 1]) / s, s / 4], -1)
		out[sel] = q
	out /= np.linalg.norm(out, axis=-1, keepdims=True)
	out[out[..., 0:1].repeat(4, -1) < 0] *= -1
	return out


def quat_to_mat(q: np.ndarray) -> np.ndarray:
	"""(...,4) (w,x,y,z) -> (...,3,3)."""
	q = q / np.linalg.norm(q, axis=-1, keepdims=True)
	w, x, y, z = q[..., 0], q[..., 1], q[..., 2], q[..., 3]
	m = np.empty(q.shape[:-1] + (3, 3))
	m[..., 0, 0] = 1 - 2 * (y * y + z * z); m[..., 0, 1] = 2 * (x * y - z * w); m[..., 0, 2] = 2 * (x * z + y * w)
	m[..., 1, 0] = 2 * (x * y + z * w); m[..., 1, 1] = 1 - 2 * (x * x + z * z); m[..., 1, 2] = 2 * (y * z - x * w)
	m[..., 2, 0] = 2 * (x * z - y * w); m[..., 2, 1] = 2 * (y * z + x * w); m[..., 2, 2] = 1 - 2 * (x * x + y * y)
	return m


# ------------------------------------------------------ forward kinematics

def forward_kinematics(mo: Motion) -> tuple[np.ndarray, np.ndarray]:
	"""The world places (N,J,3) and turns (N,J,3,3) of all joints.

	G_j = G_parent · L_j;  p_j = p_parent + G_parent · offset_j.
	The root: p = root_pos + the root's offset, G = the root's L."""
	sk = mo.skeleton
	n, j = mo.frames, len(sk.names)
	pos = np.zeros((n, j, 3))
	rot = np.zeros((n, j, 3, 3))
	for i in range(j):
		p = sk.parent[i]
		if p < 0:
			rot[:, i] = mo.local_rot[:, i]
			pos[:, i] = mo.root_pos + sk.offset[i]
		else:
			rot[:, i] = rot[:, p] @ mo.local_rot[:, i]
			pos[:, i] = pos[:, p] + np.einsum("nab,b->na", rot[:, p], sk.offset[i])
	return pos, rot


def rest_pose(sk: Skeleton) -> tuple[np.ndarray, np.ndarray]:
	"""The places (J,3) and turns (J,3,3) at rest: every local turn the identity."""
	j = len(sk.names)
	rot = np.tile(np.eye(3), (j, 1, 1))
	pos = np.zeros((j, 3))
	for i in range(j):
		p = sk.parent[i]
		pos[i] = sk.offset[i] if p < 0 else pos[p] + sk.offset[i]
	return pos, rot


# ------------------------------------------------------------------- BVH

_CH_ROT = {"Xrotation": "x", "Yrotation": "y", "Zrotation": "z"}
_CH_POS = {"Xposition": 0, "Yposition": 1, "Zposition": 2}


def read_bvh(path: str, scale: float, name: str = "") -> Motion:
	"""Reads a BVH. scale: metres per unit of the file (centimetres: 0.01).

	The channels' order as in the file; the turns are multiplied from left to right (the first channel is the
	outer one). The root's place = its channels + its OFFSET."""
	with open(path, "r", encoding="utf-8", errors="replace") as f:
		text = f.read()
	head, sep, tail = text.partition("MOTION")
	if not sep:
		raise ValueError(f"{path}: no MOTION section")
	tokens = re.findall(r"[^\s{}]+|[{}]", head)
	names: list[str] = []
	parent: list[int] = []
	offsets: list[list[float]] = []
	is_end: list[bool] = []
	chans: list[list[str]] = []
	stack: list[int] = []
	i = 0
	while i < len(tokens):
		t = tokens[i]
		if t in ("ROOT", "JOINT") or (t == "End" and tokens[i + 1] == "Site"):
			if t == "End":
				i += 1
				nm = (names[stack[-1]] if stack else "") + "_end"
				end = True
			else:
				nm = tokens[i + 1]
				i += 1
				end = False
			names.append(nm)
			parent.append(stack[-1] if stack else -1)
			offsets.append([0.0, 0.0, 0.0])
			is_end.append(end)
			chans.append([])
			stack.append(len(names) - 1)
		elif t == "OFFSET":
			offsets[stack[-1]] = [float(tokens[i + 1]), float(tokens[i + 2]), float(tokens[i + 3])]
			i += 3
		elif t == "CHANNELS":
			k = int(tokens[i + 1])
			chans[stack[-1]] = tokens[i + 2:i + 2 + k]
			i += 1 + k
		elif t == "}":
			stack.pop()
		i += 1
	mm = re.match(r"\s*Frames:\s*(\d+)\s*Frame Time:\s*([0-9.eE+-]+)\s*", tail)
	if not mm:
		raise ValueError(f"{path}: no Frames / Frame Time")
	frames, ft = int(mm.group(1)), float(mm.group(2))
	flat = np.fromstring(tail[mm.end():], dtype=np.float64, sep=" ")
	ncols = sum(len(c) for c in chans)
	if flat.size != frames * ncols:
		raise ValueError(f"{path}: {frames}×{ncols} numbers declared, {flat.size} read")
	data = flat.reshape(frames, ncols)
	jn = len(names)
	local = np.tile(np.eye(3), (frames, jn, 1, 1))
	root_pos = np.zeros((frames, 3))
	col = 0
	for j in range(jn):
		ch = chans[j]
		if not ch:
			continue
		vals = data[:, col:col + len(ch)]
		col += len(ch)
		rot_names = [c for c in ch if c in _CH_ROT]
		for c, k in zip(ch, range(len(ch))):
			if c in _CH_POS:
				if parent[j] >= 0:
					raise ValueError(f"{path}: a position channel on '{names[j]}', not the root")
				root_pos[:, _CH_POS[c]] = vals[:, k] * scale
		if rot_names:
			order = "".join(_CH_ROT[c] for c in rot_names)
			ang = np.stack([vals[:, ch.index(c)] for c in rot_names], axis=-1)
			local[:, j] = euler_product(order, ang)
	if col != data.shape[1]:
		raise ValueError(f"{path}: {col} channels, {data.shape[1]} columns in a frame")
	sk = Skeleton(names, parent, np.array(offsets) * scale, is_end)
	return Motion(sk, 1.0 / ft, root_pos, local, name=name)


# --------------------------------------------------------------- ASF/AMC

def _asf_sections(text: str) -> dict[str, list[str]]:
	sec: dict[str, list[str]] = {}
	cur = None
	for raw in text.splitlines():
		ln = raw.strip()
		if not ln or ln.startswith("#"):
			continue
		if ln.startswith(":"):
			cur = ln.split()[0][1:]
			sec[cur] = [ln.split(None, 1)[1]] if len(ln.split(None, 1)) > 1 else []
		elif cur is not None:
			sec[cur].append(ln)
	return sec


def read_asf_amc(asf_path: str, amc_path: str, name: str = "") -> Motion:
	"""Reads a CMU ASF/AMC pair.

	Lengths and the root are put in metres as the database's FAQ says: times (1/length_scale)·0.0254, where
	length_scale is ":units length" of the ASF. A bone's turn: L = C · M · C⁻¹, where C is the turn of the bone's
	axes (the axis field, the letters' order from the ASF) and M the product of the turns by dof; the product's
	order M = Rz · Ry · Rx for dof "rx ry rz" (the last one is the outer); C is built from the axis field the same
	way. The order was chosen by the feet standing still while they bear the weight, checked in enarche's
	selftest.py (group 6b)."""
	with open(asf_path, "r", encoding="utf-8", errors="replace") as f:
		sec = _asf_sections(f.read())
	units = {}
	for ln in sec.get("units", []):
		p = ln.split()
		if len(p) >= 2:
			try:
				units[p[0]] = float(p[1])
			except ValueError:
				pass
	to_m = (1.0 / units.get("length", 1.0)) * 0.0254
	bones: dict[str, dict] = {}
	cur: dict | None = None
	for ln in sec["bonedata"]:
		if ln == "begin":
			cur = {"dof": [], "axis": (0.0, 0.0, 0.0), "axis_order": "xyz"}
		elif ln == "end":
			bones[cur["name"]] = cur
			cur = None
		elif cur is not None:
			key, _, rest = ln.partition(" ")
			rest = rest.strip()
			if key == "name":
				cur["name"] = rest
			elif key == "direction":
				cur["direction"] = np.array([float(v) for v in rest.split()])
			elif key == "length":
				cur["length"] = float(rest) * to_m
			elif key == "axis":
				p = rest.split()
				cur["axis"] = tuple(float(v) for v in p[:3])
				cur["axis_order"] = p[3].lower()
			elif key == "dof":
				cur["dof"] = rest.lower().split()
	children: dict[str, list[str]] = {}
	for ln in sec["hierarchy"]:
		if ln in ("begin", "end"):
			continue
		p = ln.split()
		children[p[0]] = p[1:]
	# "root" is not in bonedata: set by hand, a joint at the recording's origin
	order: list[str] = ["root"]
	parent_of: dict[str, str] = {}
	queue = ["root"]
	while queue:
		b = queue.pop(0)
		for c in children.get(b, []):
			parent_of[c] = b
			order.append(c)
			queue.append(c)
	names = list(order)
	parent = [-1] + [names.index(parent_of[b]) for b in order[1:]]
	offset = np.zeros((len(names), 3))
	for b in order[1:]:
		p = parent_of[b]
		if p != "root":
			offset[names.index(b)] = bones[p]["direction"] * bones[p]["length"]
	# the first level of bones has no offset from the root: the ASF's root "bone" has no length (the root's place
	# comes from the AMC)
	# leaves for the ends: the tip of the foot, of the head, of the hand
	for b in list(order[1:]):
		if not children.get(b):
			names.append(b + "_end")
			parent.append(names.index(b))
			offset = np.vstack([offset, bones[b]["direction"] * bones[b]["length"]])
	is_end = [False] * len(order) + [True] * (len(names) - len(order))
	sk = Skeleton(names, parent, offset, is_end)

	# AMC: a frame is a line with its number and a line for each bone, always in the same order
	with open(amc_path, "r", encoding="utf-8", errors="replace") as f:
		raw = [ln.strip() for ln in f.read().splitlines()]
	lines = [ln for ln in raw if ln and not ln.startswith("#") and not ln.startswith(":")]
	if not lines or not lines[0].isdigit():
		raise ValueError(f"{amc_path}: no frames")
	stride = 1
	while stride < len(lines) and not lines[stride].isdigit():
		stride += 1
	if len(lines) % stride:
		raise ValueError(f"{amc_path}: the number of lines is not a whole number of frames")
	if not all(x.isdigit() for x in lines[0::stride]):
		raise ValueError(f"{amc_path}: frames of different lengths")
	order_in_frame = [ln.split()[0] for ln in lines[1:stride]]
	slot = {nm: 1 + k for k, nm in enumerate(order_in_frame)}
	n = len(lines) // stride

	def column(nm: str, cnt: int) -> np.ndarray:
		sl = lines[slot[nm]::stride]
		if any(ln.split(None, 1)[0] != nm for ln in sl[:1] + sl[-1:]):
			raise ValueError(f"{amc_path}: the order of the bones changes")
		arr = np.fromstring(" ".join(ln[len(nm):] for ln in sl), dtype=np.float64, sep=" ")
		return arr.reshape(n, cnt)
	jn = len(names)
	local = np.tile(np.eye(3), (n, jn, 1, 1))
	root_pos = np.zeros((n, 3))
	rootsec = sec.get("root", [])
	toks: list[str] = []
	root_axis_order = "xyz"
	root_axis_deg = (0.0, 0.0, 0.0)
	for ln in rootsec:
		p = ln.split()
		if p[0] == "order":
			toks = [t.lower() for t in p[1:]]
		elif p[0] == "axis":
			root_axis_order = p[-1].lower()
		elif p[0] == "orientation":
			root_axis_deg = tuple(float(v) for v in p[1:4])
	rot_axes_root = "".join(t[1] for t in toks if t.startswith("r"))
	arr_root = column("root", 6)
	root_pos[:] = arr_root[:, :3] * to_m
	local[:, 0] = _conj(root_axis_deg, root_axis_order, _ordered(rot_axes_root, arr_root[:, 3:6], ASF_M_REVERSED))
	for b in order[1:]:
		bd = bones[b]
		dof = bd["dof"]
		if not dof:
			continue
		axes = "".join(d[1] for d in dof)
		vals = column(b, len(dof))
		m = _ordered(axes, vals, ASF_M_REVERSED)
		local[:, names.index(b)] = _conj(bd["axis"], bd["axis_order"], m)
	return Motion(sk, 120.0, root_pos, local, name=name, meta={"asf_units": units})


# The order ASF/AMC turns are multiplied in. False: for "rx ry rz" the matrix Rx·Ry·Rz (the first listed is the
# outer, as BVH's channels); True: Rz·Ry·Rx. Chosen by the foot standing still while it bears the weight (enarche's
# selftest.py, group 6b, and its report).
ASF_M_REVERSED = True       # the order for the AMC's dof angles
ASF_C_REVERSED = True       # the order for the ASF's axis field


def _ordered(order: str, ang: np.ndarray, reverse: bool) -> np.ndarray:
	if reverse:
		return euler_product(order[::-1], ang[..., ::-1])
	return euler_product(order, ang)


def _conj(axis_deg, axis_order: str, m: np.ndarray) -> np.ndarray:
	"""C · M · C⁻¹, where C is the turn of the bone's axes (axis) in the order axis_order."""
	c = _ordered(axis_order, np.array(axis_deg, dtype=np.float64), ASF_C_REVERSED)
	return c @ m @ c.T
