package dev.skyloft.skypython.pip

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PipTab { Install, Installed }

sealed interface InstallOutcome {
    data class Succeeded(val spec: String) : InstallOutcome
    /** [buildFailure]: something needed compiling, so the pure-Python hint applies. */
    data class Failed(val message: String, val buildFailure: Boolean) : InstallOutcome
}

data class PipState(
    val tab: PipTab = PipTab.Install,
    val spec: String = "",
    val specError: SpecProblem? = null,
    val log: String = "",
    val installing: Boolean = false,
    val outcome: InstallOutcome? = null,
    val packages: List<PipPackage> = emptyList(),
    val loading: Boolean = false,
    val listError: String? = null,
    /** Asking "Uninstall X?" */
    val confirmUninstall: PipPackage? = null,
    /** Name of the package being uninstalled. */
    val uninstalling: String? = null,
)

class PipViewModel(private val pip: PipManager) : ViewModel() {

    private val _state = MutableStateFlow(PipState())
    val state: StateFlow<PipState> = _state.asStateFlow()

    /** The Installed list must be (re)loaded before it is shown. */
    private var listStale = true
    private var listJob: Job? = null

    fun selectTab(tab: PipTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == PipTab.Installed && listStale) refresh()
    }

    fun onSpecChange(value: String) {
        _state.update { it.copy(spec = value, specError = null) }
    }

    fun install() {
        val current = _state.value
        if (current.installing) return
        val spec = when (val check = PackageSpec.check(current.spec)) {
            is SpecCheck.Valid -> check.spec
            is SpecCheck.Invalid -> {
                _state.update { it.copy(specError = check.problem) }
                return
            }
        }
        _state.update { it.copy(installing = true, log = "", outcome = null, specError = null) }
        viewModelScope.launch {
            val outcome = try {
                pip.install(spec).collect { chunk -> _state.update { it.copy(log = capped(it.log + chunk)) } }
                listStale = true
                InstallOutcome.Succeeded(spec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                val log = _state.value.log
                _state.update { it.copy(log = capped(withNewline(it.log) + message + "\n")) }
                InstallOutcome.Failed(message, PipLog.isBuildFailure(log))
            }
            _state.update { it.copy(installing = false, outcome = outcome) }
        }
    }

    fun refresh() {
        if (listJob?.isActive == true) return
        listStale = false
        _state.update { it.copy(loading = true, listError = null) }
        listJob = viewModelScope.launch { loadList() }
    }

    fun requestUninstall(pkg: PipPackage) {
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
                _state.update { it.copy(listError = e.message ?: e.toString()) }
                return@launch
            }
            listJob?.join()
            listStale = false
            _state.update { it.copy(loading = true) }
            loadList()
        }
    }

    private suspend fun loadList() {
        pip.listInstalled().fold(
            onSuccess = { list -> _state.update { it.copy(packages = list, loading = false) } },
            onFailure = { e ->
                listStale = true
                _state.update { it.copy(loading = false, listError = e.message ?: e.toString()) }
            },
        )
    }

    companion object {
        /** The log keeps its last this many characters. */
        const val MAX_LOG_CHARS = 200_000

        private fun capped(log: String): String =
            if (log.length <= MAX_LOG_CHARS) log else log.substring(log.length - MAX_LOG_CHARS)

        private fun withNewline(log: String) = if (log.isEmpty() || log.endsWith("\n")) log else "$log\n"

        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { PipViewModel(PipRepository.create(app)) }
        }
    }
}
