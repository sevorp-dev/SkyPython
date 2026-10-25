package dev.skyloft.skypython.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** How the system's installer finished (see [ApkInstaller.results]). */
sealed interface InstallResult {
    data object Success : InstallResult
    /** The user said no in the system dialog. */
    data object Cancelled : InstallResult
    /** Given to the system's installer screen, which reports nothing back. */
    data object HandedOff : InstallResult
    /** [status] is a PackageInstaller STATUS_* code; [message] the system's text. */
    data class Failed(val status: Int, val message: String?) : InstallResult
}

/** Hands a verified APK to Android's installer. */
interface ApkInstaller {
    /** The user allowed SkyPython to install apps ("Install unknown apps", Android 8+). */
    fun canInstall(): Boolean

    /** Starts the install; the system asks the user, and the outcome arrives in [results]. */
    suspend fun install(file: File)

    val results: Flow<InstallResult>
}

sealed interface UpdateFailure {
    data object Network : UpdateFailure
    data object Http : UpdateFailure
    data object NoChecksum : UpdateFailure
    data object Checksum : UpdateFailure
    data object Storage : UpdateFailure
    /** [incompatible]: signed with another key; the old app must be uninstalled once. */
    data class Install(val status: Int, val message: String?, val incompatible: Boolean) : UpdateFailure
}

sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    /** [total] is -1 while unknown. */
    data class Downloading(val done: Long, val total: Long) : UpdatePhase
    /** Waiting for the system's installer. */
    data object Installing : UpdatePhase
    data class Failed(val failure: UpdateFailure) : UpdatePhase
}

/** One-off news for the UI (a Toast / snackbar), cleared with [UpdateController.consumeMessage]. */
sealed interface UpdateMessage {
    data class UpToDate(val version: String) : UpdateMessage
    data class Available(val version: String) : UpdateMessage
    data object CheckFailed : UpdateMessage
}

data class UpdateState(
    val currentVersion: String,
    /** A release newer than this app, or null. */
    val available: SemVer? = null,
    /** Its notes (Markdown), when they could be read. */
    val notes: String? = null,
    val skipped: Boolean = false,
    val bannerDismissed: Boolean = false,
    val autoCheck: Boolean = true,
    val checking: Boolean = false,
    val phase: UpdatePhase = UpdatePhase.Idle,
    val message: UpdateMessage? = null,
    /** Install was pressed but Android doesn't let SkyPython install apps yet. */
    val needsInstallPermission: Boolean = false,
) {
    /** The dot on ☰, the drawer's About and the About screen's row. */
    val badge: Boolean get() = available != null && !skipped
    val banner: Boolean get() = badge && !bannerDismissed
    val busy: Boolean get() = phase is UpdatePhase.Downloading || phase == UpdatePhase.Installing
}

/**
 * The in-app updater: checks GitHub for a newer release (at most every 12 hours on its own,
 * or when asked), downloads the APK for this build's ABI, verifies it and hands it to [installer].
 */
class UpdateController(
    private val http: UpdateHttp,
    private val prefs: UpdatePrefs,
    private val installer: ApkInstaller,
    private val currentVersion: String,
    /** The APK variant this build is (BuildConfig.SKY_APK_VARIANT): an ABI or "universal". */
    private val variant: String,
    downloadDir: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Live(
        val checking: Boolean = false,
        val phase: UpdatePhase = UpdatePhase.Idle,
        val message: UpdateMessage? = null,
        val needsPermission: Boolean = false,
    )

    private val live = MutableStateFlow(Live())
    private val downloader = UpdateDownloader(http, downloadDir, io)
    private var job: Job? = null

    val state: StateFlow<UpdateState> = combine(prefs.data, live) { p, l ->
        val available = SemVer.parse(p.latest)?.takeIf { Releases.isNewer(it.toString(), currentVersion) }
        UpdateState(
            currentVersion = currentVersion,
            available = available,
            notes = if (available != null) p.latestNotes else null,
            skipped = available != null && !UpdatePolicy.shouldAnnounce(available, p.skipped),
            bannerDismissed = available != null && !UpdatePolicy.showBanner(available, p.dismissed),
            autoCheck = p.autoCheck,
            checking = l.checking,
            phase = l.phase,
            message = l.message,
            needsInstallPermission = l.needsPermission,
        )
    }.stateIn(scope, SharingStarted.Eagerly, UpdateState(currentVersion))

    init {
        scope.launch { installer.results.collect(::onInstallResult) }
        // Nothing left to install once this version runs: drop old downloads.
        scope.launch(io) {
            val latest = prefs.data.first().latest
            if (latest == null || !Releases.isNewer(latest, currentVersion)) runCatching { downloader.clear() }
        }
    }

    /** On app start: checks when automatic checks are on and the last one is 12+ hours old. */
    fun start() {
        scope.launch {
            val p = prefs.data.first()
            if (UpdatePolicy.shouldAutoCheck(p.autoCheck, p.lastCheckAt, clock())) check(manual = false)
        }
    }

    /** "Check for updates": always asks GitHub, and says what it found. */
    fun checkNow() {
        scope.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean) {
        if (live.value.checking) return
        live.update { it.copy(checking = true) }
        try {
            when (val result = UpdateChecker(http, currentVersion, io).check()) {
                is CheckResult.Available -> {
                    val v = result.version.toString()
                    prefs.edit {
                        it.copy(
                            lastCheckAt = clock(),
                            latest = v,
                            latestNotes = result.notes ?: it.latestNotes.takeIf { _ -> it.latest == v },
                            // Asked for by hand: show it even if it was skipped before.
                            skipped = if (manual && it.skipped == v) null else it.skipped,
                        )
                    }
                    if (manual) live.update { it.copy(message = UpdateMessage.Available(v)) }
                }
                is CheckResult.UpToDate -> {
                    prefs.edit { it.copy(lastCheckAt = clock(), latest = null, latestNotes = null) }
                    if (manual) live.update { it.copy(message = UpdateMessage.UpToDate(currentVersion)) }
                }
                // Offline: quiet on its own (tried again next start), a message when asked.
                is CheckResult.Failed -> if (manual) live.update { it.copy(message = UpdateMessage.CheckFailed) }
            }
        } finally {
            live.update { it.copy(checking = false) }
        }
    }

    fun consumeMessage() = live.update { it.copy(message = null) }

    fun setAutoCheck(on: Boolean) {
        scope.launch { prefs.edit { it.copy(autoCheck = on) } }
    }

    fun dismissBanner() {
        val v = state.value.available ?: return
        scope.launch { prefs.edit { it.copy(dismissed = v.toString()) } }
    }

    fun skip() {
        val v = state.value.available ?: return
        cancel()
        scope.launch { prefs.edit { it.copy(skipped = v.toString()) } }
    }

    /** The Install button: download, verify, install. Asks for the install permission first. */
    fun install() {
        val version = state.value.available ?: return
        if (state.value.busy) return
        if (!installer.canInstall()) {
            live.update { it.copy(needsPermission = true) }
            return
        }
        live.update { it.copy(needsPermission = false, phase = UpdatePhase.Downloading(0, -1)) }
        job = scope.launch {
            val phase = try {
                val file = downloader.download(version, variant) { done, total ->
                    live.update { it.copy(phase = UpdatePhase.Downloading(done, total)) }
                }
                live.update { it.copy(phase = UpdatePhase.Installing) }
                installer.install(file)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                UpdatePhase.Failed(e.kind.failure())
            } catch (e: Exception) {
                UpdatePhase.Failed(UpdateFailure.Install(-1, e.message ?: e.javaClass.simpleName, incompatible = false))
            }
            if (phase != null) live.update { it.copy(phase = phase) }
        }
    }

    /** Back on screen (maybe from the "Install unknown apps" page): go on if now allowed. */
    fun onResume() {
        if (live.value.needsPermission && installer.canInstall()) install()
    }

    fun dismissPermission() = live.update { it.copy(needsPermission = false) }

    /** Stops the download (the part already there is resumed next time). */
    fun cancel() {
        job?.cancel()
        job = null
        live.update { it.copy(phase = UpdatePhase.Idle) }
    }

    /** Closes the failure card. */
    fun clearFailure() {
        if (live.value.phase is UpdatePhase.Failed) live.update { it.copy(phase = UpdatePhase.Idle) }
    }

    private fun onInstallResult(result: InstallResult) {
        val phase = when (result) {
            // The system restarts the app as the new version; nothing to show meanwhile.
            InstallResult.Success, InstallResult.Cancelled, InstallResult.HandedOff -> UpdatePhase.Idle
            is InstallResult.Failed -> UpdatePhase.Failed(
                UpdateFailure.Install(result.status, result.message, isIncompatible(result.status, result.message)),
            )
        }
        live.update { it.copy(phase = phase) }
    }

    private fun UpdateException.Kind.failure(): UpdateFailure = when (this) {
        UpdateException.Kind.Network -> UpdateFailure.Network
        UpdateException.Kind.Http -> UpdateFailure.Http
        UpdateException.Kind.NoChecksum -> UpdateFailure.NoChecksum
        UpdateException.Kind.Checksum -> UpdateFailure.Checksum
        UpdateException.Kind.Storage -> UpdateFailure.Storage
    }

    companion object {
        /** PackageInstaller.STATUS_FAILURE_CONFLICT: how a signature mismatch is reported. */
        private const val STATUS_FAILURE_CONFLICT = 5

        /** A different signing key (INSTALL_FAILED_UPDATE_INCOMPATIBLE). */
        fun isIncompatible(status: Int, message: String?): Boolean {
            if (message == null) return status == STATUS_FAILURE_CONFLICT
            return message.contains("UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                message.contains("signatures do not match", ignoreCase = true)
        }
    }
}
