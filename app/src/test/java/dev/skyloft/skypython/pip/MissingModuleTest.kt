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
}
