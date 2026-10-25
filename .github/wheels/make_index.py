"""Maintains the `wheels` branch of SkyPython: Android wheels + index.json (used by build-wheels.yml).

The app reads https://raw.githubusercontent.com/<owner>/<repo>/wheels/index.json and downloads
the wheel files next to it. Layout of SITE_DIR (the branch root):

    index.json        {"schema": 1, "python": "cp314", "packages": {"numpy": [entry, ...]}}
    SHA256SUMS        sha256sum-compatible list of the wheels
    <wheel files>

    python make_index.py --site SITE_DIR [--requires numpy=chaquopy-openblas ...] [new.whl ...]

* a wheel whose file name is already in SITE_DIR is skipped (published files never change;
  --replace overrides), so rebuild with a new version or a wheel build tag to update one;
* each new wheel is copied into SITE_DIR; an older wheel of the same project and ABI (another
  version or build) is removed, so the branch holds one wheel per project x ABI;
* index.json and SHA256SUMS are rewritten from all wheels in SITE_DIR, reading name, version
  and summary from each wheel's METADATA.

An entry: {"version", "abi" (Android ABI name: arm64-v8a, x86_64, armeabi-v7a, x86), "file",
"sha256", "size", "requires" (other projects of THIS index needed at runtime; pure-Python
dependencies from PyPI are not listed), "summary"}.

Standard library only.
"""

from __future__ import annotations

import argparse
import email.parser
import hashlib
import json
import os
import re
import shutil
import sys
import zipfile

PYTHON_TAG = "cp314"
ABI_NAMES = {"arm64_v8a": "arm64-v8a", "x86_64": "x86_64", "armeabi_v7a": "armeabi-v7a", "x86": "x86"}
ABI_ORDER = list(ABI_NAMES.values())
# name-version(-build)?-python-abi-platform.whl
WHEEL_RE = re.compile(
    r"^(?P<name>[^-]+)-(?P<version>[^-]+)(?:-\d[^-]*)?-(?P<py>[^-]+)-(?P<abi>[^-]+)"
    r"-android_(?P<api>\d+)_(?P<arch>[a-z0-9_]+)\.whl$"
)


def normalize(name: str) -> str:  # PEP 503
    return re.sub(r"[-_.]+", "-", name).lower()


def parse(filename: str) -> re.Match[str]:
    m = WHEEL_RE.match(filename)
    if not m or m.group("arch") not in ABI_NAMES:
        sys.exit(f"{filename}: not an Android wheel name this index understands")
    return m


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def metadata(path: str) -> dict[str, str]:
    with zipfile.ZipFile(path) as z:
        name = next(n for n in z.namelist() if re.fullmatch(r"[^/]+\.dist-info/METADATA", n))
        msg = email.parser.Parser().parsestr(z.read(name).decode("utf-8"), headersonly=True)
    return {"name": msg["Name"], "version": msg["Version"], "summary": (msg["Summary"] or "").strip()}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--site", required=True)
    ap.add_argument("--requires", action="append", default=[], metavar="PROJECT=DEP[,DEP]",
                    help="runtime dependencies that are themselves in this index")
    ap.add_argument("--replace", action="store_true",
                    help="overwrite wheels whose file name is already published")
    ap.add_argument("wheels", nargs="*")
    args = ap.parse_args()
    requires = {}
    for spec in args.requires:
        proj, _, deps = spec.partition("=")
        requires[normalize(proj)] = [normalize(d) for d in deps.split(",") if d]

    os.makedirs(args.site, exist_ok=True)
    for w in args.wheels:
        fn = os.path.basename(w)
        m = parse(fn)
        if os.path.exists(os.path.join(args.site, fn)) and not args.replace:
            # Builds are not bit-for-bit reproducible, and raw.githubusercontent.com caches
            # every file for minutes: a rebuilt file under a published name could briefly be
            # served with the old index.json (hash mismatch). Published files never change.
            print(f"keeping the published {fn}")
            continue
        key = (normalize(m.group("name")), m.group("arch"))
        for old in os.listdir(args.site):
            if old.endswith(".whl") and old != fn:
                o = parse(old)
                if (normalize(o.group("name")), o.group("arch")) == key:
                    print(f"replacing {old}")
                    os.remove(os.path.join(args.site, old))
        shutil.copyfile(w, os.path.join(args.site, fn))

    packages: dict[str, list[dict]] = {}
    sums = []
    for fn in sorted(f for f in os.listdir(args.site) if f.endswith(".whl")):
        path = os.path.join(args.site, fn)
        m = parse(fn)
        if m.group("py") != PYTHON_TAG:
            sys.exit(f"{fn}: python tag {m.group('py')}, this index is for {PYTHON_TAG}")
        meta = metadata(path)
        proj = normalize(meta["name"])
        digest = sha256(path)
        sums.append(f"{digest}  {fn}")
        packages.setdefault(proj, []).append({
            "version": meta["version"],
            "abi": ABI_NAMES[m.group("arch")],
            "file": fn,
            "sha256": digest,
            "size": os.path.getsize(path),
            "requires": requires.get(proj, []),
            "summary": meta["summary"],
        })
    for proj, entries in packages.items():
        entries.sort(key=lambda e: ABI_ORDER.index(e["abi"]))
        for e in entries:
            missing = [d for d in e["requires"] if d not in packages]
            if missing:
                sys.exit(f"{e['file']}: requires {missing}, which are not in the index")

    index = {"schema": 1, "python": PYTHON_TAG, "packages": dict(sorted(packages.items()))}
    with open(os.path.join(args.site, "index.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(index, f, indent=2)
        f.write("\n")
    with open(os.path.join(args.site, "SHA256SUMS"), "w", encoding="utf-8", newline="\n") as f:
        f.write("".join(s + "\n" for s in sums))
    print(json.dumps(index, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
