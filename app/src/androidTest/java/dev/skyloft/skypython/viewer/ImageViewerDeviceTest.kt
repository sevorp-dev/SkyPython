package dev.skyloft.skypython.viewer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.R
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.ui.theme.SkyPythonTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The image viewer on a real device: decodes a PNG, shows its size, zooms, deletes, explains failures. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ImageViewerDeviceTest {

    @get:Rule val rule = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val dir = File(app.filesDir, "projects/viewer-test")

    @Before
    fun setUp() {
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun png(name: String, w: Int, h: Int): File {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) for (y in 0 until h) bitmap.setPixel(x, y, if (x < w / 2) Color.RED else Color.TRANSPARENT)
        return File(dir, name).apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun show(file: File, onDeleted: (File) -> Unit = {}) {
        rule.setContent {
            SkyPythonTheme { ImageViewerScreen(file, onBack = {}, onDeleted = onDeleted) }
        }
    }

    @Test
    fun showsThePictureWithItsDimensions() {
        val file = png("plot.png", 64, 48)
        show(file)
        rule.waitUntilAtLeastOneExists(hasTestTag(IMAGE_VIEWER_TAG) and hasContentDescription("plot.png"), 10_000)
        rule.onNodeWithText("plot.png").assertIsDisplayed()
        rule.waitUntilAtLeastOneExists(hasText("64 × 48", substring = true), 5_000)
        // Double tap zooms in (the fit button appears), the button fits it back.
        val fit = AppLocale.string(app, R.string.viewer_fit)
        rule.onNodeWithTag(IMAGE_VIEWER_TAG).performTouchInput { doubleClick(center) }
        rule.waitUntilAtLeastOneExists(hasContentDescription(fit), 5_000)
        rule.onNodeWithContentDescription(fit).performClick()
        rule.waitUntilDoesNotExist(hasContentDescription(fit), 5_000)
    }

    @Test
    fun aBrokenFileExplainsItself() {
        val file = File(dir, "broken.png").apply { writeText("not a picture") }
        show(file)
        rule.waitUntilAtLeastOneExists(hasTestTag(IMAGE_VIEWER_ERROR_TAG), 10_000)
        rule.onNodeWithText(AppLocale.string(app, R.string.viewer_error_unsupported)).assertIsDisplayed()
    }

    @Test
    fun deleteAsksThenRemovesTheFile() {
        val file = png("gone.png", 8, 8)
        var deleted: File? = null
        show(file) { deleted = it }
        rule.waitUntilAtLeastOneExists(hasTestTag(IMAGE_VIEWER_TAG), 10_000)
        val delete = AppLocale.string(app, R.string.action_delete)
        rule.onNodeWithContentDescription(delete).performClick()
        rule.waitUntilAtLeastOneExists(hasText(AppLocale.string(app, R.string.files_delete_file_title)), 5_000)
        rule.onNodeWithText(delete).performClick()
        rule.waitUntil(5_000) { deleted != null }
        assertEquals(file, deleted)
        assertFalse(file.exists())
    }
}
