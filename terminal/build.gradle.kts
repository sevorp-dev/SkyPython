// Terminal emulator (VT100/xterm screen model) and its Android view, vendored from the
// Apache-2.0 terminal-emulator and terminal-view libraries; see NOTICE in this folder.
// Processes run on SkyPython's own PTY code in :runtime (libskypty.so).
plugins {
    alias(libs.plugins.android.library)
}

android {
    // terminal-view's resources are referenced as com.termux.view.R.
    namespace = "com.termux.view"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // The emulator's own tests touch android.util.Base64 / Log stubs.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Third-party code kept close to upstream; its warnings are not ours to fix.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":runtime"))
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
}
