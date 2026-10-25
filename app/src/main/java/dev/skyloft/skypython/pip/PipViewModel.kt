package dev.skyloft.skypython.pip

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.skyloft.skypython.locale.AppLocale
import java.io.File

enum class PipTab { Search, Installed, Requirements }

/** A pip run that streams into the log; one at a time. */
sealed interface PipOperation {
    data class Install(val spec: String) : PipOperation
    data class Upgrade(val name: String) : PipOperation
    data class Requirements(val file: File) : PipOperation
}

sealed interface InstallOutcome {
    val operation: PipOperation
    data class Succeeded(override val operation: PipOperation) : InstallOutcome
    /** [buildFailure]: something needed compiling, so the pure-Python hint applies. */
    data class Failed(override val operation: PipOperation, val message: String, val buildFailure: Boolean) : InstallOutcome
}

/** One-shot messages for a snackbar; cleared with [PipViewModel.noticeShown]. */
sealed interface PipNotice {
    data class Done(val operation: PipOperation) : PipNotice
    data class Failed(val operation: PipOperation) : PipNotice
    data class Uninstalled(val name: String) : PipNotice
}

/** The package details sheet. */
data class DetailsState(
    val pkg: PipPackage,
    val loading: Boolean = true,
    val info: PackageDetails? = null,
    val error: String? = null,
)

data class PipState(
    val tab: PipTab = PipTab.Search,
    /** The search / install field. */
    val spec: String = "",
    val specError: SpecProblem? = null,
    // Search
    val searching: Boolean = false,
    val lookup: LookupResult? = null,
    // The running or last operation
    val running: PipOperation? = null,
    val log: String = "",
    val outcome: InstallOutcome? = null,
    val notice: PipNotice? = null,
    // Installed
    val packages: List<PipPackage> = emptyList(),
    val loading: Boolean = false,
    val listError: String? = null,
    /** Normalized name → newer version. */
    val updates: Map<String, String> = emptyMap(),
    val checkingUpdates: Boolean = false,
    /** The update check failed (usually: offline); the list itself is fine. */
    val updatesFailed: Boolean = false,
    /** Asking "Uninstall X?" */
    val confirmUninstall: PipPackage? = null,
    /** Name of the package being uninstalled. */
    val uninstalling: String? = null,
    val details: DetailsState? = null,
    // requirements.txt
    val requirementFiles: List<File> = emptyList(),
    val scanningFiles: Boolean = false,
    /** SkyPython's prebuilt wheels for this device; null until loaded, or when there's no index. */
    val catalog: WheelCatalog? = null,
) {
    val installing: Boolean get() = running != null

    /** Whether [name] is in the wheel index (for this device or not); null if not, or no index. */
    fun prebuiltFor(name: String): PrebuiltStatus? = catalog?.status(name)

    /** The newer version of [pkg], if the update check found one. */
    fun updateFor(pkg: PipPackage): String? = updates[PyPiJson.normalize(pkg.name)]

    /** Whether [name] is in the (loaded) Installed list. */
    fun isInstalled(name: String): Boolean {
        val n = PyPiJson.normalize(name)
        return packages.any { PyPiJson.normalize(it.name) == n }
    }
}

/**
 * @param index looks names up on PyPI (Search tab).
 * @param findRequirements lists candidate requirements files (requirements tab).
 */
class PipViewModel(
    private val pip: PipManager,
    private val index: PackageIndex = PackageIndex { LookupResult.Offline("") },
    private val findRequirements: suspend () -> List<File> = { emptyList() },
    /** A failure as the user reads it (localized in the app; the English message in tests). */
    private val describe: (Throwable) -> String = { it.message ?: it.toString() },
    /** SkyPython's wheel index for this device (null: none). */
    private val prebuilt: suspend () -> WheelCatalog? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow(PipState())
    val state: StateFlow<PipState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val catalog = try {
                prebuilt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (catalog != null) _state.update { it.copy(catalog = catalog) }
        }
    }

    /** The Installed list must be (re)loaded before it is shown. */
    private var listStale = true
    private var listJob: Job? = null
    private var updatesJob: Job? = null
    private var searchJob: Job? = null
    private var detailsJob: Job? = null
    private var scanJob: Job? = null

    fun selectTab(tab: PipTab) {
        _state.update { it.copy(tab = tab) }
        when (tab) {
            PipTab.Installed -> if (listStale) refresh()
            PipTab.Requirements -> scanRequirements()
            PipTab.Search -> Unit
        }
    }

    fun onSpecChange(value: String) {
        searchJob?.cancel()
        _state.update { it.copy(spec = value, specError = null, lookup = null, searching = false) }
    }

    // --- Search ----------------------------------------------------------------------------

    /** Looks the name in the field up on PyPI. */
    fun search() {
        val spec = validSpec() ?: return
        val name = PackageSpec.nameOf(spec)
        searchJob?.cancel()
        _state.update { it.copy(searching = true, lookup = null) }
        searchJob = viewModelScope.launch {
            val result = index.lookup(name)
            _state.update { it.copy(searching = false, lookup = result) }
        }
    }

    /**
     * Installs [name] from a search result or the popular list: the typed spec if it's for
     * that package (keeps a version like `six==1.16.0`), else just the name.
     */
    fun installPackage(name: String) {
        val typed = (PackageSpec.check(_state.value.spec) as? SpecCheck.Valid)?.spec
        val spec = typed?.takeIf { PyPiJson.normalize(PackageSpec.nameOf(it)) == PyPiJson.normalize(name) } ?: name
        startInstall(spec)
    }

    // --- Install / upgrade / requirements ------------------------------------------------------

    /** Installs what's typed in the field. */
    fun install() {
        if (_state.value.installing) return
        val spec = validSpec() ?: return
        startInstall(spec)
    }

    /**
     * For other screens (e.g. "No module named x" → install): shows the Search tab with
     * [spec] and installs it. False (nothing started) if [spec] is invalid or pip is busy.
     */
    fun installNow(spec: String): Boolean {
        if (_state.value.installing) return false
        val valid = (PackageSpec.check(spec) as? SpecCheck.Valid)?.spec ?: return false
        searchJob?.cancel()
        _state.update { it.copy(tab = PipTab.Search, spec = valid, specError = null, lookup = null, searching = false) }
        startInstall(valid)
        return true
    }

    fun upgrade(pkg: PipPackage) {
        runOperation(PipOperation.Upgrade(pkg.name)) { pip.upgrade(pkg.name) }
    }

    fun installRequirements(file: File) {
        runOperation(PipOperation.Requirements(file)) { pip.installRequirements(file) }
    }

    /** Hides the log of a finished operation. */
    fun clearLog() {
        if (_state.value.installing) return
        _state.update { it.copy(log = "", outcome = null) }
    }

    fun noticeShown() {
        _state.update { it.copy(notice = null) }
    }

    private fun startInstall(spec: String) {
        runOperation(PipOperation.Install(spec)) { pip.install(spec) }
    }

    private fun runOperation(op: PipOperation, output: () -> Flow<String>) {
        if (_state.value.installing) return
        _state.update { it.copy(running = op, log = "", outcome = null, specError = null) }
        viewModelScope.launch {
            val outcome = try {
                output().collect { chunk -> _state.update { it.copy(log = capped(it.log + chunk)) } }
                InstallOutcome.Succeeded(op)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = describe(e)
                val log = _state.value.log
                _state.update { it.copy(log = capped(withNewline(it.log) + message + "\n")) }
                InstallOutcome.Failed(op, message, PipLog.isBuildFailure(log))
            }
            val notice = when (outcome) {
                is InstallOutcome.Succeeded -> PipNotice.Done(op)
                is InstallOutcome.Failed -> PipNotice.Failed(op)
            }
            // What pip says it installed shows at once everywhere (popular rows, search result,
            // Installed tab); `pip list` then confirms it.
            val fresh = PipLog.installed(_state.value.log)
            _state.update { it.copy(running = null, outcome = outcome, notice = notice, packages = merged(it.packages, fresh)) }
            // Even a failed requirements run may have installed some packages.
            reload()
        }
    }

    private fun merged(packages: List<PipPackage>, fresh: List<PipPackage>): List<PipPackage> {
        if (fresh.isEmpty()) return packages
        val names = fresh.map { PyPiJson.normalize(it.name) }.toSet()
        return (packages.filter { PyPiJson.normalize(it.name) !in names } + fresh)
            .sortedBy { PyPiJson.normalize(it.name) }
    }

    private fun validSpec(): String? = when (val check = PackageSpec.check(_state.value.spec)) {
        is SpecCheck.Valid -> check.spec
        is SpecCheck.Invalid -> {
            _state.update { it.copy(specError = check.problem) }
            null
        }
    }

    // --- Installed -------------------------------------------------------------------------

    /** The screen became visible: the popular rows need to know what's installed. */
    fun onShown() {
        if (listStale) refresh()
    }

    fun refresh() {
        if (listJob?.isActive == true) return
        reload()
    }

    /** Runs `pip list` now, replacing a run that started before pip changed something. */
    private fun reload() {
        listJob?.cancel()
        listStale = false
        _state.update { it.copy(loading = true, listError = null) }
        listJob = viewModelScope.launch { loadList() }
    }

    fun requestUninstall(pkg: PipPackage) {
        closeDetails()
        _state.update { it.copy(confirmUninstall = pkg) }
    }

    fun dismissUninstall() {
        _state.update { it.copy(confirmUninstall = null) }
    }

    fun confirmUninstall() {
        val pkg = _state.value.confirmUninstall ?: return
        _state.update { it.copy(confirmUninstall = null, uninstalling = pkg.name, listError = null) }
        viewModelScope.launch {
            val result = pip.uninstall(pkg.name)
            _state.update { it.copy(uninstalling = null) }
            result.onFailure { e ->
                _state.update { it.copy(listError = describe(e)) }
                return@launch
            }
            val gone = PyPiJson.normalize(pkg.name)
            _state.update { s ->
                s.copy(
                    notice = PipNotice.Uninstalled(pkg.name),
                    packages = s.packages.filter { PyPiJson.normalize(it.name) != gone },
                )
            }
            reload()
        }
    }

    private suspend fun loadList() {
        pip.listInstalled().fold(
            onSuccess = { list ->
                _state.update { it.copy(packages = list, loading = false) }
                checkUpdates(list)
            },
            onFailure = { e ->
                listStale = true
                _state.update { it.copy(loading = false, listError = describe(e)) }
            },
        )
    }

    private fun checkUpdates(list: List<PipPackage>) {
        updatesJob?.cancel()
        if (list.isEmpty()) {
            _state.update { it.copy(updates = emptyMap(), checkingUpdates = false, updatesFailed = false) }
            return
        }
        _state.update { it.copy(checkingUpdates = true, updatesFailed = false) }
        updatesJob = viewModelScope.launch {
            val result = pip.listOutdated()
            _state.update {
                it.copy(
                    checkingUpdates = false,
                    updates = result.getOrNull() ?: it.updates,
                    updatesFailed = result.isFailure,
                )
            }
        }
    }

    // --- Details ---------------------------------------------------------------------------

    fun openDetails(pkg: PipPackage) {
        detailsJob?.cancel()
        _state.update { it.copy(details = DetailsState(pkg)) }
        detailsJob = viewModelScope.launch {
            val result = pip.show(pkg.name)
            _state.update { s ->
                if (s.details?.pkg != pkg) return@update s
                s.copy(
                    details = DetailsState(
                        pkg,
                        loading = false,
                        info = result.getOrNull(),
                        error = result.exceptionOrNull()?.let(describe),
                    ),
                )
            }
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        _state.update { it.copy(details = null) }
    }

    // --- requirements.txt ------------------------------------------------------------------

    fun scanRequirements() {
        scanJob?.cancel()
        _state.update { it.copy(scanningFiles = true) }
        scanJob = viewModelScope.launch {
            val files = findRequirements()
            _state.update { it.copy(requirementFiles = files, scanningFiles = false) }
        }
    }

    companion object {
        /** The log keeps its last this many characters. */
        const val MAX_LOG_CHARS = 200_000

        private fun capped(log: String): String =
            if (log.length <= MAX_LOG_CHARS) log else log.substring(log.length - MAX_LOG_CHARS)

        private fun withNewline(log: String) = if (log.isEmpty() || log.endsWith("\n")) log else "$log\n"

        /** @param projectsRoot where the requirements tab looks for `.txt` files. */
        fun factory(app: Application, projectsRoot: File): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val wheels = WheelIndex.forApp(app)
                PipViewModel(
                    pip = PipRepository.create(app, wheels),
                    prebuilt = wheels::catalog,
                    index = PyPiClient(),
                    findRequirements = { withContext(Dispatchers.IO) { RequirementsFiles.find(projectsRoot) } },
                    describe = { e -> describePipError(e) { id, args -> AppLocale.string(app, id, *args) } },
                )
            }
        }
    }
}
