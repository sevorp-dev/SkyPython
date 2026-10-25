package dev.skyloft.skypython.pip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class PyPiClientTest {

    private val urls = mutableListOf<String>()

    private fun client(reply: (String) -> HttpReply) =
        PyPiClient(baseUrl = "https://pypi.example/pypi", io = Dispatchers.Unconfined) { url ->
            urls += url
            reply(url)
        }

    private val sixJson = """{"info": {"name": "six", "version": "1.17.0", "summary": "Py2/3"},
        "urls": [{"filename": "six-1.17.0-py2.py3-none-any.whl", "packagetype": "bdist_wheel"}]}"""

    @Test
    fun foundUsesTheNormalizedNameInTheUrl() = runTest {
        val c = client { HttpReply(200, PyPiJson.parse(sixJson)) }
        val result = c.lookup("Six")
        assertEquals("https://pypi.example/pypi/six/json", urls.single())
        val project = (result as LookupResult.Found).project
        assertEquals("1.17.0", project.version)
        assertEquals(WheelSupport.Pure, project.wheel)
    }

    @Test
    fun notFoundOn404() = runTest {
        assertEquals(LookupResult.NotFound("nosuch"), client { HttpReply(404) }.lookup("nosuch"))
    }

    @Test
    fun otherHttpCodesFail() = runTest {
        val r = client { HttpReply(503) }.lookup("six")
        assertTrue(r.toString(), r is LookupResult.Failed && "503" in r.message)
    }

    @Test
    fun badBodyFails() = runTest {
        assertTrue(client { HttpReply(200, null) }.lookup("six") is LookupResult.Failed)
    }

    @Test
    fun networkProblemsAreOffline() = runTest {
        for (e in listOf(UnknownHostException("pypi.org"), ConnectException("refused"), SocketTimeoutException("slow"))) {
            val r = client { throw e }.lookup("six")
            assertTrue("$e -> $r", r is LookupResult.Offline)
        }
    }

    @Test
    fun otherIoErrorsFail() = runTest {
        val r = client { throw IOException("reset") }.lookup("six")
        assertEquals(LookupResult.Failed("reset"), r)
    }

    @Test
    fun invalidNamesNeverHitTheNetwork() = runTest {
        val r = client { error("no call expected") }.lookup("../etc")
        assertTrue(r is LookupResult.NotFound)
        assertEquals(emptyList<String>(), urls)
    }
}
