# Changelog

All notable changes to SkyPython. Downloads are on the [Releases](https://github.com/sevorp-dev/SkyPython/releases) page.

## SkyPython 0.0.2 — new look, new tools, many more phones

A big update: a brand-new interface in the colours of SkyPython, an interactive interpreter, a terminal and a package manager — and SkyPython now runs on old and low-end phones too.

### 🎨 New interface
- Night-sky theme in the SkyPython colours: deep navy, electric blue and violet
- **☰ Tool drawer** with everything in one place; its header shows the current file, line and column
- File name and folder in the title bar
- Compact tabs; unsaved files are marked with `*`
- Big round **▶ Run** button; while the keyboard is open it moves into the symbol bar so it never covers your code
- Longer symbol bar: `Tab : ; ' " # ( ) [ ] { } = . , _ + - * / < > \ |`
- Same look on every screen, new app icon

### 🧰 New tools
- 🐍 **Interpreter** — interactive Python (`>>>`) for quick experiments
- ⌨️ **Terminal** — a shell with `python`, `python3`, `pip` and `pip3` commands
- 📦 **Pip** — install, list and remove pure-Python packages from PyPI (`requests`, `rich`, `six`, `colorama`, …); installed packages work in your scripts right away
- 🎓 **Samples** — 8 ready-to-run examples: input, loops & lists, functions, classes, files, JSON, random & math, a guess-the-number game
- ⚙️ **Settings** — font size (12–24), word wrap, line numbers, keep screen on
- 🔗 **Share** the current script with any app
- ℹ️ **About** — version, licenses, links

### 📱 Runs on more phones
- **Android 7.0+** (was 8.0+)
- **32-bit phones** (armeabi-v7a) and x86 devices are now supported
- **Much smaller download**: ~14 MB for your phone's CPU type (was 45 MB)
- Checked automatically on Android 7, 9, 11, 14 and 16, and on a 32-bit device, before every release
- If storage is almost full, the app says how much space Python needs instead of failing

### 🐞 Fixes
- **Stop** now really stops programs that ignore the normal stop signal
- Interpreter results no longer appear after the next `>>>` prompt
- Opening the same file twice no longer creates two tabs that could overwrite each other
- Huge outputs (millions of lines) no longer freeze the console
- Unsaved changes are kept when the app is closed quickly
- Renaming a file or folder keeps its open tabs
- Light status-bar icons on the dark theme

### ⚠️ Known limitations
- The terminal is line-based: no colours, `Ctrl+C` or full-screen programs yet
- Stop in the interpreter closes it (instead of interrupting the current command)
- Pip installs pure-Python packages only (no packages with C code yet)

### 📥 Which file do I download?
| Your phone | File |
|---|---|
| Most phones (2017 and newer) | `SkyPython-0.0.2-arm64-v8a.apk` |
| Old 32-bit phones | `SkyPython-0.0.2-armeabi-v7a.apk` |
| Not sure | `SkyPython-0.0.2-universal.apk` — works everywhere |
| Emulators, Chromebooks | `SkyPython-0.0.2-x86_64.apk` (or `-x86.apk`) |

If Android says *"App not installed"*, take the `universal` one. Installs over 0.0.1, your files are kept.

---

## SkyPython 0.0.1 — the first preview

The very first public build of SkyPython: a Python 3 IDE that runs **real CPython 3.14 right on your phone, fully offline**.

### ✨ What's inside
- 📝 **Code editor**
  - Python syntax highlighting, line numbers
  - Tabs for several open files, undo / redo
  - Symbol bar above the keyboard: `Tab ( ) [ ] { } : " ' # = .`
  - Files are saved automatically when you run them or leave the app
- ▶️ **Run**
  - One tap runs the current file with **CPython 3.14.9** (the official build for Android)
  - Every run is a separate process, so a crashing script can't take the app down
- 💻 **Console**
  - `print()` and `input()` work as on a computer
  - Errors are shown in red, with the exit code at the end
  - **Stop** and **Restart** buttons
- 📁 **Files**
  - Projects folder: create, rename and delete files and folders
  - Open and save files from other apps
- 🐍 Ready to use out of the box: `json`, `sqlite3`, `ssl`, `hashlib`, `ctypes`, `lzma` and the rest of the standard library
- 🌙 Dark theme

### 📱 Requirements
- Android **8.0+**
- 64-bit phone (arm64-v8a) or an x86_64 emulator
- About 60 MB of free storage

### ⚠️ Known limitations
- No package manager (`pip`) yet
- No interactive interpreter or terminal yet
- The first Run takes a few seconds while Python is prepared

### 📥 Install
Download `SkyPython-0.0.1.apk` below and open it on your phone (allow *Install unknown apps* if asked).
