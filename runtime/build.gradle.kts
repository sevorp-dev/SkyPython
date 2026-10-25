import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
            // CPython's own test-suite extension modules and frozen-module demo: only
            // Lib/test imports them.
            exclude(
                "lib-dynload/_test*.so",
                "lib-dynload/_ctypes_test*.so",
                "lib-dynload/_xxtestfuzz*.so",
                "lib-dynload/xxlimited*.so",
                "lib-dynload/xxsubtype*.so",
                "__phello__/**",
            )
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
        minSdk = 24 // Android 7.0, the API level the python.org CPython build targets
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

// ---------------------------------------------------------------------------
// Language server (editor completions and error hints): jedi + parso + pyflakes wheels from
// PyPI plus src/main/langserver/, packed into assets/langserver/langserver.zip. LanguageServer-
// Installer unpacks it to filesDir/langserver (never into the user's pip site).
// ---------------------------------------------------------------------------

val langProps = Properties().apply { file("langserver.properties").inputStream().use { load(it) } }
val langWheelsDir = layout.buildDirectory.dir("langserver")

/** Packs [wheels] (filtered, see [map]) and [serverDir] into `langserver/langserver.zip` + `.version`. */
abstract class LangServerBundle : DefaultTask() {
    @get:InputFiles abstract val wheels: ConfigurableFileCollection
    @get:InputDirectory abstract val serverDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val entries = sortedMapOf<String, ByteArray>()
        for (wheel in wheels.files.sortedBy { it.name }) {
            ZipFile(wheel).use { zip ->
                for (e in zip.entries()) {
                    if (e.isDirectory) continue
                    val name = map(e.name) ?: continue
                    entries[name] = zip.getInputStream(e).use { it.readBytes() }
                }
            }
        }
        val root = serverDir.get().asFile
        root.walkTopDown().filter { it.isFile && "__pycache__" !in it.path }.forEach {
            entries[it.relativeTo(root).invariantSeparatorsPath] = it.readBytes()
        }
        val zipFile = File(out, "langserver.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            zos.setLevel(9)
            for ((name, bytes) in entries) {
                // Fixed timestamps: the same inputs give the same zip (and .version).
                zos.putNextEntry(ZipEntry(name).apply { time = 315532800000L })
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(zipFile.readBytes())
        File(out, "langserver.version").writeText(digest.joinToString("") { "%02x".format(it) })
        logger.lifecycle("Language server bundle: ${entries.size} files, ${zipFile.length() / 1024} KB")
    }

    /** Bundle path for a wheel entry, or null to drop it. */
    private fun map(name: String): String? {
        val license = Regex("""([A-Za-z_]+)-[^/]+\.dist-info/(?:licenses/)?LICENSE(?:\.txt)?""").matchEntire(name)
        if (license != null) return "licenses/${license.groupValues[1]}-LICENSE.txt"
        if (name.substringBefore('/').endsWith(".dist-info")) return null
        if ("/__pycache__/" in name || name.endsWith(".pyc")) return null
        val dropped = listOf(
            // Third-party stubs (~8 MB): pip-installed packages are read from their source.
            "jedi/third_party/typeshed/stubs/",
            "jedi/third_party/django-stubs/",
            // Not in the bundled stdlib (see zipPythonStdlib).
            "jedi/third_party/typeshed/stdlib/tkinter/",
            "pyflakes/test/",
        )
        return name.takeIf { n -> dropped.none { n.startsWith(it) } }
    }
}

val langWheelDownloads = listOf("jedi", "parso", "pyflakes").map { pkg ->
    val version = langProps.getProperty("$pkg.version")
    val fileName = "$pkg-$version-py2.py3-none-any.whl"
    tasks.register<DownloadVerifiedFile>("download${pkg.replaceFirstChar(Char::uppercase)}Wheel") {
        group = "langserver"
        url.set("https://files.pythonhosted.org/packages/py2.py3/${pkg.first()}/$pkg/$fileName")
        sha256.set(langProps.getProperty("$pkg.sha256"))
        dest.set(langWheelsDir.map { it.file(fileName) })
    }
}

val langServerBundle = tasks.register<LangServerBundle>("langServerBundle") {
    group = "langserver"
    description = "Packs jedi, parso, pyflakes and the server script into assets/langserver/."
    wheels.from(langWheelDownloads.map { t -> t.flatMap { it.dest } })
    serverDir.set(layout.projectDirectory.dir("src/main/langserver"))
    outputDir.set(layout.projectDirectory.dir("src/main/assets/langserver"))
}

tasks.named("preBuild") { dependsOn(preparePython, langServerBundle) }
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
