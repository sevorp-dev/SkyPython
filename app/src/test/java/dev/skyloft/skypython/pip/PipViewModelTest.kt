package dev.skyloft.skypython.pip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PipViewModelTest {

    private class FakeManager : PipManager {
        /** Every run: "install six", "upgrade six", "requirements /x/r.txt". */
        val runs = mutableListOf<String>()
        val installs get() = runs.filter { it.startsWith("install ") }.map { it.removePrefix("install ") }
        var output = Channel<String>(Channel.UNLIMITED)
        val uninstalls = mutableListOf<String>()
        var uninstallResult: Result<Unit> = Result.success(Unit)
        var listCalls = 0
        var listResult = CompletableDeferred<Result<List<PipPackage>>>()
        var outdatedCalls = 0
        var outdatedResult = CompletableDeferred<Result<Map<String, String>>>()
        val shows = mutableListOf<String>()
        var showResult = CompletableDeferred<Result<PackageDetails>>()

        private fun run(what: String): Flow<String> {
            runs += what
            return output.receiveAsFlow()
        }

        override fun install(spec: String) = run("install $spec")
        override fun upgrade(name: String) = run("upgrade $name")
        override fun installRequirements(file: File) = run("requirements ${file.path}")

        override suspend fun uninstall(name: String): Result<Unit> {
            uninstalls += name
            return uninstallResult
        }

        override suspend fun listInstalled(): Result<List<PipPackage>> {
            listCalls++
            return listResult.await()
        }

        override suspend fun listOutdated(): Result<Map<String, String>> {
            outdatedCalls++
            return outdatedResult.await()
        }

        override suspend fun show(name: String): Result<PackageDetails> {
            shows += name
            return showResult.await()
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val manager = FakeManager()
    private val lookups = mutableListOf<String>()
    private var lookupResult = CompletableDeferred<LookupResult>()
    private var files = listOf<File>()
    private var scans = 0
    private lateinit var vm: PipViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = PipViewModel(
            pip = manager,
            index = PackageIndex { name -> lookups += name; lookupResult.await() },
            findRequirements = { scans++; files },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val state get() = vm.state.value

    private fun finishRun(vararg lines: String) {
        lines.forEach { manager.output.trySend(it) }
        manager.output.close()
    }

    // --- Install -------------------------------------------------------------------------

    @Test
    fun invalidSpecShowsErrorAndRunsNothing() = runTest(dispatcher) {
        vm.onSpecChange("six;ls")
        vm.install()
        runCurrent()
        assertEquals(SpecProblem.Invalid, state.specError)
        assertFalse(state.installing)
        assertEquals(emptyList<String>(), manager.runs)
    }

    @Test
    fun editingClearsTheSpecError() = runTest(dispatcher) {
        vm.onSpecChange("a b")
        vm.install()
        vm.onSpecChange("ab")
        assertNull(state.specError)
    }

    @Test
    fun installStreamsLogThenSucceeds() = runTest(dispatcher) {
        vm.onSpecChange(" six ")
        vm.install()
        runCurrent()
        assertTrue(state.installing)
        assertEquals(PipOperation.Install("six"), state.running)
        assertEquals(listOf("six"), manager.installs)
        manager.output.send("Collecting six\n")
        runCurrent()
        assertTrue(state.log.contains("Collecting six\n"))
        finishRun("Successfully installed six\n")
        runCurrent()
        assertFalse(state.installing)
        assertNull(state.running)
        assertEquals(InstallOutcome.Succeeded(PipOperation.Install("six")), state.outcome)
    }

    @Test
    fun successAndFailureRaiseAOneShotNotice() = runTest(dispatcher) {
        vm.onSpecChange("six")
        vm.install()
        finishRun()
        runCurrent()
        assertEquals(PipNotice.Done(PipOperation.Install("six")), state.notice)
        vm.noticeShown()
        assertNull(state.notice)

        manager.output = Channel(Channel.UNLIMITED)
        vm.install()
        manager.output.close(PipFailedException(1, what = "pip"))
        runCurrent()
        assertEquals(PipNotice.Failed(PipOperation.Install("six")), state.notice)
    }

    @Test
    fun secondOperationWhileRunningIsIgnored() = runTest(dispatcher) {
        vm.onSpecChange("six")
        vm.install()
        runCurrent()
        vm.install()
        vm.upgrade(PipPackage("rich", "1"))
        vm.installRequirements(File("/p/requirements.txt"))
        assertFalse(vm.installNow("tqdm"))
        runCurrent()
        assertEquals(listOf("install six"), manager.runs)
    }

    @Test
    fun newInstallClearsThePreviousLog() = runTest(dispatcher) {
        vm.onSpecChange("six")
        vm.install()
        finishRun("old\n")
        runCurrent()
        manager.output = Channel(Channel.UNLIMITED)
        vm.install()
        runCurrent()
        assertFalse(state.log.contains("old"))
        assertNull(state.outcome)
    }

    @Test
    fun buildFailureIsFlagged() = runTest(dispatcher) {
        vm.onSpecChange("numpy")
        vm.install()
        manager.output.send("ERROR: Failed building wheel for numpy\n")
        manager.output.close(PipFailedException(1, what = "pip"))
        runCurrent()
        val outcome = state.outcome as InstallOutcome.Failed
        assertTrue(outcome.buildFailure)
        assertEquals(PipOperation.Install("numpy"), outcome.operation)
        assertTrue(state.log.contains("pip exited with code 1"))
    }

    @Test
    fun otherFailureIsNotABuildFailure() = runTest(dispatcher) {
        vm.onSpecChange("nosuchpkg")
        vm.install()
        manager.output.send("ERROR: No matching distribution found for nosuchpkg\n")
        manager.output.close(PipFailedException(1, what = "pip"))
        runCurrent()
        assertFalse((state.outcome as InstallOutcome.Failed).buildFailure)
    }

    @Test
    fun logIsCappedKeepingTheEnd() = runTest(dispatcher) {
        vm.onSpecChange("six")
        vm.install()
        val chunk = "x".repeat(1000) + "\n"
        repeat(PipViewModel.MAX_LOG_CHARS / 1000 + 50) { manager.output.send(chunk) }
        finishRun("THE END\n")
        runCurrent()
        assertTrue(state.log.length <= PipViewModel.MAX_LOG_CHARS)
        assertTrue(state.log.contains("THE END"))
    }

    @Test
    fun clearLogHidesTheFinishedPanel() = runTest(dispatcher) {
        vm.onSpecChange("six")
        vm.install()
        runCurrent()
        vm.clearLog() // ignored while running
        manager.output.send("x\n")
        runCurrent()
        assertEquals("x\n", state.log)
        finishRun()
        runCurrent()
        vm.clearLog()
        assertEquals("", state.log)
        assertNull(state.outcome)
    }

    // --- installNow (from other screens) ----------------------------------------------------

    @Test
    fun installNowStartsAnInstallOnTheSearchTab() = runTest(dispatcher) {
        vm.selectTab(PipTab.Requirements)
        assertTrue(vm.installNow(" requests "))
        runCurrent()
        assertEquals(PipTab.Search, state.tab)
        assertEquals("requests", state.spec)
        assertEquals(listOf("requests"), manager.installs)
    }

    @Test
    fun installNowRejectsBadSpecs() = runTest(dispatcher) {
        assertFalse(vm.installNow("-r /etc/passwd"))
        runCurrent()
        assertEquals(emptyList<String>(), manager.runs)
        assertNull(state.running)
    }

    // --- Search ----------------------------------------------------------------------------

    @Test
    fun searchLooksUpTheNameWithoutTheVersion() = runTest(dispatcher) {
        vm.onSpecChange("Requests[socks]>=2")
        vm.search()
        runCurrent()
        assertTrue(state.searching)
        assertEquals(listOf("Requests"), lookups)
        val project = PyPiProject("requests", "2.32.3", "HTTP", null, null, WheelSupport.Pure)
        lookupResult.complete(LookupResult.Found(project))
        runCurrent()
        assertFalse(state.searching)
        assertEquals(LookupResult.Found(project), state.lookup)
    }

    @Test
    fun searchWithAnInvalidQueryShowsTheProblem() = runTest(dispatcher) {
        vm.onSpecChange("a b")
        vm.search()
        runCurrent()
        assertEquals(SpecProblem.Spaces, state.specError)
        assertEquals(emptyList<String>(), lookups)
    }

    @Test
    fun offlineSearchIsReported() = runTest(dispatcher) {
        vm.onSpecChange("six")
        vm.search()
        lookupResult.complete(LookupResult.Offline("no route"))
        runCurrent()
        assertEquals(LookupResult.Offline("no route"), state.lookup)
    }

    @Test
    fun editingTheQueryCancelsAndClearsTheResult() = runTest(dispatcher) {
        vm.onSpecChange("six")
        vm.search()
        runCurrent()
        vm.onSpecChange("sixx")
        assertFalse(state.searching)
        lookupResult.complete(LookupResult.NotFound("six"))
        runCurrent()
        assertNull(state.lookup)
    }

    @Test
    fun installFromAResultInstallsTheTypedSpecWhenItIsThatPackage() = runTest(dispatcher) {
        vm.onSpecChange("six==1.16.0")
        vm.installPackage("six")
        runCurrent()
        assertEquals(listOf("six==1.16.0"), manager.installs)
    }

    @Test
    fun installFromThePopularListInstallsTheName() = runTest(dispatcher) {
        vm.onSpecChange("ric")
        vm.installPackage("rich")
        runCurrent()
        assertEquals(listOf("rich"), manager.installs)
    }

    // --- Installed -------------------------------------------------------------------------

    @Test
    fun openingInstalledTabLoadsTheListOnce() = runTest(dispatcher) {
        vm.selectTab(PipTab.Installed)
        runCurrent()
        assertTrue(state.loading)
        manager.listResult.complete(Result.success(listOf(PipPackage("six", "1.16.0"))))
        runCurrent()
        assertFalse(state.loading)
        assertEquals(listOf(PipPackage("six", "1.16.0")), state.packages)
        vm.selectTab(PipTab.Search)
        vm.selectTab(PipTab.Installed)
        runCurrent()
        assertEquals(1, manager.listCalls)
    }

    @Test
    fun loadedListIsCheckedForUpdates() = runTest(dispatcher) {
        manager.listResult.complete(Result.success(listOf(PipPackage("Six", "1.16.0"), PipPackage("rich", "14"))))
        vm.selectTab(PipTab.Installed)
        runCurrent()
        assertTrue(state.checkingUpdates)
        manager.outdatedResult.complete(Result.success(mapOf("six" to "1.17.0")))
        runCurrent()
        assertFalse(state.checkingUpdates)
        assertEquals("1.17.0", state.updateFor(PipPackage("Six", "1.16.0")))
        assertNull(state.updateFor(PipPackage("rich", "14")))
        assertFalse(state.updatesFailed)
    }

    @Test
    fun updateCheckFailureIsQuiet() = runTest(dispatcher) {
        manager.listResult.complete(Result.success(listOf(PipPackage("six", "1"))))
        manager.outdatedResult.complete(Result.failure(PipFailedException(1, startFailure = "offline")))
        vm.selectTab(PipTab.Installed)
        runCurrent()
        assertTrue(state.updatesFailed)
        assertNull(state.listError)
        assertEquals(listOf(PipPackage("six", "1")), state.packages)
    }

    @Test
    fun emptyListSkipsTheUpdateCheck() = runTest(dispatcher) {
        manager.listResult.complete(Result.success(emptyList()))
        vm.selectTab(PipTab.Installed)
        runCurrent()
        assertEquals(0, manager.outdatedCalls)
        assertFalse(state.checkingUpdates)
    }

    @Test
    fun upgradeRunsAndReloadsTheVisibleList() = runTest(dispatcher) {
        manager.listResult.complete(Result.success(listOf(PipPackage("six", "1.16.0"))))
        manager.outdatedResult.complete(Result.success(mapOf("six" to "1.17.0")))
        vm.selectTab(PipTab.Installed)
        runCurrent()
        vm.upgrade(PipPackage("six", "1.16.0"))
        runCurrent()
        assertEquals(PipOperation.Upgrade("six"), state.running)
        finishRun("Successfully installed six-1.17.0\n")
        runCurrent()
        assertEquals(InstallOutcome.Succeeded(PipOperation.Upgrade("six")), state.outcome)
        assertEquals(2, manager.listCalls)
        assertEquals(listOf("upgrade six"), manager.runs)
    }

    @Test
    fun successfulInstallMakesTheListReloadNextTime() = runTest(dispatcher) {
        manager.listResult.complete(Result.success(emptyList()))
        vm.selectTab(PipTab.Installed)
        runCurrent()
        vm.selectTab(PipTab.Search)
        vm.onSpecChange("six")
        vm.install()
        manager.output.close()
        runCurrent()
        assertEquals(1, manager.listCalls)
        vm.selectTab(PipTab.Installed)
        runCurrent()
        assertEquals(2, manager.listCalls)
    }

    @Test
    fun listFailureIsShown() = runTest(dispatcher) {
        manager.listResult.complete(Result.failure(IllegalStateException("bad output")))
        vm.refresh()
        runCurrent()
        assertEquals("bad output", state.listError)
        assertFalse(state.loading)
    }

    @Test
    fun uninstallAsksThenRemovesAndRefreshes() = runTest(dispatcher) {
        manager.listResult.complete(Result.success(emptyList()))
        val six = PipPackage("six", "1.16.0")
        vm.requestUninstall(six)
        assertEquals(six, state.confirmUninstall)
        assertEquals(emptyList<String>(), manager.uninstalls)
        vm.confirmUninstall()
        assertNull(state.confirmUninstall)
        runCurrent()
        assertEquals(listOf("six"), manager.uninstalls)
        assertEquals(1, manager.listCalls)
        assertNull(state.uninstalling)
        assertEquals(PipNotice.Uninstalled("six"), state.notice)
    }

    @Test
    fun dismissingUninstallDoesNothing() = runTest(dispatcher) {
        vm.requestUninstall(PipPackage("six", "1"))
        vm.dismissUninstall()
        runCurrent()
        assertNull(state.confirmUninstall)
        assertEquals(emptyList<String>(), manager.uninstalls)
    }

    @Test
    fun uninstallFailureIsShown() = runTest(dispatcher) {
        manager.uninstallResult = Result.failure(IllegalStateException("not installed"))
        manager.listResult.complete(Result.success(emptyList()))
        vm.requestUninstall(PipPackage("six", "1"))
        vm.confirmUninstall()
        runCurrent()
        assertEquals("not installed", state.listError)
    }

    @Test
    fun uninstallFromTheDetailsSheetClosesIt() = runTest(dispatcher) {
        val six = PipPackage("six", "1")
        vm.openDetails(six)
        vm.requestUninstall(six)
        assertNull(state.details)
        assertEquals(six, state.confirmUninstall)
    }

    // --- Details ---------------------------------------------------------------------------

    @Test
    fun detailsLoadPipShow() = runTest(dispatcher) {
        val six = PipPackage("six", "1.17.0")
        vm.openDetails(six)
        runCurrent()
        assertEquals(DetailsState(six, loading = true), state.details)
        val d = PackageDetails("six", "1.17.0", "Py2/3", null, "MIT", emptyList(), emptyList())
        manager.showResult.complete(Result.success(d))
        runCurrent()
        assertEquals(DetailsState(six, loading = false, info = d), state.details)
        assertEquals(listOf("six"), manager.shows)
        vm.closeDetails()
        assertNull(state.details)
    }

    @Test
    fun detailsFailureIsShownInTheSheet() = runTest(dispatcher) {
        vm.openDetails(PipPackage("six", "1"))
        manager.showResult.complete(Result.failure(PipFailedException(1, startFailure = "not found")))
        runCurrent()
        assertEquals("not found", state.details!!.error)
        assertFalse(state.details!!.loading)
    }

    @Test
    fun closingDetailsWhileLoadingDropsTheLateResult() = runTest(dispatcher) {
        vm.openDetails(PipPackage("six", "1"))
        runCurrent()
        vm.closeDetails()
        manager.showResult.complete(Result.success(PackageDetails("six", "1", null, null, null, emptyList(), emptyList())))
        runCurrent()
        assertNull(state.details)
    }

    // --- requirements.txt ------------------------------------------------------------------

    @Test
    fun requirementsTabScansForFilesEachTime() = runTest(dispatcher) {
        files = listOf(File("/p/requirements.txt"))
        vm.selectTab(PipTab.Requirements)
        runCurrent()
        assertEquals(files, state.requirementFiles)
        assertFalse(state.scanningFiles)
        vm.selectTab(PipTab.Search)
        vm.selectTab(PipTab.Requirements)
        runCurrent()
        assertEquals(2, scans)
    }

    @Test
    fun installRequirementsRunsAndMarksTheListStale() = runTest(dispatcher) {
        manager.listResult.complete(Result.success(emptyList()))
        vm.selectTab(PipTab.Installed)
        runCurrent()
        vm.selectTab(PipTab.Requirements)
        val req = File("/p/requirements.txt")
        vm.installRequirements(req)
        runCurrent()
        assertEquals(PipOperation.Requirements(req), state.running)
        finishRun()
        runCurrent()
        assertEquals(InstallOutcome.Succeeded(PipOperation.Requirements(req)), state.outcome)
        assertEquals(listOf("requirements ${req.path}"), manager.runs)
        vm.selectTab(PipTab.Installed)
        runCurrent()
        assertEquals(2, manager.listCalls)
    }
}
