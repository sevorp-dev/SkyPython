package dev.skyloft.skypython.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageSpecTest {

    private fun valid(input: String) = PackageSpec.check(input) as? SpecCheck.Valid

    @Test
    fun acceptsCommonForms() {
        for (s in listOf(
            "six", "requests", "python-dateutil", "zope.interface", "my_pkg", "A1",
            "name==1.2", "name>=1", "name<=2.0", "name~=1.4", "name!=1.0", "name<3", "name>2",
            "name===1.0", "name==1.*", "name>=1,<2", "name[x]", "requests[socks,security]>=2.0",
            "pkg==1.0rc1", "pkg==1.0.post1+local.2",
        )) {
            assertEquals(s, SpecCheck.Valid(s), PackageSpec.check(s))
        }
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(SpecCheck.Valid("six"), PackageSpec.check("  six \n"))
    }

    @Test
    fun rejectsEmpty() {
        assertTrue(PackageSpec.check("   ") is SpecCheck.Invalid)
    }

    @Test
    fun rejectsSpacesShellCharsAndOptions() {
        for (s in listOf(
            "six requests", "six;rm -rf /", "six && ls", "six|cat", "\$(id)", "`id`", "six>a b",
            "-r", "--index-url=http://x", "-e.", "six'", "six\"", "../pkg", "/sdcard/x.whl",
            "http://x/y.whl", "name[", "name[]", "name[x", "name==", "name>=1,", ".pkg", "pkg.",
            "name @ http://x", "six\nother",
        )) {
            assertTrue(s, PackageSpec.check(s) is SpecCheck.Invalid)
        }
    }

    @Test
    fun nameOfTakesTheLeadingName() {
        assertEquals("requests", PackageSpec.nameOf(" requests[socks]>=2.0 "))
        assertEquals("zope.interface", PackageSpec.nameOf("zope.interface==5"))
        assertEquals("six", PackageSpec.nameOf("six"))
        assertEquals("", PackageSpec.nameOf("==1"))
        assertEquals("", PackageSpec.nameOf(""))
    }

    @Test
    fun invalidHasAReason() {
        val r = PackageSpec.check("a b") as SpecCheck.Invalid
        assertTrue(r.reason.isNotBlank())
    }

    @Test
    fun isNameAcceptsBareNamesOnly() {
        assertTrue(PackageSpec.isName("python-dateutil"))
        assertFalse(PackageSpec.isName("six==1"))
        assertFalse(PackageSpec.isName("-y"))
        assertFalse(PackageSpec.isName(""))
        assertTrue(valid("six") != null)
    }
}
