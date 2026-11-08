#!/usr/bin/env python3
"""Tests of retarget.py: python3 -I tools/quaternius/test_retarget.py [UAL1.glb]

The math runs alone; the pose checks need the UAL1 glb (path argument or
UAL1_GLB in the environment) and are skipped without it; KAYKIT_GLB
(Rig_Medium_General.glb) and SWAT_GLB do the same for those rigs.
"""
import math
import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import retarget as r  # noqa: E402

GLB = os.environ.get("UAL1_GLB") or (sys.argv.pop(1) if len(sys.argv) > 1 else None)
KAYKIT = os.environ.get("KAYKIT_GLB")  # Rig_Medium_General.glb
SWAT = os.environ.get("SWAT_GLB")


class Math(unittest.TestCase):
    def test_euler_round_trip(self):
        rng = np.random.default_rng(1)
        for _ in range(500):
            e = rng.uniform([-180, -89, -180], [180, 89, 180])
            back = r.mat_to_euler(r.euler_to_mat(e), prev=e)
            self.assertLess(np.max(np.abs(back - e)), 1e-6)

    def test_euler_branch_follows_previous(self):
        m = r.euler_to_mat([170.0, 10.0, 0.0])
        self.assertAlmostEqual(r.mat_to_euler(m, prev=np.array([175.0, 10.0, 0.0]))[0], 170.0, places=6)
        self.assertAlmostEqual(r.mat_to_euler(m, prev=np.array([-175.0, 10.0, 0.0]))[0], -190.0, places=6)

    def test_minecraft_arm_forward(self):
        # HumanoidModel: xRot -90 deg swings a hanging arm (+Y) to the front (-Z)
        v = r.euler_to_mat([-90.0, 0.0, 0.0]) @ r.Y
        self.assertTrue(np.allclose(v, [0, 0, -1], atol=1e-9))

    def test_rot_between(self):
        for a, b in [([0, 1, 0], [0, 0, -1]), ([1, 0, 0], [-1, 0, 0]), ([0, 1, 0], [0, 1, 0])]:
            m = r.rot_between(np.array(a, float), np.array(b, float))
            self.assertTrue(np.allclose(m @ np.array(a, float), b, atol=1e-9))
            self.assertTrue(np.allclose(m @ m.T, np.eye(3), atol=1e-9))

    def test_reduce_keys_keeps_corners(self):
        t = [i / 10 for i in range(11)]
        v = np.array([[abs(x - 0.5) * 10] for x in t])
        keys = r.reduce_keys(t, v, 0.01)
        self.assertEqual(keys, [0, 5, 10])

    def test_limb_modes_point_along_their_bones(self):
        # a bent arm: the shoulder at 0, the elbow 1 down, the wrist 1 forward of it
        p = {"P": {"upperarm_r": np.zeros(3), "lowerarm_r": np.array([0.0, 1.0, 0.0]),
                   "hand_r": np.array([0.0, 1.0, -1.0])}}
        d = {m: r.unit(r.Skeleton.limb_dir(p, "right_arm", m)) for m in r.MODES}
        self.assertTrue(np.allclose(d["upper"], [0, 1, 0]))
        self.assertTrue(np.allclose(d["lower"], [0, 0, -1]))
        self.assertTrue(np.allclose(d["end"], r.unit(np.array([0.0, 1.0, -1.0]))))
        # halfway between the upper bone and the whole limb: 22.5 degrees from each
        self.assertAlmostEqual(r.angle(d["mix"], d["upper"]), 22.5, places=6)
        self.assertAlmostEqual(r.angle(d["mix"], d["end"]), 22.5, places=6)
        # the leg's old names
        self.assertTrue(np.allclose(r.Skeleton.limb_dir(p, "right_arm", "thigh"), d["upper"]))

    def test_choices_are_read_and_checked(self):
        import json
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump({"version": 1, "clips": {"UAL1/Idle_Loop": {"arms": "upper", "legs": "lower", "take": True},
                                               "SWAT/x": {"arms": "end", "legs": "mix", "take": False}}}, f)
        try:
            c = r.read_choices(f.name)
            self.assertEqual(c["UAL1/Idle_Loop"]["arms"], "upper")
            self.assertFalse(c["SWAT/x"]["take"])
            with open(f.name, "w") as g:
                json.dump({"clips": {"UAL1/Idle_Loop": {"arms": "knee", "legs": "end"}}}, g)
            with self.assertRaises(ValueError):
                r.read_choices(f.name)
        finally:
            os.unlink(f.name)


@unittest.skipUnless(GLB and os.path.exists(GLB), "UAL1 glb not given")
class Poses(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.g = r.Glb(GLB)
        cls.sk = r.Skeleton(cls.g)

    def solve(self, clip, t=0.0):
        p = self.sk.pose(self.g.clip(clip).world(t))
        return self.sk.solve(p)[0]

    def test_t_pose_is_arms_out(self):
        out = self.solve("A_TPose")
        right = out["right_arm"][1] @ r.Y
        left = out["left_arm"][1] @ r.Y
        self.assertTrue(np.allclose(right, [-1, 0, 0], atol=0.02), right)
        self.assertTrue(np.allclose(left, [1, 0, 0], atol=0.02), left)
        # UAL's rest spine leans 1.2 deg forward, its legs 2.6 deg: kept as they are
        self.assertLess(np.max(np.abs(r.mat_to_euler(out["body"][1]))), 3.0)
        self.assertTrue(np.allclose(out["head"][1], np.eye(3), atol=1e-6))

    def test_t_pose_stands_on_the_ground(self):
        out = self.solve("A_TPose")
        for leg in ("right_leg", "left_leg"):
            self.assertAlmostEqual(out[leg][0][1], 12.0, delta=0.3)

    def test_pistol_aim_right_arm_forward(self):
        out = self.solve("Pistol_Aim_Neutral")
        e = r.mat_to_euler(out["right_arm"][1])
        self.assertAlmostEqual(e[0], -90.0, delta=5.0)
        self.assertLess(abs(e[2]), 10.0)  # no roll: palm faces in, as vanilla's held-out arm

    def test_split_halves_add_up_to_the_whole(self):
        # _lower carries `root`; a part's position in a half plus root is its position in the whole clip
        clip = self.g.clip("Crouch_Idle_Loop")
        chans, _, _, _, times = r.convert(self.sk, clip)
        whole = r.bedrock(times, chans, r.PARTS, clip.length, True)
        upper = r.bedrock(times, chans, r.UPPER, clip.length, True, split=True)
        lower = r.bedrock(times, chans, r.LOWER + ["root"], clip.length, True, split=True)
        self.assertIn("root", lower["bones"])
        self.assertEqual(set(upper["bones"]) | set(lower["bones"]) - {"root"}, set(whole["bones"]))

        def at(track, t):
            ks = sorted(track, key=float)
            ts = [float(k) for k in ks]
            vs = np.array([track[k]["post"] for k in ks])
            return np.array([np.interp(t, ts, vs[:, i]) for i in range(3)])

        root = lower["bones"]["root"]["position"]
        for part, half in (("body", upper), ("right_arm", upper), ("left_leg", lower)):
            for t in np.linspace(0, clip.length, 17):
                got = at(half["bones"][part]["position"], t) + at(root, t)
                want = at(whole["bones"][part]["position"], t)
                self.assertLess(np.max(np.abs(got - want)), 0.15, (part, t))

    def test_fast_swing_follows_between_samples(self):
        clip = self.g.clip("Melee_Hook") if "Melee_Hook" in self.g.anims else self.g.clip("Punch_Cross")
        _, st, _, _, _ = r.convert(self.sk, clip)
        self.assertLess(max(st["mid"]), 3.0)

    def test_arm_modes_follow_their_bone(self):
        p = self.sk.pose(self.g.clip("Pistol_Reload").world(0.8))
        for mode, (a, b) in (("upper", ("upperarm_l", "lowerarm_l")), ("lower", ("lowerarm_l", "hand_l")),
                             ("end", ("upperarm_l", "hand_l"))):
            out = self.sk.solve(p, "ankle", mode)[0]
            self.assertLess(r.angle(out["left_arm"][1] @ r.Y, p["P"][b] - p["P"][a]), 0.01, mode)

    def test_death_ends_lying(self):
        clip = self.g.clip("Death01")
        out = self.solve("Death01", clip.length)
        down = out["body"][1] @ r.Y
        self.assertLess(abs(down[1]), 0.3, down)  # the body's length is level


@unittest.skipUnless(KAYKIT and os.path.exists(KAYKIT), "KayKit glb not given")
class KayKitRest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.g = r.Glb(KAYKIT)
        cls.sk = r.Skeleton(cls.g, "kaykit")

    def test_rest_is_a_t_pose_on_the_ground(self):
        out = self.sk.solve(self.sk.rest)[0]
        self.assertAlmostEqual(r.mat_to_euler(out["right_arm"][1])[2], 90.0, delta=1.0)
        self.assertAlmostEqual(r.mat_to_euler(out["left_arm"][1])[2], -90.0, delta=1.0)
        self.assertTrue(np.allclose(out["head"][1], np.eye(3), atol=1e-6))
        self.assertLess(np.max(np.abs(r.mat_to_euler(out["body"][1]))), 1.0)
        for leg in ("right_leg", "left_leg"):
            self.assertAlmostEqual(out[leg][0][1], 12.0, delta=0.3)

    def test_spawn_scaled_from_nothing_is_seen(self):
        self.assertTrue(self.g.clip("Spawn_Air").zero_scale())
        self.assertFalse(self.g.clip("Idle_A").zero_scale())


@unittest.skipUnless(SWAT and os.path.exists(SWAT), "SWAT glb not given")
class SwatRest(unittest.TestCase):
    def test_rest_hangs_the_arms_and_stands(self):
        g = r.Glb(SWAT)
        sk = r.Skeleton(g, "qmodular")
        out = sk.solve(sk.rest)[0]
        # the rest hangs the arms about 15 deg out, the right one out to the right (+Z turn), the left to the left
        rz, lz = r.mat_to_euler(out["right_arm"][1])[2], r.mat_to_euler(out["left_arm"][1])[2]
        self.assertTrue(5.0 < rz < 25.0, rz)
        self.assertTrue(-25.0 < lz < -5.0, lz)
        for leg in ("right_leg", "left_leg"):
            self.assertAlmostEqual(out[leg][0][1], 12.0, delta=0.4)


if __name__ == "__main__":
    unittest.main()
