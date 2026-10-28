package dev.skyloft.skypython.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissingModuleTest {

    @Test
    fun findsTheModuleInATraceback() {
        val tb = """
            Traceback (most recent call last):
              File "/data/user/0/dev.skyloft.skypython/files/projects/main.py", line 1, in <module>
                import requests
            ModuleNotFoundError: No module named 'requests'
        """.trimIndent()
        assertEquals("requests", missingModuleFrom(tb))
    }

    @Test
    fun dottedNamesGiveTheTopLevelPackage() {
        assertEquals("google", missingModuleFrom("ModuleNotFoundError: No module named 'google.protobuf'"))
        assertEquals("PIL", missingModuleFrom("ModuleNotFoundError: No module named 'PIL.Image'"))
    }

    @Test
    fun theLastErrorWins() {
        val chained = """
            ModuleNotFoundError: No module named 'simplejson'

            During handling of the above exception, another exception occurred:

            ModuleNotFoundError: No module named 'yaml'
        """.trimIndent()
        assertEquals("yaml", missingModuleFrom(chained))
    }

    @Test
    fun noMatchIsNull() {
        assertNull(missingModuleFrom(""))
        assertNull(missingModuleFrom("NameError: name 'x' is not defined"))
        assertNull(missingModuleFrom("ImportError: cannot import name 'foo' from 'bar'"))
        // Private/C modules of the interpreter itself can't be fixed with pip.
        assertNull(missingModuleFrom("ModuleNotFoundError: No module named '_tkinter'"))
        // Relative imports inside a project aren't packages either.
        assertNull(missingModuleFrom("ModuleNotFoundError: No module named '.helpers'"))
    }

    @Test
    fun alsoAcceptsTheModuleWithWindowsOrMacLineEndingsAndTrailingText() {
        assertEquals("rich", missingModuleFrom("ModuleNotFoundError: No module named 'rich'\r\n"))
        assertEquals("tqdm", missingModuleFrom("x\nModuleNotFoundError: No module named 'tqdm'; 'x' is not a package"))
    }

    @Test
    fun knownModulesMapToTheirPipNames() {
        val expected = mapOf(
            "cv2" to "opencv-python",
            "PIL" to "pillow",
            "yaml" to "pyyaml",
            "bs4" to "beautifulsoup4",
            "sklearn" to "scikit-learn",
            "dateutil" to "python-dateutil",
            "serial" to "pyserial",
            "Crypto" to "pycryptodome",
            "attr" to "attrs",
            "dotenv" to "python-dotenv",
            "jwt" to "PyJWT",
            "OpenSSL" to "pyOpenSSL",
            "telegram" to "python-telegram-bot",
            "discord" to "discord.py",
            "skimage" to "scikit-image",
            "docx" to "python-docx",
            "fitz" to "PyMuPDF",
            "magic" to "python-magic",
            "usb" to "pyusb",
            "win32api" to "pywin32",
            "mpl_toolkits" to "matplotlib",
            "mpl_toolkits.mplot3d" to "matplotlib",
            "pylab" to "matplotlib",
            "matplotlib" to "matplotlib",
        )
        for ((module, pip) in expected) assertEquals(module, pip, pipNameForModule(module))
    }

    @Test
    fun unknownModulesKeepTheirName() {
        assertEquals("requests", pipNameForModule("requests"))
        assertEquals("rich", pipNameForModule("rich.console"))
    }

    @Test
    fun everyMappedNameIsInstallable() {
        for (pip in MODULE_TO_PIP.values) assertEquals(pip, true, PackageSpec.isName(pip))
    }

    private val traceback = """
        Traceback (most recent call last):
          File "/data/user/0/dev.skyloft.skypython/files/projects/cam.py", line 1, in <module>
            import cv2
        ModuleNotFoundError: No module named 'cv2'
    """.trimIndent()

    @Test
    fun aFailedRunMissingAModuleOffersItsPipPackage() {
        assertEquals(MissingPackage("cv2", "opencv-python"), MissingPackage.afterRun(exitCode = 1, output = traceback))
        assertEquals(
            MissingPackage("requests", "requests"),
            MissingPackage.afterRun(1, "ModuleNotFoundError: No module named 'requests'"),
        )
    }

    @Test
    fun nothingIsOfferedWhenTheRunSucceededOrFailedOtherwise() {
        assertNull(MissingPackage.afterRun(exitCode = 0, output = traceback)) // caught and printed
        assertNull(MissingPackage.afterRun(exitCode = 1, output = "ValueError: boom"))
        assertNull(MissingPackage.afterRun(exitCode = null, output = traceback)) // still running
    }

    @Test
    fun googleIsANamespaceNotAPackage() {
        // `import google.protobuf` and `import google.cloud.x` miss different packages, and the
        // PyPI project called "google" is neither: no guess.
        assertEquals("google", pipNameForModule("google"))
        assertNull(MissingPackage.afterRun(1, "ModuleNotFoundError: No module named 'google.protobuf'"))
        assertNull(MissingPackage.afterRun(1, "ModuleNotFoundError: No module named 'google'"))
    }
}
