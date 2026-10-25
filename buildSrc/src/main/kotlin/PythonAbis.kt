import org.gradle.api.Project
import java.util.Properties

/**
 * Android ABIs SkyPython can ship CPython for, and where each ABI's CPython comes from.
 *
 * Which ABIs are built is set by the single Gradle property [PROPERTY] (gradle.properties);
 * every module reads it through [Project.skypythonAbis].
 */
object PythonAbis {
    const val PROPERTY = "skypython.abis"
    const val DEFAULT = "arm64-v8a,x86_64"

    /** Value of a not-yet-pinned hash in runtime/python.properties. */
    const val PLACEHOLDER = "TODO_RUN_build-python_WORKFLOW"

    /** Android ABI -> CPython host triple (Android/android.py HOSTS). */
    val TRIPLES: Map<String, String> = linkedMapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "arm-linux-androideabi",
        "x86" to "i686-linux-android",
        "x86_64" to "x86_64-linux-android",
    )

    /** ABIs that python.org publishes official builds for. */
    private val OFFICIAL = setOf("arm64-v8a", "x86_64")

    /**
     * Our own builds of the other ABIs (.github/workflows/build-python.yml), stored in the
     * `cpython-builds` branch so they don't clutter the app's Releases page.
     */
    private const val OWN_BUILDS = "https://raw.githubusercontent.com/sevorp-dev/SkyPython/cpython-builds"

    /** Parses a comma-separated ABI list; blank/null means [DEFAULT]. */
    fun parse(value: String?): List<String> {
        val raw = value?.takeIf { it.isNotBlank() } ?: DEFAULT
        val abis = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val unknown = abis.filter { it !in TRIPLES }
        require(unknown.isEmpty()) {
            "$PROPERTY: unknown ABI(s) ${unknown.joinToString()}; allowed: ${TRIPLES.keys.joinToString()}"
        }
        return abis
    }

    fun triple(abi: String): String =
        requireNotNull(TRIPLES[abi]) { "No CPython build for ABI '$abi'; allowed: ${TRIPLES.keys.joinToString()}" }

    /** File name produced by `android.py package` (same on python.org). */
    fun tarballName(abi: String, version: String): String = "python-$version-${triple(abi)}.tar.gz"

    fun tarballUrl(abi: String, version: String): String {
        val name = tarballName(abi, version)
        return if (abi in OFFICIAL) {
            "https://www.python.org/ftp/python/$version/$name"
        } else {
            "$OWN_BUILDS/$name"
        }
    }

    /** The pinned SHA-256 of [abi]'s tarball from runtime/python.properties. */
    fun sha256(props: Properties, abi: String): String {
        val key = "sha256.${triple(abi)}"
        val value = props.getProperty(key)?.trim()
        require(value != PLACEHOLDER) {
            "CPython for $abi is not built yet ($key is a placeholder in runtime/python.properties). " +
                "Run the GitHub workflow 'build-python' (push the branch `build-python`), " +
                "paste the sha256 lines it prints into runtime/python.properties, " +
                "or remove $abi from $PROPERTY in gradle.properties."
        }
        require(value != null && Regex("[0-9a-fA-F]{64}").matches(value)) {
            "runtime/python.properties: $key is missing or not a SHA-256 hex digest"
        }
        return value.lowercase()
    }
}

/** The ABIs this build ships (Gradle property `skypython.abis`). */
fun Project.skypythonAbis(): List<String> =
    PythonAbis.parse(providers.gradleProperty(PythonAbis.PROPERTY).orNull)
