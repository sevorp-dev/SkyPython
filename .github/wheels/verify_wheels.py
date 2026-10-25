"""Checks Android wheels before they are published (EXPERIMENTAL, used by build-wheels.yml).

For every wheel given on the command line:
  * the platform tag is android_<api>_<abi> with api <= --max-api (our minSdk, 24);
  * every .so is ELF of the class/machine that ABI needs;
  * every LOAD segment is aligned to at least 16 KB (Android 15+ 16 KB page devices);
  * every DT_NEEDED is a system library, libpython, or a library shipped in the same
    wheel (e.g. auditwheel's vendored libc++_shared-<hash>.so / libopenblas-<hash>.so).

Standard library only, so it can run anywhere: python verify_wheels.py wheelhouse/*.whl
"""

from __future__ import annotations

import argparse
import posixpath
import re
import struct
import sys
import zipfile

# ABI -> (ELF class, e_machine)
ABIS = {
    "arm64_v8a": (64, 0xB7),  # AArch64
    "x86_64": (64, 0x3E),
    "armeabi_v7a": (32, 0x28),  # ARM
    "x86": (32, 0x03),  # Intel 80386
}
# Public NDK system libraries an extension may link against.
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so",
    "libjnigraphics.so", "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libOpenSLES.so",
    "libmediandk.so", "libvulkan.so", "libnativewindow.so", "libsync.so", "libstdc++.so",
}
PAGE = 16384
TAG_RE = re.compile(r"-android_(\d+)_([a-z0-9_]+)\.whl$")


def elf_info(b: bytes) -> dict:
    if b[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    cls = {1: 32, 2: 64}[b[4]]
    if b[5] != 1:
        raise ValueError("not little-endian")
    (machine,) = struct.unpack_from("<H", b, 0x12)
    if cls == 64:
        phoff, shoff = struct.unpack_from("<QQ", b, 0x20)
        phentsize, phnum, shentsize, shnum, shstrndx = struct.unpack_from("<HHHHH", b, 0x36)
    else:
        phoff, shoff = struct.unpack_from("<II", b, 0x1C)
        phentsize, phnum, shentsize, shnum, shstrndx = struct.unpack_from("<HHHHH", b, 0x2A)

    aligns, dynamic = [], None
    for i in range(phnum):
        o = phoff + i * phentsize
        if cls == 64:
            p_type, _f, p_offset, _v, _p, p_filesz, _m, p_align = struct.unpack_from("<IIQQQQQQ", b, o)
        else:
            p_type, p_offset, _v, _p, p_filesz, _m, _f, p_align = struct.unpack_from("<IIIIIIII", b, o)
        if p_type == 1:  # PT_LOAD
            aligns.append(p_align)
        elif p_type == 2:  # PT_DYNAMIC
            dynamic = (p_offset, p_filesz)

    sections = []
    for i in range(shnum):
        o = shoff + i * shentsize
        fmt = "<IIQQQQ" if cls == 64 else "<IIIIII"
        name, stype, _flags, _addr, offset, size = struct.unpack_from(fmt, b, o)
        sections.append((name, stype, offset, size))

    def cstr(base: int, off: int) -> str:
        end = b.index(b"\0", base + off)
        return b[base + off:end].decode()

    shstr = sections[shstrndx][2] if sections else 0
    dynstr = next((s for s in sections if cstr(shstr, s[0]) == ".dynstr"), None)
    needed, runpath = [], []
    if dynamic and dynstr:
        size, fmt = (16, "<qQ") if cls == 64 else (8, "<iI")
        for k in range(dynamic[1] // size):
            tag, val = struct.unpack_from(fmt, b, dynamic[0] + k * size)
            if tag == 0:
                break
            if tag == 1:
                needed.append(cstr(dynstr[2], val))
            elif tag in (15, 29):  # DT_RPATH, DT_RUNPATH
                runpath.append(cstr(dynstr[2], val))
    return {"class": cls, "machine": machine, "aligns": aligns, "needed": needed, "runpath": runpath}


def check_wheel(path: str, max_api: int) -> list[str]:
    errors = []
    m = TAG_RE.search(path)
    if not m:
        return [f"{path}: not an android_<api>_<abi> wheel"]
    api, abi = int(m.group(1)), m.group(2)
    if api > max_api:
        errors.append(f"{path}: API level {api} > {max_api}; devices on API {max_api} cannot install it")
    if abi not in ABIS:
        return errors + [f"{path}: unknown ABI {abi}"]
    want_cls, want_machine = ABIS[abi]

    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        basenames = {posixpath.basename(n) for n in names}
        libs = [n for n in names if n.endswith(".so") or ".so." in posixpath.basename(n)]
        if not libs:
            errors.append(f"{path}: no native libraries (pure-Python packages need no Android wheel)")
        for n in libs:
            try:
                info = elf_info(z.read(n))
            except Exception as e:  # noqa: BLE001 - report and keep checking
                errors.append(f"{path}:{n}: {e}")
                continue
            where = f"{path}:{n}"
            if (info["class"], info["machine"]) != (want_cls, want_machine):
                errors.append(f"{where}: ELF{info['class']} machine {info['machine']:#x}, want ELF{want_cls} {want_machine:#x}")
            bad = [a for a in info["aligns"] if a < PAGE]
            if bad:
                errors.append(f"{where}: LOAD alignment {bad} < {PAGE} (fails on 16 KB page devices)")
            for lib in info["needed"]:
                if lib in SYSTEM_LIBS or re.fullmatch(r"libpython3\.\d+\.so", lib) or lib in basenames:
                    continue
                errors.append(f"{where}: needs {lib}, which is neither a system library nor in the wheel")
            print(f"  {n}: ELF{info['class']} align={sorted(set(info['aligns']))} needed={info['needed']} runpath={info['runpath']}")
    return errors


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("wheels", nargs="+")
    ap.add_argument("--max-api", type=int, default=24)
    args = ap.parse_args()
    errors = []
    for w in args.wheels:
        print(w)
        errors += check_wheel(w, args.max_api)
    for e in errors:
        print(f"::error::{e}")
    print("OK" if not errors else f"{len(errors)} problem(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
