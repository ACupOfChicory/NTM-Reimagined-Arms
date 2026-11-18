#!/usr/bin/env python3
"""moves_to_mod.py: the legs and the curves go in as they are; a move without its legs is no move."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import moves_to_mod as m  # noqa: E402


def anim(length=1.0):
	keys = {"0.0": {"post": [1.0, 2.0, 3.0]}, "0.5": {"post": [4.0, 5.0, 6.0]}}
	return {"animation_length": length, "bones": {b: {"rotation": keys} for b in m.LEGS} | {"root": {"position": {}}}}


def meta(kind="turn"):
	return {"kind": kind, "turn_left_deg": [0.0, 45.0, 90.0], "ahead_m": [0.0, 0.5, 1.0], "curve_hz": 60, "drop_px": 0.45,
			"ends_on_foot": "left", "stances": {"right": [[0.0, 0.5]], "left": [[0.0, 1.0]]}, "source": "s", "license": "l",
			"credit": "c"}


class MovesToMod(unittest.TestCase):
	def test_a_turn_by_its_turn_a_start_by_its_way(self):
		d = m.build({"a_lower": anim(), "b_lower": anim()}, {"a": meta("turn"), "b": meta("start")})["moves"]
		self.assertEqual(d["a"]["curve"], [0.0, 45.0, 90.0])
		self.assertEqual(d["b"]["curve"], [0.0, 0.5, 1.0])
		self.assertEqual(d["a"]["bones"]["left_shin"]["t"], [0.0, 0.5])
		self.assertEqual(d["a"]["bones"]["left_shin"]["r"], [1.0, 2.0, 3.0, 4.0, 5.0, 6.0])
		self.assertEqual(set(d["a"]["bones"]), set(m.LEGS))
		self.assertEqual(d["a"]["drop"], 0.45)

	def test_a_down_and_an_up_with_their_crouch(self):
		down, up = meta("down"), meta("up")
		down["crouch"] = [0.0, 1.0, 1.0]
		up["crouch"] = [1.0, 0.0, 0.0]
		del up["drop_px"]
		up["gait_drop_px"] = 0.45
		d = m.build({"a_lower": anim(), "b_lower": anim(), "c_lower": anim()}, {"a": down, "b": up, "c": meta("stop")})
		self.assertEqual(d["moves"]["a"]["crouch"], [0.0, 1.0, 1.0])
		self.assertEqual(d["moves"]["b"]["crouch"], [1.0, 0.0, 0.0])
		self.assertEqual(d["moves"]["b"]["drop"], 0.45)
		self.assertEqual(d["moves"]["a"]["curve"], [0.0, 0.5, 1.0])
		self.assertNotIn("crouch", d["moves"]["c"])
		self.assertIn("ACCAD", d["about"])
		self.assertNotIn("ACCAD", m.build({"c_lower": anim()}, {"c": meta("stop")})["about"])
		# a share off the curve's samples, or out of 0 to 1
		for bad in ([0.0, 1.0], [0.0, 1.5, 1.0]):
			down["crouch"] = bad
			with self.assertRaises(ValueError):
				m.build({"a_lower": anim()}, {"a": down})

	def test_no_legs_no_move(self):
		a = anim()
		del a["bones"]["left_shin"]
		with self.assertRaises(ValueError):
			m.build({"a_lower": a}, {"a": meta()})
		with self.assertRaises(ValueError):
			m.build({}, {"a": meta()})


if __name__ == "__main__":
	unittest.main()
