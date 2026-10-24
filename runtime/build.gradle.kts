import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

// ---------------------------------------------------------------------------
// CPython for Android, fetched at build time: python.org's official release for the 64-bit
// ABIs, our own build (.github/workflows/build-python.yml) for the 32-bit ones.
// ---------------------------------------------------------------------------

val pythonProps = Properties().apply { file("python.properties").inputStream().use { load(it) } }
val pythonVersion: String = pythonProps.getProperty("version")
val pythonShortVersion: String = pythonVersion.split(".").take(2).joinToString(".") // "3.14"

/** ABIs to build (gradle.properties `skypython.abis`); see buildSrc/PythonAbis.kt. */
val pythonAbis: List<String> = skypythonAbis()

val pythonBuildDir = layout.buildDirectory.dir("python")

/** Downloads [url] to [dest] and verifies its SHA-256; a verified file is reused. */
abstract class DownloadVerifiedFile : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val dest: RegularFileProperty

    @TaskAction
    fun download() {
        val out = dest.get().asFile
        val expected = sha256.get().lowercase()
        if (out.isFile && sha256Of(out) == expected) return
        out.parentFile.mkdirs()
        val part = File(out.path + ".part")
        logger.lifecycle("Downloading ${url.get()}")
        URI(url.get()).toURL().openStream().use { input ->
            part.outputStream().use { input.copyTo(it) }
        }
        val actual = sha256Of(part)
        if (actual != expected) {
            part.delete()
            throw GradleException(
                "SHA-256 mismatch for ${url.get()}\n  expected: $expected\n  actual:   $actual\n" +
                    "If the release was intentionally changed, update runtime/python.properties."
            )
        }
        Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val preparePython = tasks.register("preparePython") {
    group = "python"
    description = "Copies CPython native libs to jniLibs and zips the stdlib into assets."
}

// Output of ABIs dropped from `skypython.abis` would otherwise stay in assets (and the APK).
val deleteStalePython = tasks.register<Delete>("deleteStalePython") {
    group = "python"
    val stale = PythonAbis.TRIPLES.keys - pythonAbis.toSet()
    delete(stale.map { "src/main/assets/python/stdlib-$it.zip" })
    delete(stale.map { "src/main/jniLibs/$it" })
}
preparePython.configure { dependsOn(deleteStalePython) }

pythonAbis.forEach { abi ->
    val taskSuffix = abi.split("-", "_").joinToString("") { it.replaceFirstChar(Char::uppercase) }
    val tarball = pythonBuildDir.map { it.file(PythonAbis.tarballName(abi, pythonVersion)) }
    val extractDir = pythonBuildDir.map { it.dir(abi) }

    val download = tasks.register<DownloadVerifiedFile>("downloadPython$taskSuffix") {
        group = "python"
        url.set(PythonAbis.tarballUrl(abi, pythonVersion))
        // Lazy: a placeholder hash fails only when this ABI's download actually runs.
        sha256.set(provider { PythonAbis.sha256(pythonProps, abi) })
        dest.set(tarball)
    }

    val extract = tasks.register<Sync>("extractPython$taskSuffix") {
        group = "python"
        dependsOn(download)
        from(tarTree(resources.gzip(tarball))) {
            include("prefix/include/python$pythonShortVersion/**")
            include("prefix/lib/**")
        }
        into(extractDir)
    }

    val copyLibs = tasks.register<Sync>("copyPythonLibs$taskSuffix") {
        group = "python"
        dependsOn(extract)
        from(extractDir.map { it.dir("prefix/lib") }) {
            // Only the "_python"-suffixed OpenSSL/SQLite builds: those are what the
            // extension modules and libpython link against.
            include(
                "libpython$pythonShortVersion.so",
                "libssl_python.so",
                "libcrypto_python.so",
                "libsqlite3_python.so",
            )
        }
        into(layout.projectDirectory.dir("src/main/jniLibs/$abi"))
    }

    val zipStdlib = tasks.register<Zip>("zipPythonStdlib$taskSuffix") {
        group = "python"
        dependsOn(extract)
        from(extractDir.map { it.dir("prefix/lib/python$pythonShortVersion") }) {
            exclude("test/**", "idlelib/**", "tkinter/**", "turtledemo/**")
            exclude("**/__pycache__/**")
            into("lib/python$pythonShortVersion")
        }
        includeEmptyDirs = false
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        destinationDirectory.set(layout.projectDirectory.dir("src/main/assets/python"))
        archiveFileName.set("stdlib-$abi.zip")
    }

    preparePython.configure { dependsOn(copyLibs, zipStdlib) }
}

android {
    namespace = "dev.skyloft.skypython.runtime"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PYTHON_VERSION", "\"$pythonVersion\"")
        buildConfigField("String", "PYTHON_SHORT_VERSION", "\"$pythonShortVersion\"")
        buildConfigField("String", "PYTHON_ABIS", "\"${pythonAbis.joinToString(",")}\"")
        ndk {
            abiFilters += pythonAbis
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DPYTHON_BUILD_DIR=${pythonBuildDir.get().asFile.invariantSeparatorsPath}",
                    "-DPYTHON_SHORT_VERSION=$pythonShortVersion",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The launcher must exist as a real file in nativeLibraryDir (test APK too).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

tasks.named("preBuild") { dependsOn(preparePython) }
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(preparePython)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
