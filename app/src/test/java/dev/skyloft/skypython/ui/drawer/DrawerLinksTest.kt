package dev.skyloft.skypython.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerLinksTest {

    @Test fun `footer links point at the studio channel, the repository and the developer`() {
        assertEquals("https://t.me/SkyLoft_Studio", DrawerLinks.TELEGRAM)
        assertEquals("https://github.com/sevorp-dev/SkyPython", DrawerLinks.GITHUB)
        assertEquals("https://github.com/sevorp-dev", DrawerLinks.DEVELOPER)
    }

    @Test fun `every footer link is a distinct https url`() {
        assertEquals(3, DrawerLinks.ALL.toSet().size)
        DrawerLinks.ALL.forEach { assertTrue(it, it.startsWith("https://")) }
    }
}
