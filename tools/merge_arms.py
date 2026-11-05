#!/usr/bin/env python3
"""Put a gun's arms, exported by the arm editor ("Руки пушки", page "Файл"), into the jar's gun settings.

    tools/merge_arms.py <export.properties> [--file <guns.properties>] [--check]

The export's "# gun: <id>" line names the gun. Each arm setting of that gun and of its variants ("<gun>-Barrel.leftAt")
in the settings file takes the export's value: a key in both keeps its place (and the comments around it), a key the
export does not have is dropped, a key only the export has goes after the gun's last line. Nothing else is touched.
The change is printed as a diff; --check prints it and writes nothing. The default file is
src/main/resources/ntmgunfeel-guns.properties next to this script.
"""
import argparse
import difflib
import os
import sys

GRIPS = {"right", "left", "rightAt", "leftAt"}


class Bad(Exception):
    pass


def arm_name(name):
    return name.startswith("arm") or name in GRIPS


def split(line):
    """(key, value) of a settings line as java.util.Properties reads ours, or None for a comment or a blank line."""
    s = line.lstrip()
    if not s or s[0] in "#!":
        return None
    if line.rstrip("\n").endswith("\\"):
        raise Bad("a line goes on to the next (ends with a backslash), which this script does not read: " + line.strip())
    key, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            key.append(s[i + 1])
            i += 2
            continue
        if c in "=: \t\n":
            break
        key.append(c)
        i += 1
    rest = s[i:].lstrip(" \t")
    if rest[:1] in ("=", ":"):
        rest = rest[1:]
    value = rest.strip()
    if "\\" in value:
        raise Bad("a value with a backslash, which this script does not read: " + line.strip())
    return "".join(key), value


def escape(key):
    return key.replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=").replace(" ", "\\ ")


def line_of(key, value):
    return escape(key) + (" = " + value if value else " =") + "\n"


def owner(key):
    """The gun a key is of: "hbm:gun_maresleg-Stock-Barrel.left" -> "hbm:gun_maresleg"."""
    gun = key[:key.rfind(".")] if "." in key else key
    return gun.split("-")[0]


def read_export(text):
    gun, arms = None, {}
    for line in text.splitlines(keepends=True):
        if line.startswith("# gun: "):
            gun = line[len("# gun: "):].strip()
        kv = split(line)
        if kv:
            arms[kv[0]] = kv[1]
    if not gun:
        raise Bad("the export has no \"# gun: <id>\" line")
    for k in arms:
        if owner(k) != gun:
            raise Bad("a key of another gun in the export: " + k)
        if not arm_name(k[k.rfind(".") + 1:]):
            raise Bad("not an arm setting in the export: " + k)
    return gun, arms


def merge(lines, gun, arms):
    """The settings file's lines with the gun's arm keys made what the export says."""
    keys = [split(l) for l in lines]
    shared = any(kv and kv[0] == gun + ".sameAs" and kv[1] for kv in keys)
    if not shared:
        # a gun that shares no other's settings: an empty value hides nothing
        arms = {k: v for k, v in arms.items() if v}
    out, done, last = [], set(), -1
    for line, kv in zip(lines, keys):
        if kv and owner(kv[0]) == gun:
            k = kv[0]
            if arm_name(k[k.rfind(".") + 1:]):
                if k in done:
                    continue
                if k not in arms:
                    continue
                done.add(k)
                out.append(line if kv[1] == arms[k] else line_of(k, arms[k]))
            else:
                out.append(line)
            last = len(out) - 1
            continue
        out.append(line)
    new = [line_of(k, arms[k]) for k in sorted(arms) if k not in done]
    if not new:
        return out
    if last < 0:
        # no line of the gun yet: before the first gun that sorts after it, with the comments over that one
        last = len(out) - 1
        for i, l in enumerate(out):
            kv = split(l)
            if kv and owner(kv[0]) > gun:
                last = i - 1
                while last >= 0 and out[last].lstrip().startswith("#"):
                    last -= 1
                break
    return out[:last + 1] + new + out[last + 1:]


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("export")
    ap.add_argument("--file", default=os.path.join(here, "..", "src", "main", "resources", "ntmgunfeel-guns.properties"))
    ap.add_argument("--check", action="store_true", help="print the change, write nothing")
    a = ap.parse_args(argv)
    try:
        with open(a.export, encoding="utf-8") as f:
            gun, arms = read_export(f.read())
        with open(a.file, encoding="utf-8") as f:
            lines = f.readlines()
        merged = merge(lines, gun, arms)
    except (Bad, OSError) as e:
        print("merge_arms: " + str(e), file=sys.stderr)
        return 2
    diff = list(difflib.unified_diff(lines, merged, a.file, a.file + " (" + gun + ")"))
    if not diff:
        print("merge_arms: " + gun + ": the file already has these arms")
        return 0
    sys.stdout.writelines(diff)
    if not a.check:
        with open(a.file, "w", encoding="utf-8") as f:
            f.writelines(merged)
        print("merge_arms: " + gun + ": written to " + a.file)
    return 0


if __name__ == "__main__":
    sys.exit(main())
