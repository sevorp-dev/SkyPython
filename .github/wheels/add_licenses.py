"""Adds license texts of vendored native libraries to wheels (used by build-wheels.yml).

auditwheel copies libraries such as libopenblas / libc++_shared into a wheel but not their
license texts. This puts them into `<name>.dist-info/licenses/` and rewrites RECORD.

    python add_licenses.py WHEEL [WHEEL ...] --license NAME=PATH [--license NAME=PATH ...]
        [--if-contains SUBSTRING]   # per license: NAME=PATH@SUBSTRING adds it only if some
                                    # file in the wheel contains SUBSTRING in its name

Standard library only.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
import shutil
import sys
import tempfile
import zipfile


def record_hash(data: bytes) -> str:
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
    return f"sha256={digest}"


def add(wheel: str, licenses: list[tuple[str, str, str | None]]) -> list[str]:
    with zipfile.ZipFile(wheel) as zin:
        infos = zin.infolist()
        names = [i.filename for i in infos]
        dist_info = next(n.split("/")[0] for n in names if n.split("/")[0].endswith(".dist-info"))
        record_name = f"{dist_info}/RECORD"
        wanted = []
        for name, path, cond in licenses:
            if cond and not any(cond in os.path.basename(n) for n in names):
                continue
            wanted.append((f"{dist_info}/licenses/{name}", open(path, "rb").read()))
        wanted = [(n, d) for n, d in wanted if n not in names]
        if not wanted:
            return []
        record = zin.read(record_name).decode().splitlines()
        record = [line for line in record if line and not line.startswith(record_name + ",")]
        for n, d in wanted:
            record.append(f"{n},{record_hash(d)},{len(d)}")
        record.append(f"{record_name},,")

        fd, tmp = tempfile.mkstemp(suffix=".whl", dir=os.path.dirname(os.path.abspath(wheel)))
        os.close(fd)
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
            for info in infos:
                if info.filename == record_name:
                    continue
                zout.writestr(info, zin.read(info.filename))
            for n, d in wanted:
                zout.writestr(zipfile.ZipInfo(n, date_time=(2020, 1, 1, 0, 0, 0)), d,
                              compress_type=zipfile.ZIP_DEFLATED)
            zout.writestr(zipfile.ZipInfo(record_name, date_time=(2020, 1, 1, 0, 0, 0)),
                          "\n".join(record) + "\n", compress_type=zipfile.ZIP_DEFLATED)
    shutil.move(tmp, wheel)
    return [n for n, _ in wanted]


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
        added = add(w, licenses)
        print(f"{w}: added {added or 'nothing'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
