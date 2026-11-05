#!/usr/bin/env python3
"""Leave out of the build the shared files that do not compile here yet, until it compiles.

Usage: tools/prune.py [main|test] [--max N] [--own-ok]
Runs ./gradlew compileJava (compileTestJava for test), puts every shared file javac reports an error in into
pending.txt under [main] ([test]), and goes again, until it compiles or N rounds pass. A file of the port's own
(src/ here) with an error stops it: that is the port's to fix (--own-ok goes on, to see what else falls with it). pending.txt only grows here; porting a part takes
its files out of it by hand.
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ERR = re.compile(r"^(/\S+\.java):\d+: error:")


def read_pending():
    path = os.path.join(HERE, "pending.txt")
    return open(path, encoding="utf-8").read().splitlines() if os.path.exists(path) else []


def add_pending(section, files):
    lines = read_pending()
    start = lines.index("[%s]" % section)
    end = start + 1
    while end < len(lines) and not lines[end].startswith("["):
        end += 1
    have = {l.split("#")[0].strip() for l in lines[start + 1:end]}
    body = [l for l in lines[start + 1:end] if l.strip()]
    body += sorted(f for f in files if f not in have)
    body = sorted(set(body))
    out = lines[:start + 1] + body + [""] + lines[end:]
    open(os.path.join(HERE, "pending.txt"), "w", encoding="utf-8").write("\n".join(out).rstrip("\n") + "\n")


def main():
    section = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else "main"
    rounds = int(sys.argv[sys.argv.index("--max") + 1]) if "--max" in sys.argv else 30
    task = "compileJava" if section == "main" else "compileTestJava"
    shared = os.path.join(HERE, "build", "generated", "shared", section) + os.sep
    for n in range(rounds):
        run = subprocess.run(["./gradlew", task, "--console=plain", "-q"], cwd=HERE,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if run.returncode == 0:
            print("round %d: compiles" % n)
            return 0
        bad = set()
        own = set()
        for line in run.stdout.splitlines():
            m = ERR.match(line)
            if not m:
                continue
            path = m.group(1)
            if path.startswith(shared):
                bad.add(path[len(shared):])
            else:
                own.add(path)
        if own and not ("--own-ok" in sys.argv and bad):
            print("round %d: the port's own files do not compile:" % n)
            for p in sorted(own):
                print("  " + p)
            return 1
        if not bad:
            print(run.stdout[-3000:])
            print("round %d: failed without a file error" % n)
            return 1
        print("round %d: %d files left out" % (n, len(bad)))
        add_pending(section, bad)
    print("still failing after %d rounds" % rounds)
    return 1


if __name__ == "__main__":
    sys.exit(main())
