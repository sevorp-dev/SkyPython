package dev.skyloft.skypython.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipListTest {

    @Test
    fun parsesPipListJson() {
        val out = """[{"name": "six", "version": "1.16.0"}, {"name": "Requests", "version": "2.32.3"}]"""
        assertEquals(
            listOf(PipPackage("Requests", "2.32.3"), PipPackage("six", "1.16.0")),
            PipList.parse(out),
        )
    }

    @Test
    fun emptyListAndSurroundingNoise() {
        assertEquals(emptyList<PipPackage>(), PipList.parse("[]\n"))
        assertEquals(
            listOf(PipPackage("a", "1")),
            PipList.parse("WARNING: something\n[{\"name\":\"a\",\"version\":\"1\"}]\r\n"),
        )
    }

    @Test
    fun extraFieldsAndEscapesAreHandled() {
        val out = """[{"name": "a\"bé", "version": "1.0", "editable_project_location": null,
            "n": 1.5e3, "flags": [true, false, {}]}]"""
        assertEquals(listOf(PipPackage("a\"bé", "1.0")), PipList.parse(out))
    }

    @Test
    fun entriesWithoutNameOrVersionAreSkipped() {
        val out = """[{"name": "a"}, {"version": "1"}, {"name": "b", "version": "2"}, 3]"""
        assertEquals(listOf(PipPackage("b", "2")), PipList.parse(out))
    }

    @Test
    fun malformedOutputReturnsNull() {
        for (s in listOf(
            "", "garbage", "[", "[{\"name\": \"a\", \"version\": \"1\"}", "{\"name\": \"a\"}",
            "[{\"name\": \"a\" \"version\": \"1\"}]", "[{\"name\": \"a\\q\"}]", "[1,]", "[\"abc]",
        )) {
            assertNull(s, PipList.parse(s))
        }
    }

    @Test
    fun outdatedMapsNormalizedNamesToTheLatestVersion() {
        val out = "[notice] x\n" + """[{"name": "Python_DateUtil", "version": "2.8", "latest_version": "2.9",
            "latest_filetype": "wheel"}, {"name": "six", "version": "1"}, {"name": "pip", "version": "25", "latest_version": "26"}]"""
        assertEquals(mapOf("python-dateutil" to "2.9", "pip" to "26"), PipList.parseOutdated(out))
        assertEquals(emptyMap<String, String>(), PipList.parseOutdated("[]"))
        assertNull(PipList.parseOutdated("nope"))
    }
}
