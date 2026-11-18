#!/usr/bin/env python3
"""actions_to_mod.py: a clip goes in whole by its name in the mod, its keys as BotClip reads them."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import actions_to_mod as m  # noqa: E402


def anim():
	return {"animation_length": 0.6, "loop": True, "bones": {
		"right_arm": {"rotation": {"0.5": {"post": [4.00004, 5.0, 6.0]}, "0.0": [1.0, 2.0, 3.0]},
					  "position": {"0.0": {"post": [0.0, 1.0, 0.0]}}},
		"left_leg": {"rotation": {}},
	}}


class ActionsToMod(unittest.TestCase):
	def test_a_clip_whole_by_its_name_in_the_mod(self):
		d = m.build({"throw_cmu": anim()}, {"throw_cmu": {"source": "CMU 79_35", "license": "l", "credit": "c", "x": 1}},
					{"throw_person": "throw_cmu"})
		c = d["animations"]["throw_person"]
		self.assertFalse(c["loop"])
		self.assertEqual(c["animation_length"], 0.6)
		self.assertEqual(list(c["bones"]["right_arm"]["rotation"]), ["0.0000", "0.5000"])
		self.assertEqual(c["bones"]["right_arm"]["rotation"]["0.0000"], {"post": [1.0, 2.0, 3.0]})
		self.assertEqual(c["bones"]["right_arm"]["rotation"]["0.5000"], {"post": [4.0, 5.0, 6.0]})
		self.assertEqual(c["bones"]["right_arm"]["position"]["0.0000"], {"post": [0.0, 1.0, 0.0]})
		self.assertNotIn("left_leg", c["bones"])
		self.assertEqual(d["about"]["throw_person"], {"source": "CMU 79_35", "license": "l", "credit": "c"})

	def test_no_clip_no_action(self):
		notes = {"t": {"source": "s", "license": "l", "credit": "c"}}
		with self.assertRaises(ValueError):
			m.build({}, notes, {"throw_person": "t"})
		with self.assertRaises(ValueError):
			m.build({"t": {"animation_length": 1.0, "bones": {"a": {"rotation": {}}}}}, notes, {"x": "t"})

	def test_no_credit_no_action(self):
		with self.assertRaises(ValueError):
			m.build({"t": anim()}, {"t": {"source": "s", "license": "l"}}, {"x": "t"})

	def test_a_key_not_three_numbers_is_an_error(self):
		for v in ([0, 0], [0, float("nan"), 0], [float("inf"), 0, 0]):
			with self.assertRaises(ValueError):
				m.keys({"0.0": v})

	def test_a_name_given_twice_is_an_error(self):
		self.assertEqual(m.main(["x", "a.json", "b.json", "c.json", "t=a", "t=b"]), 2)

	def test_two_keys_on_one_time_are_an_error(self):
		with self.assertRaises(ValueError):
			m.keys({"0.00001": [0, 0, 0], "0.00002": [1, 1, 1]})


if __name__ == "__main__":
	unittest.main()
