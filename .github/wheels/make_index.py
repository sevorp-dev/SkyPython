"""Builds a PEP 503 "simple" index for our Android wheels (EXPERIMENTAL, used by build-wheels.yml).

The wheels themselves live as assets of a GitHub release (no size limit per repo, keeps git
small); the `wheels` branch holds only this index and SHA256SUMS, served by GitHub Pages
(pip needs Content-Type text/html, which raw.githubusercontent.com does not send).

    python make_index.py --site SITE_DIR --url-base RELEASE_DOWNLOAD_URL [new.whl ...]

* adds the SHA-256 of each new wheel to SITE_DIR/SHA256SUMS (replacing an older line for the
  same file name);
* rewrites SITE_DIR/simple/index.html and SITE_DIR/simple/<project>/index.html from SHA256SUMS.

Standard library only.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import os
import re
import sys
from collections import defaultdict


def normalize(name: str) -> str:  # PEP 503
    return re.sub(r"[-_.]+", "-", name).lower()


def read_sums(path: str) -> dict[str, str]:
    sums: dict[str, str] = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if len(parts) == 2:
                    sums[parts[1]] = parts[0]
    return sums


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def page(title: str, body: str) -> str:
    return (
        "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n"
        "<meta name=\"pypi:repository-version\" content=\"1.0\">\n"
        f"<title>{html.escape(title)}</title>\n</head>\n<body>\n{body}</body>\n</html>\n"
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--site", required=True)
    ap.add_argument("--url-base", required=True, help="where the wheel files are downloaded from")
    ap.add_argument("wheels", nargs="*")
    args = ap.parse_args()

    sums_path = os.path.join(args.site, "SHA256SUMS")
    sums = read_sums(sums_path)
    for w in args.wheels:
        sums[os.path.basename(w)] = sha256(w)

    projects: dict[str, list[str]] = defaultdict(list)
    for name in sums:
        if not name.endswith(".whl"):
            continue
        projects[normalize(name.split("-")[0])].append(name)

    os.makedirs(args.site, exist_ok=True)
    with open(sums_path, "w", encoding="utf-8", newline="\n") as f:
        for name in sorted(sums):
            f.write(f"{sums[name]}  {name}\n")

    simple = os.path.join(args.site, "simple")
    os.makedirs(simple, exist_ok=True)
    links = "".join(f"<a href=\"{p}/\">{p}</a><br>\n" for p in sorted(projects))
    with open(os.path.join(simple, "index.html"), "w", encoding="utf-8", newline="\n") as f:
        f.write(page("SkyPython Android wheels", links))
    base = args.url_base.rstrip("/")
    for proj, files in projects.items():
        d = os.path.join(simple, proj)
        os.makedirs(d, exist_ok=True)
        body = "".join(
            f"<a href=\"{html.escape(base)}/{html.escape(fn)}#sha256={sums[fn]}\">"
            f"{html.escape(fn)}</a><br>\n"
            for fn in sorted(files)
        )
        with open(os.path.join(d, "index.html"), "w", encoding="utf-8", newline="\n") as f:
            f.write(page(f"Links for {proj}", body))
    # Serve files as-is (no Jekyll processing on GitHub Pages).
    open(os.path.join(args.site, ".nojekyll"), "w").close()
    print(f"{len(projects)} project(s), {sum(map(len, projects.values()))} wheel(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
