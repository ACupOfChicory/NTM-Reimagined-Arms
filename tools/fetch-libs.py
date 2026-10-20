#!/usr/bin/env python3
"""Downloads everything the mod compiles and is tested against into libs/.

  libs/minecraft/   Minecraft client jar and Mojang's libraries (no natives)
  libs/neoforge/    NeoForge universal jar and its loader libraries
  libs/test/        JUnit console launcher
  libs/mods/        NTM NEXT jar: put it here yourself, or pass --ntm <url or path>

Nothing here is needed to run the game; it is only the class path for javac and the tests.
Files already present with the right checksum are skipped, so running it again is cheap.
"""
import argparse
import hashlib
import io
import json
import os
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

MC = "26.2"
NEOFORGE = "26.2.0.90"
JUNIT = "1.13.4"

ROOT = Path(__file__).resolve().parent.parent
MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
NEO_MAVEN = "https://maven.neoforged.net/releases"
CENTRAL = "https://repo1.maven.org/maven2"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "ntmgunfeel-fetch-libs"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def sha1_of(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(url, dest, sha1=None):
    dest = Path(dest)
    if dest.exists() and (sha1 is None or sha1_of(dest) == sha1):
        return False
    data = get(url)
    if sha1 is not None and hashlib.sha1(data).hexdigest() != sha1:
        sys.exit(f"checksum mismatch: {url}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    tmp.write_bytes(data)
    tmp.replace(dest)
    print(f"  got {dest.relative_to(ROOT) if dest.is_relative_to(ROOT) else dest}")
    return True


def maven_path(coord):
    """group:artifact:version[:classifier] -> group/path/artifact/version/artifact-version[-classifier].jar"""
    parts = coord.split(":")
    group, artifact, version = parts[:3]
    classifier = f"-{parts[3]}" if len(parts) > 3 else ""
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{classifier}.jar"


def is_native(lib):
    parts = lib["name"].split(":")
    return len(parts) > 3 and parts[3].startswith("natives")


def fetch_library(lib, base):
    art = lib.get("downloads", {}).get("artifact")
    if art and art.get("url"):
        fetch(art["url"], base / art["path"], art.get("sha1"))
    elif "url" in lib:  # older style: repository root plus maven path
        path = maven_path(lib["name"])
        fetch(lib["url"].rstrip("/") + "/" + path, base / path)


def minecraft(libs):
    print(f"Minecraft {MC}")
    manifest = json.loads(get(MOJANG_MANIFEST))
    entry = next((v for v in manifest["versions"] if v["id"] == MC), None)
    if entry is None:
        sys.exit(f"Minecraft {MC} is not in Mojang's manifest")
    version = json.loads(get(entry["url"]))
    client = version["downloads"]["client"]
    fetch(client["url"], libs / "minecraft" / f"minecraft-{MC}-client.jar", client["sha1"])
    for lib in version["libraries"]:
        if not is_native(lib):
            fetch_library(lib, libs / "minecraft" / "libraries")


def neoforge(libs):
    print(f"NeoForge {NEOFORGE}")
    base = f"{NEO_MAVEN}/net/neoforged/neoforge/{NEOFORGE}/neoforge-{NEOFORGE}"
    fetch(f"{base}-universal.jar", libs / "neoforge" / f"neoforge-{NEOFORGE}-universal.jar")
    installer = zipfile.ZipFile(io.BytesIO(get(f"{base}-installer.jar")))
    version = json.loads(installer.read("version.json"))
    for lib in version["libraries"]:
        fetch_library(lib, libs / "neoforge" / "libraries")


def junit(libs):
    print(f"JUnit console {JUNIT}")
    name = f"junit-platform-console-standalone-{JUNIT}.jar"
    fetch(f"{CENTRAL}/org/junit/platform/junit-platform-console-standalone/{JUNIT}/{name}", libs / "test" / name)


def ntm(libs, source):
    mods = libs / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    if source:
        name = os.path.basename(source.split("?")[0])
        if not name.endswith(".jar"):
            name = "ntm-next.jar"
        if source.startswith(("http://", "https://")):
            fetch(source, mods / name)
        else:
            shutil.copy(source, mods / name)
            print(f"  copied {name}")
    if not any(mods.glob("*.jar")):
        print(f"\nNTM NEXT jar missing: put it into {mods} (or rerun with --ntm <url or path>).")
        return False
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ntm", help="URL or local path of the NTM NEXT jar to put into libs/mods/")
    ap.add_argument("--libs", default=os.environ.get("LIBS", str(ROOT / "libs")), help="target folder (default libs/)")
    args = ap.parse_args()
    libs = Path(args.libs).resolve()
    minecraft(libs)
    neoforge(libs)
    junit(libs)
    ok = ntm(libs, args.ntm)
    print("done" if ok else "done, except NTM NEXT")


if __name__ == "__main__":
    main()
