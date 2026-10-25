package dev.skyloft.skypython.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomNavPolicyTest {
    private val topLevel = listOf("editor", "terminal", "pip", "files", "settings")
    private val secondary = listOf("run", "interpreter", "console", "samples", "about")

    @Test
    fun barOrderAndRoutesMatchTheBanner() {
        // Editor · Terminal · Packages · Projects · Settings, each on its existing screen.
        assertEquals(topLevel, TopLevelDestination.entries.map { it.route })
        assertEquals(
            listOf("Editor", "Terminal", "Packages", "Projects", "Settings"),
            TopLevelDestination.entries.map { it.name },
        )
    }

    @Test
    fun routesMapToDestinations() {
        assertEquals(TopLevelDestination.Editor, TopLevelDestination.fromRoute("editor"))
        assertEquals(TopLevelDestination.Packages, TopLevelDestination.fromRoute("pip"))
        assertEquals(TopLevelDestination.Projects, TopLevelDestination.fromRoute("files"))
        secondary.forEach { assertNull(it, TopLevelDestination.fromRoute(it)) }
        assertNull(TopLevelDestination.fromRoute(null))
    }

    @Test
    fun showsOnTheFiveTopLevelScreens() {
        topLevel.forEach { assertTrue(it, BottomNavPolicy.isVisible(enabled = true, route = it, imeVisible = false)) }
    }

    @Test
    fun hiddenOnSecondaryScreens() {
        secondary.forEach { assertFalse(it, BottomNavPolicy.isVisible(enabled = true, route = it, imeVisible = false)) }
        assertFalse(BottomNavPolicy.isVisible(enabled = true, route = null, imeVisible = false))
    }

    @Test
    fun settingOffHidesItEverywhere() {
        (topLevel + secondary).forEach {
            assertFalse(it, BottomNavPolicy.isVisible(enabled = false, route = it, imeVisible = false))
        }
    }

    @Test
    fun hiddenWhileTheKeyboardIsUp() {
        topLevel.forEach { assertFalse(it, BottomNavPolicy.isVisible(enabled = true, route = it, imeVisible = true)) }
    }

    @Test
    fun hiddenWhileTheDrawerIsOpen() {
        assertFalse(BottomNavPolicy.isVisible(enabled = true, route = "editor", imeVisible = false, drawerOpen = true))
    }

    @Test
    fun debuggerPanelHidesItOnTheEditorOnly() {
        assertFalse(BottomNavPolicy.isVisible(enabled = true, route = "editor", imeVisible = false, debugPanelOpen = true))
        // A debug session in the background doesn't take the bar from the other tabs.
        assertTrue(BottomNavPolicy.isVisible(enabled = true, route = "terminal", imeVisible = false, debugPanelOpen = true))
    }

    @Test
    fun labelsOnlyWhenTheWindowIsTallEnough() {
        assertTrue(BottomNavPolicy.showLabels(640)) // 720x1280 @ 320dpi
        assertTrue(BottomNavPolicy.showLabels(BottomNavPolicy.LABELS_MIN_HEIGHT_DP))
        assertFalse(BottomNavPolicy.showLabels(BottomNavPolicy.LABELS_MIN_HEIGHT_DP - 1))
        assertFalse(BottomNavPolicy.showLabels(360)) // a phone in landscape
    }

    @Test
    fun testTagsAreDistinct() {
        assertEquals(5, TopLevelDestination.entries.map { it.tag }.toSet().size)
    }
}
