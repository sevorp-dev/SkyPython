"""Final touches on built Android wheels before they are verified and published (build-wheels.yml).

* Adds license texts of vendored native libraries: auditwheel copies libopenblas /
  libc++_shared into a wheel but not their licenses. They go into `<name>.dist-info/licenses/`.
* Removes absolute RUNPATH entries (build-machine paths such as
  /tmp/cibw-run-*/.../python/prefix/lib that meson leaves in extensions which link only
  libpython); relative ones like $ORIGIN/../../numpy.libs are kept. Needs `patchelf` on PATH.
* Rewrites RECORD.

    python finish_wheels.py WHEEL [WHEEL ...] [--license NAME=PATH[@SUBSTRING] ...]

With @SUBSTRING a license is added only if some file in the wheel has SUBSTRING in its name.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_wheels import elf_info  # noqa: E402

DATE = (2020, 1, 1, 0, 0, 0)


def record_hash(data: bytes) -> str:
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
    return f"sha256={digest}"


def strip_runpath(data: bytes) -> bytes | None:
    try:
        info = elf_info(data)
    except Exception:  # noqa: BLE001 - not an ELF file we understand
        return None
    entries = [p for r in info["runpath"] for p in r.split(":") if p]
    keep = [p for p in entries if not p.startswith("/")]
    if keep == entries:
        return None
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "lib.so")
        with open(path, "wb") as f:
            f.write(data)
        if keep:
            subprocess.run(["patchelf", "--set-rpath", ":".join(keep), path], check=True)
        else:
            subprocess.run(["patchelf", "--remove-rpath", path], check=True)
        with open(path, "rb") as f:
            return f.read()


def finish(wheel: str, licenses: list[tuple[str, str, str | None]]) -> list[str]:
    changes = []
    with zipfile.ZipFile(wheel) as zin:
        infos = zin.infolist()
        names = [i.filename for i in infos]
        dist_info = next(n.split("/")[0] for n in names if n.split("/")[0].endswith(".dist-info"))
        record_name = f"{dist_info}/RECORD"

        replaced: dict[str, bytes] = {}
        for n in names:
            if n.endswith(".so"):
                new = strip_runpath(zin.read(n))
                if new is not None:
                    replaced[n] = new
                    changes.append(f"runpath stripped: {n}")
        added = []
        for name, path, cond in licenses:
            if cond and not any(cond in os.path.basename(n) for n in names):
                continue
            target = f"{dist_info}/licenses/{name}"
            if target not in names:
                with open(path, "rb") as f:
                    added.append((target, f.read()))
                changes.append(f"license added: {target}")
        if not changes:
            return []

        files = {n: d for n, d in [*replaced.items(), *added]}
        record = []
        for line in zin.read(record_name).decode().splitlines():
            n = line.rsplit(",", 2)[0]
            if not line or n == record_name or n in files:
                continue
            record.append(line)
        for n, d in files.items():
            record.append(f"{n},{record_hash(d)},{len(d)}")
        record.append(f"{record_name},,")

        fd, tmp = tempfile.mkstemp(suffix=".whl", dir=os.path.dirname(os.path.abspath(wheel)))
        os.close(fd)
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
            for info in infos:
                if info.filename == record_name:
                    continue
                zout.writestr(info, replaced.get(info.filename) or zin.read(info.filename))
            for n, d in added:
                zout.writestr(zipfile.ZipInfo(n, date_time=DATE), d, compress_type=zipfile.ZIP_DEFLATED)
            zout.writestr(zipfile.ZipInfo(record_name, date_time=DATE), "\n".join(record) + "\n",
                          compress_type=zipfile.ZIP_DEFLATED)
    shutil.move(tmp, wheel)
    return changes


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("wheels", nargs="+")
    ap.add_argument("--license", action="append", default=[], metavar="NAME=PATH[@SUBSTRING]")
    args = ap.parse_args()
    licenses = []
    for spec in args.license:
        name, _, rest = spec.partition("=")
        path, _, cond = rest.partition("@")
        if not (name and path and os.path.isfile(path)):
            sys.exit(f"bad --license {spec!r} (file missing?)")
        licenses.append((name, path, cond or None))
    for w in args.wheels:
        changes = finish(w, licenses)
        print(f"{w}:")
        for c in changes or ["nothing to do"]:
            print(f"  {c}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
