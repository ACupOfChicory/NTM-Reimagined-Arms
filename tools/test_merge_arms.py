#!/usr/bin/env python3
"""Tests of merge_arms.py: python3 tools/test_merge_arms.py"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import merge_arms as m  # noqa: E402

JAR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "ntmgunfeel-guns.properties")

FILE = """# head
hbm\\:gun_a.leftAt = 0,1,2
hbm\\:gun_a.kickPreset = smg
# the reload: the left hand to the pump
hbm\\:gun_a.armLeftPath = RELOAD 0:grip 100:Pump@0,0,0
hbm\\:gun_a-Barrel.left = 0.5,0,0.7
hbm\\:gun_b.rightAt = 1,1,1
hbm\\:gun_b.armRollLeft = -50
hbm\\:gun_c.sameAs = hbm:gun_b
hbm\\:gun_d.leftAt = 9,9,9
"""


def text_of(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def export(gun, arms):
    return "# gun: " + gun + "\n" + "".join(m.line_of(k, v) for k, v in arms.items())


class MergeArms(unittest.TestCase):
    def merged(self, gun, arms, text=FILE):
        g, a = m.read_export(export(gun, arms))
        return "".join(m.merge(text.splitlines(keepends=True), g, a))

    def test_a_changed_key_keeps_its_place_and_comment(self):
        out = self.merged("hbm:gun_a", {"hbm:gun_a.leftAt": "0,1,2", "hbm:gun_a.armLeftPath": "RELOAD 0:grip",
                                        "hbm:gun_a-Barrel.left": "0.5,0,0.7"})
        self.assertEqual(FILE.replace("RELOAD 0:grip 100:Pump@0,0,0", "RELOAD 0:grip"), out)

    def test_a_key_the_export_has_not_is_dropped_others_stay(self):
        out = self.merged("hbm:gun_a", {"hbm:gun_a.leftAt": "0,1,2"})
        self.assertNotIn("gun_a.armLeftPath", out)
        self.assertNotIn("gun_a-Barrel", out)
        self.assertIn("hbm\\:gun_a.kickPreset = smg\n", out, "not an arm setting")
        self.assertIn("# the reload", out)

    def test_a_new_key_goes_after_the_guns_last_line(self):
        out = self.merged("hbm:gun_b", {"hbm:gun_b.rightAt": "1,1,1", "hbm:gun_b.armRollLeft": "-50", "hbm:gun_b.armScaleLeft": "1.2"})
        self.assertIn("hbm\\:gun_b.armRollLeft = -50\nhbm\\:gun_b.armScaleLeft = 1.2\nhbm\\:gun_c.sameAs", out)

    def test_a_gun_with_no_line_goes_in_its_place(self):
        out = self.merged("hbm:gun_bb", {"hbm:gun_bb.leftAt": "1,2,3"})
        self.assertIn("gun_b.armRollLeft = -50\nhbm\\:gun_bb.leftAt = 1,2,3\nhbm\\:gun_c", out)

    def test_an_empty_value_is_kept_only_where_it_hides_the_shared_guns(self):
        out = self.merged("hbm:gun_c", {"hbm:gun_c.armRollLeft": "", "hbm:gun_c.rightAt": "2,2,2"})
        self.assertIn("hbm\\:gun_c.sameAs = hbm:gun_b\nhbm\\:gun_c.armRollLeft =\nhbm\\:gun_c.rightAt = 2,2,2\n", out)
        out = self.merged("hbm:gun_d", {"hbm:gun_d.leftAt": "9,9,9", "hbm:gun_d.armRollLeft": ""})
        self.assertEqual(FILE, out)

    def test_an_export_with_a_foreign_or_wrong_key_is_refused(self):
        with self.assertRaises(m.Bad):
            m.read_export(export("hbm:gun_a", {"hbm:gun_b.leftAt": "1,1,1"}))
        with self.assertRaises(m.Bad):
            m.read_export(export("hbm:gun_a", {"hbm:gun_a.kickPreset": "smg"}))
        with self.assertRaises(m.Bad):
            m.read_export("hbm\\:gun_a.leftAt = 1,1,1\n")

    # every gun of the jar: its own arm keys as an export change nothing
    def test_the_jars_own_arms_change_nothing(self):
        with open(JAR, encoding="utf-8") as f:
            lines = f.readlines()
        guns = {}
        for l in lines:
            kv = m.split(l)
            if kv and m.arm_name(kv[0][kv[0].rfind(".") + 1:]):
                guns.setdefault(m.owner(kv[0]), {})[kv[0]] = kv[1]
        self.assertGreater(len(guns), 40)
        for gun, arms in guns.items():
            self.assertEqual(lines, m.merge(lines, gun, arms), gun)

    def test_the_script_writes_the_file_and_check_does_not(self):
        with tempfile.TemporaryDirectory() as d:
            jar, exp = os.path.join(d, "guns.properties"), os.path.join(d, "x.properties")
            with open(jar, "w") as f:
                f.write(FILE)
            with open(exp, "w") as f:
                f.write(export("hbm:gun_d", {"hbm:gun_d.leftAt": "1,1,1"}))
            self.assertEqual(0, m.main([exp, "--file", jar, "--check"]))
            self.assertEqual(FILE, text_of(jar))
            self.assertEqual(0, m.main([exp, "--file", jar]))
            self.assertIn("hbm\\:gun_d.leftAt = 1,1,1\n", text_of(jar))
            with open(exp, "w") as f:
                f.write("hbm\\:gun_d.leftAt = 1,1,1\n")
            self.assertEqual(2, m.main([exp, "--file", jar]))


if __name__ == "__main__":
    unittest.main()
