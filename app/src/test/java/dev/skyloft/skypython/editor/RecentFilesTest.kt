package dev.skyloft.skypython.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFilesTest {
    @Test
    fun newestFirstWithoutDuplicates() {
        assertEquals(listOf("/b", "/a"), pushRecent(listOf("/a"), "/b"))
        assertEquals(listOf("/a", "/b"), pushRecent(listOf("/b", "/a"), "/a"))
    }

    @Test
    fun keepsAtMostTen() {
        val ten = (1..10).map { "/$it" }
        val out = pushRecent(ten, "/new")
        assertEquals(10, out.size)
        assertEquals("/new", out.first())
        assertEquals("/9", out.last())
    }

    @Test
    fun forgetRemovesAPath() {
        assertEquals(listOf("/a"), forgetRecent(listOf("/a", "/b"), "/b"))
    }

    @Test
    fun codecRoundTripsAndSkipsBlanks() {
        val list = listOf("/data/a b.py", "/data/x.py")
        assertEquals(list, RecentFilesCodec.decode(RecentFilesCodec.encode(list)))
        assertEquals(emptyList<String>(), RecentFilesCodec.decode(null))
        assertEquals(listOf("/a"), RecentFilesCodec.decode("\n/a\n\n"))
    }
}
