package dev.skyloft.skypython.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageMathTest {

    @Test
    fun smallImagesAreNotSampled() {
        assertEquals(1, ImageMath.sampleSize(800, 600, maxSide = 2048, maxPixels = 4_000_000))
        assertEquals(1, ImageMath.sampleSize(2048, 2048, maxSide = 2048, maxPixels = 4_194_304))
    }

    @Test
    fun bigImagesAreHalvedUntilTheyFit() {
        assertEquals(2, ImageMath.sampleSize(4000, 3000, maxSide = 2048, maxPixels = 100_000_000))
        assertEquals(4, ImageMath.sampleSize(8000, 100, maxSide = 2048, maxPixels = 100_000_000))
        // Fits the side, not the pixel budget.
        assertEquals(2, ImageMath.sampleSize(2000, 2000, maxSide = 4096, maxPixels = 2_000_000))
    }

    @Test
    fun hugeImagesStillGetASize() {
        assertEquals(32, ImageMath.sampleSize(60000, 60000, maxSide = 2048, maxPixels = 4_000_000))
    }

    @Test
    fun badDimensionsGiveOne() {
        assertEquals(1, ImageMath.sampleSize(-1, -1, maxSide = 2048, maxPixels = 4_000_000))
        assertEquals(1, ImageMath.sampleSize(0, 10, maxSide = 2048, maxPixels = 4_000_000))
    }

    @Test
    fun fitScaleFitsTheWholeImage() {
        assertEquals(0.5f, ImageMath.fitScale(2000f, 1000f, 1000f, 1000f), 1e-4f)
        assertEquals(2f, ImageMath.fitScale(100f, 200f, 1000f, 400f), 1e-4f)
    }

    @Test
    fun panIsZeroWhenTheImageIsSmallerThanTheView() {
        // Content 500 wide at zoom 1 in a 1000 view: centred, no pan.
        assertEquals(0f, ImageMath.clampPan(300f, content = 500f, zoom = 1f, view = 1000f), 1e-4f)
    }

    @Test
    fun panStopsAtTheEdges() {
        // Content 1000 at zoom 3 = 3000 in a 1000 view: pan up to ±1000.
        assertEquals(1000f, ImageMath.clampPan(5000f, content = 1000f, zoom = 3f, view = 1000f), 1e-4f)
        assertEquals(-1000f, ImageMath.clampPan(-5000f, content = 1000f, zoom = 3f, view = 1000f), 1e-4f)
        assertEquals(200f, ImageMath.clampPan(200f, content = 1000f, zoom = 3f, view = 1000f), 1e-4f)
    }

    @Test
    fun zoomAroundAPointKeepsThatPointStill() {
        // Point 100 px right of centre, no pan, zoom 1 → 2: the pan moves it back under the finger.
        assertEquals(-100f, ImageMath.panAfterZoom(pan = 0f, focus = 100f, oldZoom = 1f, newZoom = 2f), 1e-4f)
        assertEquals(0f, ImageMath.panAfterZoom(pan = 0f, focus = 0f, oldZoom = 1f, newZoom = 2f), 1e-4f)
        // Already panned: the point under the finger stays under it.
        assertEquals(-100f, ImageMath.panAfterZoom(pan = 50f, focus = 200f, oldZoom = 1f, newZoom = 2f), 1e-4f)
    }

    @Test
    fun doubleTapTogglesBetweenFitAndZoomedIn() {
        assertEquals(ImageMath.DOUBLE_TAP_ZOOM, ImageMath.doubleTapZoom(1f), 1e-4f)
        assertEquals(1f, ImageMath.doubleTapZoom(1.5f), 1e-4f)
        assertEquals(1f, ImageMath.doubleTapZoom(ImageMath.DOUBLE_TAP_ZOOM), 1e-4f)
    }
}
