# Native packages (NumPy & co.) on SkyPython — spike

Status: **research spike, October 2026.** Nothing here is wired into the app yet. The draft
workflow `.github/workflows/build-wheels.yml` and its helpers in `.github/wheels/` have not
been run on GitHub Actions; they are syntax-checked, and the helpers were tested locally
(see "What was verified").

## TL;DR — recommendation

1. **Build our own Android wheels with cibuildwheel on GitHub Actions** (Linux runner,
   `--platform android`), for CPython 3.14 / API level 24, and **host them as a PEP 503
   index**: wheel files as assets of a GitHub release `wheels`, index HTML on a `wheels`
   branch served by **GitHub Pages**.
2. **In the app, add two environment variables** to `PythonEnvironment.build()`:
   `PIP_EXTRA_INDEX_URL=https://sevorp-dev.github.io/SkyPython/simple/` and
   `PIP_PREFER_BINARY=1`. pip 26.2.2 (the one bundled by CPython 3.14.9's `ensurepip`)
   already understands Android wheel tags, so nothing else in the pip path has to change.
3. **Start with NumPy 2.5.4 for arm64-v8a + x86_64** (cibuildwheel supports these out of the
   box, OpenBLAS available), then add 32-bit (armeabi-v7a, x86) via a small cibuildwheel patch
   that points it at our own 32-bit CPython builds, then Pillow, then
   pandas / kiwisolver / contourpy / matplotlib.
4. Don't adopt BeeWare's mobile-forge (it's semi-retired, iOS-focused, no 3.14) or
   Chaquopy's old `build-wheel.py`. Chaquopy's own docs now say cibuildwheel is the
   recommended way for Python ≥ 3.13. Reuse their *knowledge* (patches, OpenBLAS build) and
   their **OpenBLAS wheel** as a build input.

Why not just wait for upstream? PyPI has Android wheels only for a handful of packages, and
**none of numpy, pandas, pillow, matplotlib, scipy, cryptography or pydantic-core**. NumPy's
maintainers merged Android build support (numpy/numpy#30412, in 2.6.0.dev) but declined to
publish Android wheels for now (Android is "tier 3", numpy/numpy#33119, closed 2026-10-22).
They also build only 64-bit, so SkyPython's 32-bit ABIs would always need our own builds.

## 1. Android wheels on PyPI today

**Tag format.** PEP 738 defines `android_<api>_<abi>`, where `<abi>` is one of
`arm64_v8a`, `x86_64`, `armeabi_v7a`, `x86`
(https://peps.python.org/pep-0738/, https://packaging.python.org/en/latest/specifications/platform-compatibility-tags/).
A wheel tagged with API level N installs on devices with API ≥ N.

**pip on the device recognises them.** CPython 3.14.9 bundles `_PIP_VERSION = "26.2.2"`
(`Lib/ensurepip/__init__.py` at tag v3.14.9). pip 26.2.2 vendors `packaging==26.2`, whose
`tags.android_platforms()` yields `android_{api}_{abi}` for every level from the device's
`platform.android_ver().api_level` down to 16, with the ABI taken from
`sysconfig.get_platform()` (checked in the pip wheel: `pip/_vendor/packaging/tags.py`).
So a device on API 34 accepts `android_24_arm64_v8a`. No `--platform` tricks needed.

**What's on PyPI** (PyPI JSON API, latest release of each, checked 2026-10-25):

| Package | Latest | Android wheels in latest release |
|---|---|---|
| numpy | 2.5.4 | none |
| pandas | 3.1.0 | none |
| pillow | 13.0.0 | none (has iOS wheels) |
| matplotlib | 3.11.2 | none |
| scipy | 1.18.1 | none |
| cryptography | 50.0.2 | none |
| pydantic-core | 2.50.1 | none |
| lxml, pyyaml, orjson, regex, psutil, kiwisolver, contourpy, bcrypt, jiter, rpds-py, frozenlist, yarl, propcache, greenlet, websockets, brotli, zstandard, ujson, wrapt, tornado | — | none |
| markupsafe | 3.0.4 | cp313/cp314/cp315 × arm64_v8a, x86_64 |
| aiohttp | 3.14.5 | cp313/314/315 × arm64_v8a, x86_64 |
| charset-normalizer | 3.5.2 | cp313/314 × arm64_v8a, x86_64 |
| coverage | 7.17.0 | cp313/314/315 × arm64_v8a, x86_64 |
| pyzmq | 27.2.0 | cp313/314/315 × arm64_v8a, x86_64 |
| xxhash | 4.0.2 | cp313/314/315 × arm64_v8a, x86_64 |
| multidict | 7.2.1 | x86_64 only |

No package publishes **32-bit** Android wheels on PyPI (cibuildwheel can't build them).
Other indexes: the Scientific Python nightly index has no Android numpy; Chaquopy's
`https://chaquo.com/pypi-13.1/` has numpy only up to 1.26.2 / cp313, and
`https://chaquo.com/pypi-upstream/` has numpy 2.3.2, pandas 2.3.2, scipy, scikit-learn
**for cp313 only**, plus `chaquopy-openblas 0.3.33` (`py3-none-android_24_{arm64_v8a,x86_64}`),
which numpy's own CI uses. **Nothing has cp314 numpy, so we must build it.**

**Inspected wheels** (downloaded from PyPI and checked against their PyPI SHA-256 digests;
read as zip/ELF data, never executed):

- `markupsafe-3.0.4-cp314-cp314-android_24_arm64_v8a.whl`: `WHEEL` says
  `Tag: cp314-cp314-android_24_arm64_v8a`, `Root-Is-Purelib: false`; the extension is
  `markupsafe/_speedups.cpython-314-aarch64-linux-android.so`, ELF64 AArch64, every LOAD
  segment aligned to 16384, NEEDED = `libm.so libpython3.14.so libdl.so libc.so`.
- `pyzmq-27.2.0-cp314-cp314-android_24_arm64_v8a.whl` (C++ via scikit-build-core):
  auditwheel vendored the NDK's C++ runtime as `pyzmq.libs/libc++_shared-d523468d.so`; the
  extension has `DT_RUNPATH=$ORIGIN/../../../pyzmq.libs` and NEEDED
  `libc++_shared-d523468d.so`. LOAD alignment 16384/65536.

So: extension suffix `.cpython-314-<triplet>.so`, they link against `libpython3.14.so` (our
launcher already loads it from `nativeLibraryDir`), the C++ runtime is **inside the wheel**
under a hashed name, and 16 KB alignment is already the default.

## 2. Building: cibuildwheel's Android support

- Added in **cibuildwheel 3.1.0** (2025-07-23); current **4.3.4** (2026-10-22). **4.0.0**
  (2026-06-07) added what NumPy needs: auditwheel repair, pkg-config and Fortran (`flang-new`)
  configuration, the `xbuild-files` option (https://cibuildwheel.pypa.io/en/stable/changelog/).
- Build host: **Linux x86_64 or macOS** with `curl` and Java; it uses the Android SDK at
  `ANDROID_HOME` (preinstalled on GitHub's ubuntu runners) and installs the NDK with
  `sdkmanager`. Default `ANDROID_API_LEVEL=24` (= our minSdk). Frontend: `build` or `uv`
  (not `pip`). (https://cibuildwheel.pypa.io/en/stable/platforms/#android)
- Architectures: **only `arm64_v8a` and `x86_64`** (`cibuildwheel/architecture.py`,
  `platforms/android.py: ANDROID_TRIPLET`). Identifiers: `cp314-android_arm64_v8a`,
  `cp314-android_x86_64`. Target CPython for cp314 is python.org's 3.14.8 tarball — the cp314
  ABI is stable across patch releases, so wheels run on our 3.14.9.
- How it works: it downloads the target CPython tarball, creates a cross venv, gets the
  compiler environment from the tarball's `android.py env` (CPython's `android-env.sh`,
  which adds `-Wl,-z,max-page-size=16384`), builds with `python -m build --no-isolation`,
  repairs with `auditwheel repair --ldpaths <NDK libc++ / libomp dirs>`, and runs tests in
  CPython's Android testbed on a Gradle-managed emulator (only for the runner's native arch,
  so x86_64 can be tested on GitHub's x86_64 runners; arm64 can't).
- meson-python ≥ 0.20 (current 0.22.1) generates the Meson cross file itself under
  cibuildwheel (mesonbuild/meson-python#824), for every Android `platform.machine`.

**NumPy specifics** (from numpy main's `pyproject.toml` / `tools/wheels/cibw_before_build.sh`
after numpy/numpy#30412):
- OpenBLAS from `chaquopy-openblas==0.3.33` on `https://chaquo.com/pypi-upstream/`,
  `-Duse-ilp64=false` ("ILP64 causes many failures on Android"), `PKG_CONFIG_PATH` and
  `AUDITWHEEL_LD_LIBRARY_PATH` pointing into it.
- 32-bit builds use **no BLAS** (NumPy's own policy for all 32-bit wheels).
- The only source change: when cross-compiling for Android, the long-double format is
  hardcoded (`IEEE_QUAD_LE` on 64-bit, including x86_64; `IEEE_DOUBLE_LE` on 32-bit) instead
  of running a probe program. NumPy 2.5.4 (latest release) doesn't have that change, so the
  draft workflow `sed`s the same constant into `numpy/_core/meson.build` (checked against
  the real 2.5.4 sdist; line 505).
- NumPy needs **no Fortran**. (SciPy does: cibuildwheel ≥ 4.0 handles `flang-new`; that is a
  later, bigger project.)

**Why build the released 2.5.4 and not numpy main:** `2.6.0.dev0` is a pre-release, so
`pip install numpy` on the device would skip it and pick PyPI's 2.5.4 **sdist**, then fail
trying to compile. Once NumPy 2.6.0 is released (expected December 2026 per #33119), drop the
`sed` step and build 2.6.0.

**32-bit (armeabi-v7a, x86).** cibuildwheel has no 32-bit Android because python.org
doesn't ship 32-bit Android CPython. We already build exactly those tarballs with CPython's
own `Android/android.py package` (`build-python.yml`, release `python-android-3.14.9`),
in the same layout, with hashes pinned in `runtime/python.properties`. So cibuildwheel only
needs to know they exist. `.github/wheels/patch_cibw_32bit.py` makes three checked edits to
the pinned cibuildwheel 4.3.4: adds `armeabi_v7a` to `Architecture` and both 32-bit archs to
the Android arch set, adds the triplets to `ANDROID_TRIPLET`, and adds a
`cp314-android_<abi>` entry (our URL + SHA-256) to `resources/build-platforms.toml`. It
stops with an error if cibuildwheel's code doesn't match, rather than half-patching.
auditwheel has no 32-bit Android architecture (its enum has only `arm64_v8a`), so 32-bit
builds skip repair and link libc++ statically (`-Dcpp_link_args=-static-libstdc++`).
NumPy keeps C++ inside each extension, so that's acceptable. The 32-bit jobs are
`continue-on-error`.

Fallback if the patch route fails: crossenv + a hand-written Meson cross file using
`android.py env HOST` from our tarball. That's more code to own; try the patch first.

## 3. Distribution: PEP 503 index on GitHub

- **raw.githubusercontent.com does not work:** it serves `text/plain`, and pip 26 only
  accepts index/find-links pages whose Content-Type is `text/html` or
  `application/vnd.pypi.simple.v1+{html,json}` (`pip/_internal/index/collector.py:
  _ensure_api_header`). It also wouldn't map `simple/numpy/` to `index.html`.
- **GitHub Pages works** (text/html, directory → `index.html`). Limits: published site
  ≤ 1 GB, soft bandwidth limit 100 GB/month
  (https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits).
- **Wheel files go into release assets**, not git: each asset must be < 2 GiB, with no total
  size cap
  (https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases).
  Git blobs over 100 MiB are rejected, and binaries bloat clones. The index links to
  `https://github.com/sevorp-dev/SkyPython/releases/download/wheels/<file>#sha256=<hash>`.
  pip follows the redirect and checks the hash.
- `.github/wheels/make_index.py` keeps `SHA256SUMS` on the `wheels` branch and regenerates
  `simple/index.html` + `simple/<project>/index.html` (PEP 503 normalized names, PEP 629
  `pypi:repository-version` meta). **Tested locally:** I generated an index for the PyPI
  Android wheels above, served it with `http.server`, and ran `pip 26.2.2 download
  --only-binary=:all: --platform android_34_arm64_v8a --platform android_24_arm64_v8a
  --python-version 3.14 --implementation cp --abi cp314 --index-url http://127.0.0.1:8765/simple/
  markupsafe pyzmq`. pip resolved and downloaded both `android_24_arm64_v8a` wheels with
  hashes verified.
- App side: `PIP_EXTRA_INDEX_URL` (env var, so it also works for `pip` typed in the
  terminal) + **`PIP_PREFER_BINARY=1`**. Without it, if PyPI has a newer numpy *sdist* than
  our newest wheel, pip picks the sdist and fails to compile on the device. Prefer-binary
  makes pip take the newest version that has a compatible wheel. It doesn't hurt pure-Python
  packages (they have `py3-none-any` wheels or build fine from sdist).
- Supply chain: `--extra-index-url` merges both indexes. We host only real project names
  built from the PyPI sdist (hash-checked in the workflow), so we don't add a new
  confusion risk. If upstream starts publishing Android wheels, pip will simply take
  theirs when they're newer.

**Licensing.** NumPy: BSD-3-Clause (bundled parts listed in its `LICENSES_bundled.txt`, all
permissive). OpenBLAS: BSD-3-Clause. libc++ (vendored by auditwheel):
Apache-2.0 WITH LLVM-exception. Chaquopy (repo incl. `server/pypi` recipes, and the
`chaquopy-openblas` packaging): MIT. BeeWare mobile-forge: BSD-3-Clause. Pillow: MIT-CMU
(+ libjpeg-turbo BSD/IJG, zlib, libpng, FreeType FTL-or-GPLv2: choose FTL). pandas: BSD-3.
Matplotlib: its own PSF-style licence. No LGPL in this set. Watch for LGPL/GPL natives
(e.g. libgmp in gmpy2, GTK stack) before adding such packages. **To do:** auditwheel copies
the vendored `.so` but not its license text. Add the libc++/OpenBLAS license texts to the
release notes or inject them into `*.dist-info/licenses/` in the workflow.

## 4. Runtime gotchas on Android (and how SkyPython fares)

| Concern | Status for SkyPython |
|---|---|
| `dlopen` of `.so` from app data (`filesDir/home/.local/...`) | **Works.** SkyPython already does it: the stdlib `lib-dynload/*.so` are unpacked from `stdlib-<abi>.zip` into `filesDir/python` and imported from there. W^X: since targetSdk 29, apps can't `exec()` files in their data dir (https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission), but mapping libraries is allowed. Consequence: wheels that ship **executables** (e.g. `ninja`, `cmake`, bundled CLI binaries) won't run. Extension modules are fine. |
| `libpython3.14.so` NEEDED by extensions | Already loaded by our launcher (`libpylauncher.so` links it, `LD_LIBRARY_PATH=nativeLibraryDir`). |
| `libc++_shared.so` | Not a system library. auditwheel vendors it into each wheel under a hashed name with `DT_RUNPATH=$ORIGIN/...` (seen in pyzmq). RUNPATH is supported from API 24 (= our minSdk). **No need to ship it in the APK.** 32-bit: static libc++. |
| OpenBLAS / Fortran | NumPy: OpenBLAS (C kernels + LAPACK, from Chaquopy's build) on 64-bit; internal `lapack_lite` on 32-bit (slower linalg). No Fortran runtime needed. |
| 16 KB pages (Android 15+) | Play requires 16 KB support for 64-bit apps targeting API 35+, and from 2027-02-01 updates without it can't be released (https://developer.android.com/guide/practices/page-sizes). That applies to `.so` inside the APK. Ours from the index are loaded at runtime, but a 4 KB-aligned one would still fail or force compat mode on 16 KB devices. CPython's `android-env.sh` passes `-Wl,-z,max-page-size=16384` and the NDK r28 default is 16 KB. `verify_wheels.py` fails the build if any LOAD segment is aligned < 16384. |
| Process model | Our Python runs as a child process. No JNI/`System.loadLibrary` involved, and no linker-namespace issues beyond what lib-dynload already proves. |
| Device without a matching wheel | pip downloads the sdist and fails on "no C compiler". Needs a friendly message in the Pip screen (detect `meson`/`gcc`/`cc` not found in pip output). |

## 5. Alternatives considered

- **BeeWare mobile-forge** (BSD-3): README says it's semi-retired, not working on 3.14+,
  Android "should be usable" but unverified, and points to cibuildwheel. ✗
- **Chaquopy `server/pypi`** (MIT): the README says "the recommended way to build
  Android-compatible wheels for Python 3.13 or later is to use cibuildwheel". The old
  `build-wheel.py` is for ≤ 3.12. Its recipes/patches are still a useful *reference*
  (e.g. for packages that need source patches). ✗ as a tool, ✓ as a reference and for
  `chaquopy-openblas`.
- **Reuse Chaquopy's cp313 wheels**: wrong Python ABI (cp313 ≠ cp314). ✗
- **Ship numpy inside the APK**: +10–20 MB per ABI for every user, and it ties package
  updates to app releases. ✗ (could revisit as an "offline extras" download.)
- **On-device compiler** (clang in the app): huge, slow, and blocked by W^X for executables. ✗

## 6. Plan

### 0.0.3 — prove NumPy (64-bit)
1. Merge `build-wheels.yml` + `.github/wheels/*` to `main`.
2. Push branch `build-wheels` (or "Run workflow") → iterate until `numpy arm64_v8a` and
   `numpy x86_64` are green (expect a few CI rounds; first suspects: the `-Dblas=openblas`
   detection, the x86_64 emulator smoke test).
3. Once the `wheels` branch exists: **Settings → Pages → Deploy from branch `wheels` / root.**
   Check `https://sevorp-dev.github.io/SkyPython/simple/numpy/`.
4. App: in `runtime/.../PythonEnvironment.kt` `build()` add
   ```kotlin
   "PIP_EXTRA_INDEX_URL" to "https://sevorp-dev.github.io/SkyPython/simple/",
   "PIP_PREFER_BINARY" to "1",
   ```
   (+ update `PythonEnvironmentTest`). Consider a `BuildConfig` constant for the URL.
5. Instrumented test (CI emulator, x86_64, API 24 and 36; optionally a 16 KB system image):
   `pip install numpy` via `PipRepository`, then run
   `import numpy as np; assert (np.arange(10)**2).sum() == 285; np.linalg.inv(np.eye(3))`.
6. Pip screen: friendly error when pip tries to compile an sdist.

### 0.0.4 — breadth
1. 32-bit numpy (patched cibuildwheel; armeabi-v7a matters for cheap phones; x86 is
   emulator-only, so drop it if it's troublesome).
2. Pillow: zlib comes with the NDK sysroot (`libz.so` is a public NDK library);
   build libjpeg-turbo (+ libpng, freetype later) in `before-all` with the NDK CMake toolchain
   that cibuildwheel generates. Pillow already ships iOS wheels, so its build scripts are
   cross-compile-aware.
3. kiwisolver, contourpy (C++/pybind11, no external libs), then matplotlib (freetype,
   bundled qhull), pandas 3.x (needs numpy at build time; check that the cross venv gets
   matching numpy headers).
4. Generalize the job: matrix over `package × abi`, per-package config snippets.

### Effort estimate (CI iteration dominates; no local Linux)
| Item | Estimate |
|---|---|
| numpy 64-bit wheels green + index on Pages | 1–3 days |
| App env vars + tests + error message | 1 day |
| numpy 32-bit (patched cibuildwheel) | 1–4 days, uncertain |
| Pillow (with jpeg/png/freetype) | 2–4 days |
| kiwisolver + contourpy + matplotlib | 3–5 days |
| pandas | 1–3 days |
| Each extra rebuild for a new CPython minor (3.15) | ~0.5 day + re-running |

## 7. Risks / open questions

- **The workflow hasn't run yet.** Most likely failure points: the OpenBLAS pkg-config
  detection with NumPy 2.5.4's meson (numpy main was tested with it, 2.5.4 wasn't), and on
  32-bit, NumPy's ARMv7 SIMD baseline with `-march=armv7-a` (fallback:
  `-Dcpu-baseline=none`). Also the cibuildwheel patch is tied to 4.3.4, so bump it
  deliberately.
- **Maintenance load**: every package × ABI × CPython minor is ours to rebuild. Keep the
  catalogue small and popular.
- **Licence notices** for vendored libs (see §3).
- **Pages must be enabled by the maintainer**; the repository must stay public (free plan).
  The publish job pushes to `wheels` with `GITHUB_TOKEN`. That doesn't trigger other
  workflows (good: `android.yml` runs on every branch push), but confirm the Pages build is
  triggered.
- **Index URL baked into the app**: moving it later needs an app update. A stable custom
  domain or a redirect page avoids that.
- Upstream may start publishing Android wheels (NumPy aims for 2.6.0). That's good news:
  pip will just prefer the newer one, and 32-bit stays ours.

## What was verified in this spike
- PyPI JSON API scan of ~40 popular packages (table above).
- Two real cp314 Android wheels inspected (tags, ELF class/machine, alignment, NEEDED,
  RUNPATH); `verify_wheels.py` passes on them and fails as intended for a too-high API level.
- pip 26.2.2's Android tag code read in the actual wheel; resolver test against a local
  PEP 503 index built by `make_index.py`.
- `patch_cibw_32bit.py` applied twice (idempotent) to an unpacked copy of
  cibuildwheel 4.3.4. The result parses (`ast`, `tomllib`) and lists `cp314-android_x86` /
  `cp314-android_armeabi_v7a`.
- The workflow YAML parses; every `run:` step passes `bash -n`; the embedded config writer
  produced valid TOML for 64- and 32-bit; the NumPy 2.5.4 sdist download (hash-checked)
  and the long-double `sed` were run against the real sdist.
