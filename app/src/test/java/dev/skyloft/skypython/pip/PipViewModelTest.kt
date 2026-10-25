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

@OptIn(ExperimentalCoroutinesApi::class)
class PipViewModelTest {

    private class FakeManager : PipManager {
        val installs = mutableListOf<String>()
        var installOutput = Channel<String>(Channel.UNLIMITED)
        val uninstalls = mutableListOf<String>()
        var uninstallResult: Result<Unit> = Result.success(Unit)
        var listCalls = 0
        var listResult = CompletableDeferred<Result<List<PipPackage>>>()

        override fun install(spec: String): Flow<String> {
            installs += spec
            return installOutput.receiveAsFlow()
        }

        override suspend fun uninstall(name: String): Result<Unit> {
            uninstalls += name
            return uninstallResult
        }

        override suspend fun listInstalled(): Result<List<PipPackage>> {
            listCalls++
            return listResult.await()
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val manager = FakeManager()
    private lateinit var vm: PipViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = PipViewModel(manager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val state get() = vm.state.value

    @Test
    fun invalidSpecShowsErrorAndRunsNothing() = runTest(dispatcher) {
        vm.onSpecChange("six; ls")
        vm.install()
        runCurrent()
        assertNotNull(state.specError)
        assertFalse(state.installing)
        assertEquals(emptyList<String>(), manager.installs)
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
        assertEquals(listOf("six"), manager.installs)
        manager.installOutput.send("Collecting six\n")
        runCurrent()
        assertTrue(state.log.contains("Collecting six\n"))
        manager.installOutput.send("Successfully installed six\n")
        manager.installOutput.close()
        runCurrent()
        assertFalse(state.installing)
        assertEquals(InstallOutcome.Succeeded("six"), state.outcome)
    }

    @Test
    fun secondInstallWhileRunningIsIgnored() = runTest(dispatcher) {
        vm.onSpecChange("six")
        vm.install()
        runCurrent()
        vm.install()
        runCurrent()
        assertEquals(listOf("six"), manager.installs)
    }

    @Test
    fun newInstallClearsThePreviousLog() = runTest(dispatcher) {
        vm.onSpecChange("six")
        vm.install()
        manager.installOutput.send("old\n")
        manager.installOutput.close()
        runCurrent()
        manager.installOutput = Channel(Channel.UNLIMITED)
        vm.install()
        runCurrent()
        assertFalse(state.log.contains("old"))
        assertNull(state.outcome)
    }

    @Test
    fun buildFailureShowsThePurePythonHint() = runTest(dispatcher) {
        vm.onSpecChange("numpy")
        vm.install()
        manager.installOutput.send("ERROR: Failed building wheel for numpy\n")
        manager.installOutput.close(PipFailedException(1, "pip exited with code 1"))
        runCurrent()
        val outcome = state.outcome as InstallOutcome.Failed
        assertTrue(outcome.buildFailure)
        assertTrue(state.log.contains("pip exited with code 1"))
    }

    @Test
    fun otherFailureHasNoHint() = runTest(dispatcher) {
        vm.onSpecChange("nosuchpkg")
        vm.install()
        manager.installOutput.send("ERROR: No matching distribution found for nosuchpkg\n")
        manager.installOutput.close(PipFailedException(1, "pip exited with code 1"))
        runCurrent()
        assertFalse((state.outcome as InstallOutcome.Failed).buildFailure)
    }

    @Test
    fun openingInstalledTabLoadsTheListOnce() = runTest(dispatcher) {
        vm.selectTab(PipTab.Installed)
        runCurrent()
        assertTrue(state.loading)
        manager.listResult.complete(Result.success(listOf(PipPackage("six", "1.16.0"))))
        runCurrent()
        assertFalse(state.loading)
        assertEquals(listOf(PipPackage("six", "1.16.0")), state.packages)
        vm.selectTab(PipTab.Install)
        vm.selectTab(PipTab.Installed)
        runCurrent()
        assertEquals(1, manager.listCalls)
    }

    @Test
    fun successfulInstallMakesTheListReloadNextTime() = runTest(dispatcher) {
        manager.listResult.complete(Result.success(emptyList()))
        vm.selectTab(PipTab.Installed)
        runCurrent()
        vm.selectTab(PipTab.Install)
        vm.onSpecChange("six")
        vm.install()
        manager.installOutput.close()
        runCurrent()
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
    fun logIsCappedKeepingTheEnd() = runTest(dispatcher) {
        vm.onSpecChange("six")
        vm.install()
        val chunk = "x".repeat(1000) + "\n"
        repeat(PipViewModel.MAX_LOG_CHARS / 1000 + 50) { manager.installOutput.send(chunk) }
        manager.installOutput.send("THE END\n")
        manager.installOutput.close()
        runCurrent()
        assertTrue(state.log.length <= PipViewModel.MAX_LOG_CHARS)
        assertTrue(state.log.contains("THE END"))
    }
}
