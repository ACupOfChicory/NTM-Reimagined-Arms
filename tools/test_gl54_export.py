#!/usr/bin/env python3
"""Tests of gl54_export.py's parts that need no Blender: python3 tools/test_gl54_export.py"""
import math
import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gl54_export as g  # noqa: E402


class Axes(unittest.TestCase):
    def test_forward_left_up(self):
        # the .blend: +X forward, +Y left, +Z up; NTM: +Z forward, +X left, +Y up
        o = (0.0, 0.0, 0.0)
        self.assertEqual(g.axis_point((1.0, 0.0, 0.0), o, 1.0), (0.0, 0.0, 1.0))
        self.assertEqual(g.axis_point((0.0, 1.0, 0.0), o, 1.0), (1.0, 0.0, 0.0))
        self.assertEqual(g.axis_point((0.0, 0.0, 1.0), o, 1.0), (0.0, 1.0, 0.0))

    def test_it_is_a_turn_not_a_mirror(self):
        # the determinant of (x, y, z) -> (y, z, x) is +1: faces keep their winding
        m = [g.axis_vector(v) for v in ((1, 0, 0), (0, 1, 0), (0, 0, 1))]
        det = (m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
               + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]))
        self.assertEqual(det, 1)

    def test_origin_and_scale(self):
        p = g.axis_point((-0.1 + 0.2, 0.01, -0.05 + 0.1), g.ORIGIN_M, g.UNITS_PER_METER)
        self.assertAlmostEqual(p[0], 0.12)
        self.assertAlmostEqual(p[1], 1.2)
        self.assertAlmostEqual(p[2], 2.4)

    def test_the_case_is_thrown_to_the_right(self):
        # EjectedCase flies to the .blend's -Y: the gun's right, NTM's -X
        self.assertLess(g.axis_vector((0, -1, 0))[0], 0)

    def test_rotation_axes(self):
        self.assertEqual(g.AXIS_TO_NTM, (2, 0, 1))


class Reflex(unittest.TestCase):
    # a pane 2 wide and 1 high, tilted back 30 degrees about the x axis, in the NTM axes (x left, y up, z forward)
    def pane(self):
        c, s = math.cos(math.radians(30)), math.sin(math.radians(30))
        pts = [(0, 0, 0), (2, 0, 0), (2, c, -s), (0, c, -s)]
        return pts, [(0, 0), (1, 0), (1, 1), (0, 1)]

    def test_uv_frame_of_a_plain_pane(self):
        pts, uvs = self.pane()
        o, u, v = g.uv_frame(pts, uvs)
        self.assertAlmostEqual(g.norm(u), 2.0)
        self.assertAlmostEqual(g.norm(v), 1.0)
        self.assertAlmostEqual(u[0], 2.0)
        self.assertAlmostEqual(v[2], -math.sin(math.radians(30)))

    def test_uv_frame_reproduces_every_corner(self):
        # a hexagon with cut corners, as the pane is: any three corners give the same map
        uvs = [(0, 0), (1, 0), (1, 0.9), (0.9, 1), (0.1, 1), (0, 0.9)]
        pts = [(1 + 2 * a, 3 + b, 5 + 0.25 * b) for a, b in uvs]
        o, u, v = g.uv_frame(pts, uvs)
        for p, (a, b) in zip(pts, uvs):
            for k in range(3):
                self.assertAlmostEqual(o[k] + a * u[k] + b * v[k], p[k], places=6)

    def test_numbers_have_the_laser_at_the_lens_front(self):
        pts, uvs = self.pane()
        lens = [(0.1, 0.2, 5.0), (0.3, 0.4, 5.0), (0.2, 0.3, 4.9)]
        out = g.reflex_numbers(pts, uvs, (0, 0.5, 0.8), lens)
        self.assertEqual(out["laser"]["exit"], [0.2, 0.3, 5.0])
        self.assertEqual(out["panel"]["uLength"], 2.0)
        self.assertEqual(len(out["panel"]["corners"]), 4)

    def test_the_pane_is_not_a_part(self):
        self.assertEqual(g.PANEL_OBJECT, "ReflexPanel")


class Animation(unittest.TestCase):
    def pts(self, frames):
        return [(f, v, "LINEAR", "AUTO", (f, v), (f, v), "AUTO_CLAMPED", "AUTO_CLAMPED") for f, v in frames]

    def test_durations_are_the_time_since_the_last_key(self):
        keys = g.keyframes_json(self.pts([(1, 0.0), (3, 1.0), (7, 2.0), (31, 2.0)]), 1, 60.0, lambda v: v)
        self.assertEqual([k[1] for k in keys], [0, 33, 67, 400])
        self.assertEqual(sum(k[1] for k in keys), 500)

    def test_values_are_converted(self):
        keys = g.keyframes_json(self.pts([(1, 0.5)]), 1, 60.0, lambda v: v * 12)
        self.assertEqual(keys[0][0], 6.0)

    def test_unknown_interpolation_is_linear(self):
        keys = g.keyframes_json([(1, 0.0, "WIGGLE", "AUTO", (1, 0), (1, 0), "AUTO", "AUTO")], 1, 60.0, lambda v: v)
        self.assertEqual(keys[0][2], "LINEAR")

    def test_constant_is_kept(self):
        keys = g.keyframes_json([(1, 0.0, "CONSTANT", "AUTO", (1, 0), (1, 0), "AUTO", "AUTO")], 1, 60.0, lambda v: v)
        self.assertEqual(keys[0][2], "CONSTANT")

    def test_action_names(self):
        self.assertEqual(g.animation_name("Fire_BoltCarrier", "BoltCarrier"), "Fire")
        self.assertEqual(g.animation_name("Fire", "BoltCarrier"), "Fire")


class Files(unittest.TestCase):
    def test_zigzag_varint(self):
        self.assertEqual(g.varint_delta(0), b"\x00")
        self.assertEqual(g.varint_delta(-1), b"\x01")
        self.assertEqual(g.varint_delta(1), b"\x02")
        self.assertEqual(g.varint_delta(64), b"\x80\x01")

    def test_mesh_header(self):
        face = ([((0, 0, 0), (0.5, 0.5), (0, 1, 0)), ((1, 0, 0), (0.5, 0.5), (0, 1, 0)), ((1, 0, 1), (0.5, 0.5), (0, 1, 0))], (0, 1, 0))
        data = g.encode_mesh([("Part", [face])])
        self.assertEqual(struct.unpack_from(">ii", data, 0), (g.MESH_MAGIC, g.MESH_VERSION))

    def test_obj_has_no_exponents(self):
        face = ([((1e-9, 0, 123456.0), (0.5, 0.5), (0, 1, 0))] * 3, (0, 1, 0))
        text = g.encode_obj("m", [("Part", [face])])
        self.assertNotIn("e-", text)
        self.assertIn("o Part", text)

    def test_palette_cells(self):
        u, v = g.palette_cell_centre(0)
        self.assertEqual((u, v), (0.5 / 8, 0.5 / 8))
        u, v = g.palette_cell_centre(9)
        self.assertEqual((u, v), (1.5 / 8, 1.5 / 8))

    def test_srgb(self):
        self.assertAlmostEqual(g.linear_to_srgb(0.0), 0.0)
        self.assertAlmostEqual(g.linear_to_srgb(1.0), 1.0)
        self.assertAlmostEqual(g.linear_to_srgb(0.214), 0.5, places=2)


if __name__ == "__main__":
    unittest.main()
