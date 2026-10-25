package dev.skyloft.skypython.pip

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RequirementsFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(path: String) = File(tmp.root, path).apply { parentFile!!.mkdirs(); writeText("six\n") }

    private fun found(root: File = tmp.root, limit: Int = 100) =
        RequirementsFiles.find(root, limit = limit).map { it.relativeTo(tmp.root).invariantSeparatorsPath }

    @Test
    fun requirementsFilesComeFirstThenOtherTextFiles() {
        touch("notes.txt")
        touch("app/requirements.txt")
        touch("requirements-dev.txt")
        touch("main.py")
        touch("data/Readme.TXT")
        assertEquals(
            listOf("requirements-dev.txt", "app/requirements.txt", "notes.txt", "data/Readme.TXT"),
            found(),
        )
    }

    @Test
    fun skipsHiddenAndToolDirectories() {
        touch(".git/requirements.txt")
        touch("venv/requirements.txt")
        touch("__pycache__/requirements.txt")
        touch("pkg/site-packages/x/requirements.txt")
        touch("ok/requirements.txt")
        assertEquals(listOf("ok/requirements.txt"), found())
    }

    @Test
    fun depthAndCountAreLimited() {
        touch("a/b/c/d/e/f/requirements.txt")
        for (i in 1..5) touch("r$i.txt")
        assertEquals(3, found(limit = 3).size)
        assertEquals(false, found().any { it.startsWith("a/") })
    }

    @Test
    fun missingRootIsEmpty() {
        assertEquals(emptyList<File>(), RequirementsFiles.find(File(tmp.root, "nope")))
    }

    @Test
    fun candidateIsATxtFile() {
        assertEquals(true, RequirementsFiles.isCandidate(touch("x/requirements.txt")))
        assertEquals(false, RequirementsFiles.isCandidate(touch("x/main.py")))
        assertEquals(false, RequirementsFiles.isCandidate(File(tmp.root, "missing.txt")))
    }
}
