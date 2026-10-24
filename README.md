
<div align="center">
  <img src="assets/banner.png" alt="SkyPython Banner" width="100%">

  <br>

  <h1>☁️ SkyPython</h1>

  <p>
    <strong>Python development, anywhere.</strong>
  </p>

  <p>
    An open-source Python IDE for Android.
    <br>
    Build, run, and manage Python projects right from your phone.
  </p>

  <p>
    <a href="https://github.com/sevorp-dev/SkyPython/issues">Report a bug</a>
    ·
    <a href="https://github.com/sevorp-dev/SkyPython/issues">Request a feature</a>
    ·
    <a href="https://t.me/SkyLoft_Studio">Telegram</a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android">
    <img src="https://img.shields.io/badge/Language-Python-3776AB?style=for-the-badge&logo=python&logoColor=white" alt="Python">
    <img src="https://img.shields.io/badge/Open%20Source-Yes-blue?style=for-the-badge" alt="Open Source">
  </p>
</div>

---

## ✨ About

**SkyPython** is an open-source project focused on bringing Python development to Android.

The goal is to create a convenient mobile development environment where you can write code, run scripts, manage projects, and work with Python directly from your device.

Our focus is on a clean interface, useful development tools, and an accessible experience for beginners and experienced Python developers.

## 🚀 Features

The following features are planned or under development:

- [x] 📝 Code editor with syntax highlighting
- [x] ▶️ Run Python scripts on Android (CPython 3.14, fully offline)
- [ ] 💻 Integrated terminal
- [ ] 📦 Python package management
- [x] 📁 Project and file management
- [x] 🌙 Modern dark theme
- [ ] 💡 Code completion and diagnostics

> v0.1 includes the editor, running scripts with an interactive console (`print` / `input`, Stop, Restart) and a file manager. Package management (pip) is planned for v0.2 and the terminal for v0.3.

## 📱 Screenshots

Screenshots of the application will be added as the interface develops.

## 🗺️ Roadmap

- [x] 🎨 Design and build the application interface
- [x] 📝 Implement the code editor
- [x] 🐍 Integrate the Python runtime
- [x] ▶️ Add script execution
- [ ] 💻 Implement the integrated terminal
- [ ] 📦 Add package management
- [ ] 💡 Improve code completion and diagnostics
- [ ] 📱 Prepare the first Android release

## 📥 Installation

Requires Android 8.0+ on a 64-bit device (arm64-v8a) or an x86_64 emulator.

APK releases will be published on the [Releases](https://github.com/sevorp-dev/SkyPython/releases) page. Until then, every CI run on [Actions](https://github.com/sevorp-dev/SkyPython/actions) attaches a debug APK (`SkyPython-debug-apk`).

### Build from source

1. Install [Android Studio](https://developer.android.com/studio) (it bundles JDK 21), with Android SDK 36, NDK `28.2.13676358` and CMake `3.22.1` (SDK Manager → SDK Tools).
2. Clone and open the project:
   ```sh
   git clone https://github.com/sevorp-dev/SkyPython.git
   ```
3. Build: **Run ▶** in Android Studio, or `./gradlew assembleDebug`.

The first build downloads the official CPython for Android from python.org (~46 MB, checked against pinned SHA-256 hashes) and packs it into the APK. Nothing Python-related is stored in git.

Tests: `./gradlew testDebugUnitTest` (JVM) and `./gradlew connectedDebugAndroidTest` (needs an x86_64 emulator or a device).

## 🛠️ Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **[Sora Editor](https://github.com/Rosemoe/sora-editor)** with TextMate grammars for highlighting
- **CPython 3.14**, the [official Android build](https://docs.python.org/3/using/android.html) from python.org. Scripts run as a separate process (like classic IDE and Termux), so Stop always works and a crashing script can't take the IDE down.
- Modules: `app` (UI) and `runtime` (Python packaging, installation and the process runner, plus a tiny NDK launcher).

## 🤝 Contributing

Contributions, bug reports, ideas, and feature suggestions are welcome!

You can help by:

- Reporting bugs through [GitHub Issues](https://github.com/sevorp-dev/SkyPython/issues)
- Suggesting new features
- Sharing feedback and ideas
- Contributing code

Please open an issue before making major changes.

## 👨‍💻 Author

Developed by **[@Sevorp](https://github.com/sevorp-dev)**

Created by **SkyLoft Studio 🌥**

- Telegram: [@SkyLoft_Studio](https://t.me/SkyLoft_Studio)
- GitHub: [sevorp-dev](https://github.com/sevorp-dev)

## 📄 License

SkyPython is licensed under the [Apache License 2.0](LICENSE).

Bundled third-party components: CPython (PSF License), Sora Editor (LGPL-2.1), MagicPython grammar and VS Code theme data (MIT). See [`app/src/main/assets/textmate/NOTICE.md`](app/src/main/assets/textmate/NOTICE.md).

---

<div align="center">
  <p>
    <strong>SkyPython</strong>
    <br>
    Python development, anywhere. ☁️
  </p>

  <sub>Made with ❤️ by SkyLoft Studio</sub>
</div>
