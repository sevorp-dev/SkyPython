"""Teaches an installed cibuildwheel the two 32-bit Android ABIs (EXPERIMENTAL).

cibuildwheel only knows arm64_v8a and x86_64 on Android, because python.org publishes
only those CPython builds. SkyPython builds CPython for armeabi-v7a and x86 itself
(build-python.yml, same `Android/android.py package` layout as python.org's), so cibuildwheel
can use them if it is told the identifiers, the host triplets and where the tarballs are.

    python patch_cibw_32bit.py CIBW_PACKAGE_DIR --version 3.14.9 --abi armeabi_v7a \
        --url https://.../python-3.14.9-arm-linux-androideabi.tar.gz --sha256 <hash>

Every edit is checked: if cibuildwheel's code no longer looks as expected (new version),
this fails instead of producing a half-patched tool. Pin cibuildwheel when using it.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

TRIPLETS = {"armeabi_v7a": "arm-linux-androideabi", "x86": "i686-linux-android"}


def replace_once(path: pathlib.Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return  # already patched
    if text.count(old) != 1:
        sys.exit(f"{path}: expected exactly one {old!r}; cibuildwheel changed, update this script")
    path.write_text(text.replace(old, new), encoding="utf-8", newline="\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("package_dir", type=pathlib.Path, help="the installed cibuildwheel/ directory")
    ap.add_argument("--version", required=True, help="CPython version, e.g. 3.14.9")
    ap.add_argument("--abi", required=True, choices=sorted(TRIPLETS))
    ap.add_argument("--url", required=True)
    ap.add_argument("--sha256", required=True)
    args = ap.parse_args()

    if not re.fullmatch(r"[0-9a-f]{64}", args.sha256):
        sys.exit("--sha256 must be 64 hex digits")
    major, minor = args.version.split(".")[:2]
    identifier = f"cp{major}{minor}-android_{args.abi}"
    pkg = args.package_dir

    # 1. The architecture enum and the set of Android archs.
    arch = pkg / "architecture.py"
    replace_once(arch, "    arm64_v8a = auto()\n", "    arm64_v8a = auto()\n    armeabi_v7a = auto()\n")
    replace_once(
        arch,
        '"android": {cls.x86_64, cls.arm64_v8a},',
        '"android": {cls.x86_64, cls.arm64_v8a, cls.armeabi_v7a, cls.x86},',
    )

    # 2. ABI -> CPython host triplet.
    android = pkg / "platforms" / "android.py"
    replace_once(
        android,
        '    "x86_64": "x86_64-linux-android",\n}',
        '    "x86_64": "x86_64-linux-android",\n'
        + "".join(f'    "{a}": "{t}",\n' for a, t in TRIPLETS.items())
        + "}",
    )

    # 3. Where the target CPython comes from (our own release instead of python.org).
    toml = pkg / "resources" / "build-platforms.toml"
    text = toml.read_text(encoding="utf-8")
    if f'identifier = "{identifier}"' not in text:
        anchor = re.search(r"\[android\]\npython_configurations = \[\n", text)
        if not anchor:
            sys.exit(f"{toml}: no [android] python_configurations; update this script")
        entry = (
            f'  {{ identifier = "{identifier}", version = "{major}.{minor}", '
            f'url = "{args.url}", sha256 = "{args.sha256}" }},\n'
        )
        toml.write_text(text[: anchor.end()] + entry + text[anchor.end():], encoding="utf-8", newline="\n")

    print(f"cibuildwheel patched for {identifier} ({TRIPLETS[args.abi]})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
