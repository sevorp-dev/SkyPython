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

/**
 * Fixed per-ABI versionCode offsets: versionCode * 10 + offset. 64-bit beats 32-bit of the
 * same family; a multi-ABI (universal) APK gets 0, so any per-ABI APK can update it.
 * Fixed (not derived from the skypython.abis order) so codes never shift.
 */
val abiVersionOffset = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)
val skyVersionOffset: Int = skyAbis.singleOrNull()
    ?.let { abiVersionOffset[it] ?: error("No versionCode offset for ABI $it") } ?: 0

android {
    namespace = "dev.skyloft.skypython"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.skyloft.skypython"
        // Android 7.0: the python.org CPython build targets API 24.
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += skyAbis
        }
    }

    androidResources {
        // Drop the stdlib zips (assets/python/stdlib-<abi>.zip, from :runtime) of ABIs not packed.
        val skipStdlibs = abiVersionOffset.keys.filter { it !in skyAbis }.map { "!stdlib-$it.zip" }
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
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(android.defaultConfig.versionCode!! * 10 + skyVersionOffset)
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":runtime"))
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
