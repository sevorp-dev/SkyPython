# Changelog

All notable changes to SkyPython. Downloads are on the [Releases](https://github.com/sevorp-dev/SkyPython/releases) page.

## SkyPython 0.0.5 — your projects, updates from the app, Pillow

Open projects anywhere on your phone, update SkyPython right from the app, edit photos and draw with Pillow — and a clearer, calmer interface. Still free: no ads, no premium tier.

### 📂 Projects from your phone's storage
- New **Open project** button in Projects — pick any folder on the phone: Documents, Download, an SD card…
- Built-in **folder browser** with breadcrumbs, hidden files toggle and "New folder"; tap **Use this folder**
- **Recent projects** — reopen a project in one tap, remove the ones you don't need
- Programs **run inside the project folder**: `open("data.csv")` finds the file next to your script. The interpreter, terminal and debugger start there too
- The project and its open tabs come back after a restart
- Android asks once for **"All files access"** (on Android 7–10, the storage permission) — SkyPython explains why first. Without it everything works as before with the projects kept inside the app
- If a project folder disappears or access is taken away, SkyPython falls back to its own projects instead of crashing

### 🔔 Updates right in the app
- When a new version is released, SkyPython shows **"SkyPython X is available"** — read **What's new** and tap **Install**
- It downloads the right file for **your** phone, checks its checksum and hands it to Android's own "Update this app?" dialog. Your projects and settings stay
- Checks at most twice a day; **Check for updates** in Settings and About; **Skip this version**; automatic checks can be turned off
- First time only: Android asks to allow SkyPython to install apps — SkyPython explains it and opens the right setting
- Updates now always **install over** the old version: every APK of a release has the same version number, so switching between the `universal` and your phone's own APK works too

*0.0.4 doesn't have the updater yet: install 0.0.5 over it by hand once (no need to uninstall) — from then on updates come to you.*

### 🖼️ Pillow — pictures and photos
- **Pillow 13** prebuilt for Android: Pip → Popular → **pillow** → Install
- JPEG, PNG, WebP and TIFF, colour profiles, and fonts for drawing text
- New sample **"Make a picture card with Pillow"** — the card opens right in the image viewer

```python
from PIL import Image, ImageDraw
im = Image.new("RGB", (400, 200), "orange")
ImageDraw.Draw(im).text((20, 80), "Hello from my phone", fill="black")
im.save("hello.png")
```

### 🎨 Clearer interface
- **Pip** is now cards: package avatars, version chips, "Installed ✓", "Prebuilt for Android", and sections — Prebuilt for Android, Popular, Data & science, Web & network, Text & terminal
- After installing, the package shows **Installed** right away — no need to switch tabs
- Settings, About, Report a problem and Samples in clear cards; dialogs, text fields, the symbol bar and terminal keys stand out from the background

### 🛠️ Fixes
- A program printing huge amounts of text no longer freezes the app on slow phones — Stop always responds
- More reliable automatic tests on Android 7–16

### ⚠️ Known limitations
- matplotlib and pandas are not prebuilt yet — they are next
- No GUI windows yet (Tkinter / Kivy)

## SkyPython 0.0.4 — NumPy, a true dark theme and pictures

NumPy arrives on your phone, the app gets a real IDE-style dark theme, and pictures your programs make now open right inside SkyPython. Still free: no ads, no premium tier.

### 🔢 NumPy on your phone
- **Pip installs NumPy 2.5** in one tap: open **Pip → Popular** or search for `numpy` and press **Install**
- No compiler needed: SkyPython builds NumPy for Android itself, for every phone type (64-bit, old 32-bit, emulators)
- Packages built by SkyPython carry an orange **"Prebuilt for Android"** badge in Pip
- Every download is checked against its checksum before it is installed
- The **"Install numpy"** button under a `ModuleNotFoundError` works too
- If NumPy isn't available for a device yet, Pip says so clearly instead of failing

```python
import numpy as np
a = np.arange(10)
print(a.sum(), a.mean())
print(np.linalg.inv([[1, 2], [3, 4]]))
```

### 🌑 IDE dark theme
- Near-black editor and terminal like desktop IDEs, neutral graphite panels, no more blue-grey
- The orange accent stays; syntax colours retuned for the darker background
- Orange text-selection handles and bracket match, darker app icon background
- Contrast checked against accessibility guidelines on every surface

### 🖼️ Pictures in the app
- When your program saves a picture, it appears as a **chip under the finished run** — tap to open it
- Built-in **image viewer**: pinch and double-tap zoom, pan, fit to screen, size and dimensions, **Share**, **Open with…**, **Delete**
- **Projects**: PNG, JPEG, GIF, WebP and BMP files open in the viewer; other non-text files offer Share / Open with instead of a broken editor
- Large images are scaled down on loading, so even weak phones don't run out of memory
- New sample **"Draw a picture"**: a Mandelbrot fractal drawn into a PNG with only the standard library

### 🛠️ Fixes
- The debugger could crash the app if it closed at the same moment a request finished
- Pip re-reads the package list if a package changed since the phone last checked
- More reliable automatic tests on slow devices

### ⚠️ Known limitations
- Of the packages with native code, only NumPy is prebuilt so far — Pillow, matplotlib and pandas are next
- No GUI windows yet (Tkinter / Kivy)

## SkyPython 0.0.3 — a real IDE in your pocket

The biggest update so far: a new look, a real terminal for your programs, a smart editor, a debugger, a Russian interface — and all of it free, with no ads and no premium tier.

### 🎨 New look
- Modern dark theme with a warm **orange accent**, translucent menus and sheets
- New **SkyPython logo** and app icon (adaptive and themed icons on Android 13+)
- **Inter** for the interface, **JetBrains Mono** for code and the terminal
- **Bottom navigation bar**: Editor · Terminal · Packages · Projects · Settings — can be turned off in Settings
- Orange **▶ Run** in the top bar, tabs with ✕ and `+`, status line "Python 3.14 · Ln 3, Col 26"
- Tool drawer with **Workspace / Tools / Settings** sections and links to the SkyLoft Studio Telegram channel, GitHub and the author

### ▶️ Running programs
- **▶ Run opens a real terminal**: colours (`rich`, `colorama`), `input()` with line editing, `\r` progress bars
- **^C** sends KeyboardInterrupt, **Stop** always ends the program
- **Long-press to select text** — Copy, **Copy all**, Select all, Share
- Menu: **Copy all, Share output, Save output** (`script_output.txt`), Save as…, Clear, scroll to top/bottom, text size (or pinch)
- **Tap a traceback line** `File "…", line N` to jump to that line in the editor
- When the program ends: "Exit code N" with **Run again** and **Back to editor**

### 💡 Smart editor
- **Code completion** while typing — modules, methods, your own names, installed packages
- **Live error checking**: syntax errors, undefined names, unused imports; tap an underline to read it, tap the error line to jump there
- **Go to definition** (long-press a name, Ctrl+B/F12) — also into the standard library, opened read-only
- **Usages** highlight, **Outline** of classes and functions (Ctrl+Shift+O), **parameter hints** inside calls
- **Find & replace** with match case, whole word and regex; **Go to line**
- **Line commands**: comment/uncomment, indent/outdent, duplicate, delete, move up/down — from the actions row or the selection toolbar
- **Auto-indent** after `:`, **auto-closing** brackets and quotes, Backspace removes a whole indent
- **Keyboard without autocorrect** — no more "Print" instead of `print` (switchable)
- **Pinch to zoom**, **recent files**, **custom symbol bar keys**, hardware-keyboard shortcuts (Ctrl+S, Ctrl+F, Ctrl+H, Ctrl+G, Ctrl+/, Ctrl+R / F5)

### 🐞 Debugger
- Tap a line number to set a **breakpoint**, long-press it for a **condition**
- Long-press ▶ → **Debug**: step over / into / out, continue, pause, stop
- **Variables** (expandable), **Watches**, **Call stack**, console with input

### ⌨️ Interpreter & Terminal
- Both now run in a **real terminal**: history, arrows, colours
- **Ctrl+C interrupts** without closing the interpreter; Ctrl+D exits
- Key row: Esc, Tab, Ctrl, Alt, arrows, `-` `/` `|`
- `pip` in the terminal sets itself up on first use, offline

### 📦 Pip
- **Search PyPI** with version, description and whether the package can be installed on the phone
- **Popular packages** in one tap: requests, rich, beautifulsoup4, httpx, tqdm, sympy, faker…
- Install from **requirements.txt**, **updates** for installed packages, package details

### 🔗 Share
- Share **any file** to Telegram, WhatsApp, mail, cloud drives…
- Share a **whole project folder as a .zip**

### 🚨 Report a problem
- If SkyPython crashes, the next start offers to **send a report to the developers**
- Errors the app survives show a **Report** button
- **Report a problem** in the drawer and in About
- The report contains the error and device details (model, Android version, memory, storage, screen, network, mobile operator…) — **no personal data**: no phone number, IMEI, contacts or location
- You see the whole report first; send it as a **GitHub issue** or through any app. Nothing is ever sent automatically

### 🌍 Language
- **Russian interface** — follows the phone's language, or choose in Settings (System / English / Русский)

### 🛠️ Under the hood
- Faster, more reliable process handling; output printed right before exit is never lost
- Checked automatically before release on Android 7, 9, 11, 14 and 16 and on a 32-bit device

### ⚠️ Known limitations
- Pip installs pure-Python packages only — NumPy, Pillow and other packages with native code are coming next
- No GUI windows yet (Tkinter / Kivy)

### 📥 Which file do I download?
| Your phone | File |
|---|---|
| Most phones (2017 and newer) | `SkyPython-0.0.3-arm64-v8a.apk` |
| Old 32-bit phones | `SkyPython-0.0.3-armeabi-v7a.apk` |
| Not sure | `SkyPython-0.0.3-universal.apk` — works everywhere |
| Emulators, Chromebooks | `SkyPython-0.0.3-x86_64.apk` (or `-x86.apk`) |

If Android says *"App not installed"*, take the `universal` one. Installs over 0.0.2 — your files and settings are kept.

---

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
