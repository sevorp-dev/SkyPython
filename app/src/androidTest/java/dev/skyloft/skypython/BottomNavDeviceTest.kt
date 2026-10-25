package dev.skyloft.skypython

import androidx.annotation.StringRes
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.skyloft.skypython.editor.EditorViewModel
import dev.skyloft.skypython.settings.SettingsStore
import dev.skyloft.skypython.terminal.TERMINAL_TAG
import dev.skyloft.skypython.ui.nav.BOTTOM_BAR_TAG
import dev.skyloft.skypython.ui.nav.TopLevelDestination
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bottom bar: each item opens its screen, the editor keeps its open tabs across switches,
 * and turning the bar off in Settings removes it (the ☰ drawer stays).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BottomNavDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun str(@StringRes id: Int): String = rule.activity.getString(id)

    private fun setBar(on: Boolean) = runBlocking {
        SettingsStore(rule.activity).update { it.copy(bottomNavBar = on) }
    }

    @Before
    fun barOn() = setBar(true)

    @After
    fun restoreDefault() = setBar(true)

    private fun tap(destination: TopLevelDestination) {
        rule.waitUntilAtLeastOneExists(hasTestTag(destination.tag), 10_000)
        rule.onNode(hasTestTag(destination.tag)).performClick()
        rule.waitForIdle()
    }

    private fun hideKeyboard() {
        // The terminal focuses itself and opens the keyboard; the bar steps aside until it closes.
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            androidx.core.view.WindowCompat.getInsetsController(rule.activity.window, view)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
    }

    private fun openTabs(): Pair<List<String>, String?> {
        val vm = ViewModelProvider(rule.activity)[EditorViewModel::class.java]
        val s = vm.state.value
        return s.docs.map { it.id } to s.currentId
    }

    @Test
    fun eachItemOpensItsScreenAndTheEditorKeepsItsTabs() {
        val menu = hasContentDescription(str(R.string.editor_menu))
        rule.waitUntilAtLeastOneExists(menu, 20_000)
        rule.waitUntilAtLeastOneExists(hasTestTag(BOTTOM_BAR_TAG), 10_000)
        rule.waitUntil(20_000) { openTabs().first.isNotEmpty() }
        val before = openTabs()

        tap(TopLevelDestination.Terminal)
        rule.waitUntilAtLeastOneExists(hasTestTag(TERMINAL_TAG), 20_000)
        hideKeyboard()

        tap(TopLevelDestination.Packages)
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.title_pip)), 10_000)

        tap(TopLevelDestination.Projects)
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.title_files)), 10_000)

        tap(TopLevelDestination.Settings)
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.settings_bottom_nav)), 10_000)

        tap(TopLevelDestination.Editor)
        rule.waitUntilAtLeastOneExists(menu, 10_000)
        assertEquals("editor tabs survive switching", before, openTabs())

        // Back from a tab returns to the editor.
        tap(TopLevelDestination.Settings)
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.settings_bottom_nav)), 10_000)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntilAtLeastOneExists(menu, 10_000)
        assertEquals(before, openTabs())
    }

    @Test
    fun turningItOffInSettingsRemovesTheBar() {
        rule.waitUntilAtLeastOneExists(hasTestTag(BOTTOM_BAR_TAG), 20_000)
        tap(TopLevelDestination.Settings)
        val switch = hasText(str(R.string.settings_bottom_nav))
        rule.waitUntilAtLeastOneExists(switch, 10_000)
        rule.onNode(switch).performScrollTo().performClick()
        rule.waitUntilDoesNotExist(hasTestTag(BOTTOM_BAR_TAG), 10_000)

        // Off: back to the editor, still no bar, and the ☰ drawer is there as before.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        val menu = hasContentDescription(str(R.string.editor_menu))
        rule.waitUntilAtLeastOneExists(menu, 10_000)
        rule.onNode(menu).performClick()
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.title_settings)), 10_000)
        assertEquals(0, rule.onAllNodes(hasTestTag(BOTTOM_BAR_TAG)).fetchSemanticsNodes().size)
    }
}
