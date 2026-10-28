plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * ABIs packed into the APK, from gradle.properties (`skypython.abis`). The release workflow
 * builds one APK per ABI with `-Pskypython.abis=<abi>` plus one with the full list (universal).
 *
 * Not AGP `splits { abi }`: splits only split lib/, while each ABI's stdlib zip lives in
 * assets/ and would stay in every per-ABI APK (~7 MB of dead weight each).
 */
val skyAbis: List<String> = skypythonAbis() // validated helper in buildSrc

/** Every ABI an APK can be built for (the stdlib zips of the others are dropped from assets). */
val allAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

/** What the in-app updater downloads next time: this APK's single ABI, or "universal". */
val skyApkVariant: String = skyAbis.singleOrNull() ?: "universal"

/**
 * The release number: 1 for 0.0.1, 2 for 0.0.2, ... Raise it by one with every release.
 *
 * The APK's versionCode is [skyRelease] * 10, the SAME for every APK of a release (universal and
 * each per-ABI one). Why:
 *  - SkyPython ships only as APKs on GitHub Releases, where people pick a file by hand. Android
 *    refuses only a LOWER versionCode (INSTALL_FAILED_VERSION_DOWNGRADE); an equal one installs
 *    as an update. So within a release every switch works: per-ABI -> universal, universal ->
 *    per-ABI, arm64 -> armeabi-v7a on a phone that runs both.
 *  - Every newer release installs over every APK of an older one: the codes of 0.0.1-0.0.4 had a
 *    per-ABI offset (n * 10 + 0 universal / 1 armeabi-v7a / 2 arm64-v8a / 3 x86 / 4 x86_64), so
 *    the highest old code is 44 and 0.0.5 starts at 50. Codes must never go down: keep the * 10.
 *  - The last digit stays 0; a rebuilt APK of the same release could use 1-9 if ever needed.
 * Per-ABI codes only matter to stores that serve several APKs of one release (Play multi-APK).
 */
val skyRelease = 6

android {
    namespace = "dev.skyloft.skypython"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.skyloft.skypython"
        // Android 7.0: the python.org CPython build targets API 24.
        minSdk = 24
        targetSdk = 36
        versionCode = skyRelease * 10
        versionName = "0.0.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += skyAbis
        }
        // The in-app updater downloads SkyPython-<version>-<SKY_APK_VARIANT>.apk.
        buildConfigField("String", "SKY_APK_VARIANT", "\"$skyApkVariant\"")
    }


    androidResources {
        // Drop the stdlib zips (assets/python/stdlib-<abi>.zip, from :runtime) of ABIs not packed.
        val skipStdlibs = allAbis.filter { it !in skyAbis }.map { "!stdlib-$it.zip" }
        ignoreAssetsPattern = (listOf(
            // AGP's default pattern.
            "!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "<dir>_*", "!CVS", "!thumbs.db", "!picasa.ini", "!*~",
        ) + skipStdlibs).joinToString(":")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // jcodings charset-conversion tables (~1.1 MB): only org.jcodings.transcode reads
            // them, and nothing we ship (joni/tm4e/Sora) uses transcoding.
            excludes += "tables/Transcoder_*.bin"
            // Java sources bundled in the Eclipse null-annotations jar.
            excludes += "src/org/eclipse/jdt/annotation/**"
        }
    }

    // Shared, public debug key: lets every CI/debug build install over the previous one.
    // Not a secret and not for Play Store releases.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // TODO: a real release key comes later. Until then releases are signed with the
            // committed debug key, so they install over 0.0.1 and over CI debug builds.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // -Pskypython.testRelease=true runs connectedAndroidTest against the minified release.
    if (providers.gradleProperty("skypython.testRelease").orNull.toBoolean()) {
        testBuildType = "release"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Sora's TextMate highlighter (tm4e) uses java.time, which Android only has from API 26.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // The language is chosen in the app (Settings), so every language must ship with it.
    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":runtime"))
    implementation(project(":terminal"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sora.editor)
    implementation(libs.sora.textmate)
    // Already in the APK through tm4e; declared because the language client uses it directly.
    implementation(libs.gson)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
