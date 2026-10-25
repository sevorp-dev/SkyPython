package dev.skyloft.skypython

import androidx.annotation.StringRes
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** First launch: hello.py opens, Run executes it, input() round-trips through the console. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppEndToEndTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** UI text in the device's app language (the program's own output stays English). */
    private fun str(@StringRes id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    @Test
    fun runHelloPyAndAnswerInput() {
        rule.waitUntilAtLeastOneExists(hasText("hello.py", substring = true), 20_000)

        rule.onNode(hasContentDescription(str(R.string.action_run))).performClick()

        // First run unpacks the stdlib, so allow time.
        rule.waitUntilAtLeastOneExists(hasText("What's your name?", substring = true), 90_000)

        rule.onNode(hasSetTextAction()).performTextInput("Sky")
        rule.onNode(hasContentDescription(str(R.string.action_send))).performClick()

        rule.waitUntilAtLeastOneExists(hasText("Hello, Sky!", substring = true), 20_000)
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.console_exit_footer, 0)), 20_000)
    }
}
