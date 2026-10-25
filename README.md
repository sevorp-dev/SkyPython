# CPython builds for SkyPython

This branch only stores CPython for the 32-bit Android CPU types that python.org does not
publish (`armeabi-v7a` → `arm-linux-androideabi`, `x86` → `i686-linux-android`).

They are built from the official CPython sources with CPython's own `Android/android.py` by the
[`build-python.yml`](https://github.com/sevorp-dev/SkyPython/blob/main/.github/workflows/build-python.yml)
workflow. The app build downloads them from here and checks them against the SHA-256 hashes
pinned in [`runtime/python.properties`](https://github.com/sevorp-dev/SkyPython/blob/main/runtime/python.properties).

Nothing here is meant to be used directly — get SkyPython from the
[Releases](https://github.com/sevorp-dev/SkyPython/releases) page.
