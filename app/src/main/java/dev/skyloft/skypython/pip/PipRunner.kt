package dev.skyloft.skypython.pip

import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.PythonPaths
import dev.skyloft.skypython.runtime.PythonProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Runs `python -m <module> <args>` once; same events as [PythonProcess.start]. */
fun interface PipRunner {
    fun run(module: String, args: List<String>): Flow<ConsoleEvent>
}

/**
 * [PythonProcess] only runs script files, so each call writes a one-off
 * `scriptDir/pip_<n>.py` that sets `sys.argv` and runs the module via `runpy`
 * (see [PipScript]), and deletes it afterwards. cwd is [scriptDir].
 */
class PythonPipRunner(
    private val paths: PythonPaths,
    private val scriptDir: File,
) : PipRunner {

    override fun run(module: String, args: List<String>): Flow<ConsoleEvent> = flow {
        val script = withContext(Dispatchers.IO) {
            scriptDir.mkdirs()
            File(scriptDir, "pip_${counter.incrementAndGet()}.py").apply {
                writeText(PipScript.source(module, args))
            }
        }
        try {
            emitAll(PythonProcess(paths).start(script))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { script.delete() }
        }
    }

    private companion object {
        val counter = AtomicInteger()
    }
}
