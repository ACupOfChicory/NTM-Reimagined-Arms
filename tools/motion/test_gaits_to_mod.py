#!/usr/bin/env python3
"""gaits_to_mod.py: a gait loop goes in by its own name, its legs only, with its pace; each slot its variants."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gaits_to_mod as m  # noqa: E402


def lower():
	legs = {b: {"rotation": {"0.0": {"post": [1.0, 0.0, 2.00004]}, "0.35": [3.0, 0.0, 4.0], "0.7": [1.0, 0.0, 2.0]}} for b in m.LEGS}
	return {"animation_length": 0.7, "loop": True, "bones": {**legs, "root": {"position": {"0.0": [0, 0, 0]}}}}


#: every slot, the one clip in each
ALL = {s: ["run_a"] for s in m.SLOTS}


def note(**kw):
	n = {"source": "CMU 16_45", "license": "l", "credit": "c", "speed_m_s": 3.6, "drop_px": 0.45}
	n.update(kw)
	return n


class GaitsToMod(unittest.TestCase):
	def test_a_loop_by_its_name_its_legs_and_pace(self):
		d = m.build({"run_a_lower": lower(), "run_a": {"bones": {}}}, {"run_a": note()}, ALL)
		c = d["animations"]["run_a"]
		self.assertTrue(c["loop"])
		self.assertEqual(c["animation_length"], 0.7)
		self.assertEqual(sorted(c["bones"]), sorted(m.LEGS))
		self.assertEqual(c["bones"]["left_shin"]["rotation"]["0.0000"], {"post": [1.0, 0.0, 2.0]})
		self.assertEqual(c["bones"]["left_shin"]["rotation"]["0.3500"], {"post": [3.0, 0.0, 4.0]})
		self.assertEqual(d["gaits"]["run_a"], {"speed": 3.6, "drop": 0.45})
		self.assertEqual(d["about"]["run_a"], {"source": "CMU 16_45", "license": "l", "credit": "c"})
		self.assertEqual(d["slots"], ALL)
		self.assertEqual(list(d["animations"]), ["run_a"])

	def test_no_legs_clip_no_gait(self):
		with self.assertRaises(ValueError):
			m.build({"run_a": lower()}, {"run_a": note()}, ALL)

	def test_a_leg_with_no_turns_is_an_error(self):
		a = lower()
		del a["bones"]["right_shin"]
		with self.assertRaises(ValueError):
			m.build({"run_a_lower": a}, {"run_a": note()}, ALL)

	def test_no_pace_or_credit_no_gait(self):
		for bad in (note(speed_m_s=0), note(speed_m_s=float("nan")), note(drop_px=None), note(credit="")):
			with self.assertRaises(ValueError):
				m.build({"run_a_lower": lower()}, {"run_a": bad}, ALL)

	def test_a_slot_empty_or_with_a_clip_twice_is_an_error(self):
		with self.assertRaises(ValueError):
			m.build({"run_a_lower": lower()}, {"run_a": note()}, {**ALL, "jog": []})
		with self.assertRaises(ValueError):
			m.build({"run_a_lower": lower()}, {"run_a": note()}, {**ALL, "jog": ["run_a", "run_a"]})

	def test_a_slot_missing_is_an_error(self):
		with self.assertRaises(ValueError):
			m.build({"run_a_lower": lower()}, {"run_a": note()}, {"walk": ["run_a"], "jog": ["run_a"]})

	def test_an_open_loop_or_keys_short_of_its_ends_are_an_error(self):
		a = lower()
		a["bones"]["left_leg"]["rotation"]["0.7"] = [9.0, 0.0, 2.0]
		with self.assertRaises(ValueError):
			m.build({"run_a_lower": a}, {"run_a": note()}, ALL)
		a = lower()
		del a["bones"]["left_leg"]["rotation"]["0.7"]
		with self.assertRaises(ValueError):
			m.build({"run_a_lower": a}, {"run_a": note()}, ALL)

	def test_hips_at_two_heights_are_an_error(self):
		with self.assertRaises(ValueError):
			m.build({"run_a_lower": lower(), "run_b_lower": lower()}, {"run_a": note(), "run_b": note(drop_px=2.8)},
					{**ALL, "sprint": ["run_b"]})

	def test_a_clip_mirrored_goes_in_by_its_own_name_its_sides_swapped(self):
		a = lower()
		a["bones"]["right_leg"]["rotation"] = {"0.0": [5.0, 6.0, 7.0], "0.7": [5.0, 6.0, 7.0]}
		d = m.build({"run_a_lower": a}, {"run_a": note()}, {**ALL, "aim_right": ["run_a:mirrored"]})
		self.assertEqual(d["slots"]["aim_right"], ["run_a_mirrored"])
		self.assertEqual(d["slots"]["aim_left"], ["run_a"])
		c, o = d["animations"]["run_a_mirrored"], d["animations"]["run_a"]
		# the right's turns the left's, about y and z the other way
		self.assertEqual(c["bones"]["left_leg"]["rotation"]["0.0000"], {"post": [5.0, -6.0, -7.0]})
		self.assertEqual(c["bones"]["right_shin"]["rotation"]["0.3500"], {"post": [3.0, 0.0, -4.0]})
		self.assertEqual(o["bones"]["right_leg"]["rotation"]["0.0000"], {"post": [5.0, 6.0, 7.0]})
		self.assertEqual(c["animation_length"], 0.7)
		self.assertEqual(d["gaits"]["run_a_mirrored"], d["gaits"]["run_a"])
		self.assertEqual(d["about"]["run_a_mirrored"]["source"], "CMU 16_45, mirrored")

	def test_a_slot_given_twice_is_an_error(self):
		self.assertEqual(m.main(["x", "o.json", "a.animation.json", "jog=a", "jog=b"]), 2)


if __name__ == "__main__":
	unittest.main()
