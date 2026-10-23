#!/usr/bin/env python3
"""A copy of the NTM jar that pretends to be another version, to try the untested-NTM protection on a server
(./gradlew runDumpBlocks) without a real newer NTM.

  spoof_ntm.py IN.jar OUT.jar VERSION [CLASS METHOD]

VERSION goes into the manifest (NeoForge reads NTM's version from Implementation-Version). With CLASS METHOD
(e.g. com/hbm/items/weapon/sedna/factory/Lego calcSpread) that method is renamed in that class, as if NTM had
renamed it: the name in the class's constant pool gets its last letter changed, so nothing else in the file moves.
Calls to it from other NTM classes would then fail, so only for a server that starts and stops.
"""
import struct
import sys
import zipfile


def rename(data: bytes, old: str) -> bytes:
    """Change the CONSTANT_Utf8 entry `old` (same length, last letter swapped)."""
    count = struct.unpack(">H", data[8:10])[0]
    i, n = 10, 1
    sizes = {7: 2, 8: 2, 16: 2, 19: 2, 20: 2, 3: 4, 4: 4, 9: 4, 10: 4, 11: 4, 12: 4, 17: 4, 18: 4, 15: 3}
    while n < count:
        tag = data[i]
        if tag == 1:
            ln = struct.unpack(">H", data[i + 1:i + 3])[0]
            s = data[i + 3:i + 3 + ln]
            if s == old.encode():
                new = old[:-1] + ("X" if old[-1] != "X" else "Y")
                return data[:i + 3] + new.encode() + data[i + 3 + ln:]
            i += 3 + ln
        elif tag in (5, 6):
            i += 9
            n += 1
        else:
            i += 1 + sizes[tag]
        n += 1
    sys.exit(f"{old} not in the class")


def main():
    src, dst, version = sys.argv[1:4]
    cls, method = (sys.argv[4], sys.argv[5]) if len(sys.argv) > 5 else (None, None)
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "META-INF/MANIFEST.MF":
                lines = [l for l in data.decode().splitlines() if l and not l.startswith("Implementation-Version:")]
                data = ("\r\n".join(lines + [f"Implementation-Version: {version}"]) + "\r\n").encode()
            elif cls and item.filename == cls + ".class":
                data = rename(data, method)
            zout.writestr(item, data)


if __name__ == "__main__":
    main()
