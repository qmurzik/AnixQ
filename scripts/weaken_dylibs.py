#!/usr/bin/env python3
"""Marks selected dylib dependencies of a Mach-O binary as weak (LC_LOAD_WEAK_DYLIB).

Xcode 26 autolinks Swift overlays that only ship with iOS 26 (e.g. libswiftWebKit). A strong
dependency makes dyld abort at launch on iOS 16–18, a weak one is simply skipped there.
Usage: weaken_dylibs.py <binary> [name-substring ...]   (prints every dependency)
"""
import struct
import sys

LC_LOAD_DYLIB = 0xC
LC_LOAD_WEAK_DYLIB = 0x80000018
MH_MAGIC_64 = 0xFEEDFACF
FAT_MAGIC = 0xCAFEBABE


def patch_slice(data, base, targets):
    magic, = struct.unpack_from("<I", data, base)
    if magic != MH_MAGIC_64:
        return 0
    ncmds, = struct.unpack_from("<I", data, base + 16)
    off = base + 32
    changed = 0
    for _ in range(ncmds):
        cmd, size = struct.unpack_from("<II", data, off)
        if cmd in (LC_LOAD_DYLIB, LC_LOAD_WEAK_DYLIB):
            name_off, = struct.unpack_from("<I", data, off + 8)
            name = bytes(data[off + name_off: off + size]).split(b"\0")[0].decode()
            weak = cmd == LC_LOAD_WEAK_DYLIB
            hit = any(t in name for t in targets)
            if hit and not weak:
                struct.pack_into("<I", data, off, LC_LOAD_WEAK_DYLIB)
                changed += 1
                weak = True
            print(("weak   " if weak else "strong ") + name)
        off += size
    return changed


def main():
    path, targets = sys.argv[1], sys.argv[2:] or ["libswiftWebKit"]
    data = bytearray(open(path, "rb").read())
    changed = 0
    magic, = struct.unpack_from(">I", data, 0)
    if magic == FAT_MAGIC:
        n, = struct.unpack_from(">I", data, 4)
        for i in range(n):
            _, _, offset, _, _ = struct.unpack_from(">IIIII", data, 8 + i * 20)
            changed += patch_slice(data, offset, targets)
    else:
        changed += patch_slice(data, 0, targets)
    open(path, "wb").write(data)
    print(f"weakened {changed} load command(s) in {path}")


if __name__ == "__main__":
    main()
