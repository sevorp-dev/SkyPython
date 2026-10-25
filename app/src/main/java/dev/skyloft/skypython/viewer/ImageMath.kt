package dev.skyloft.skypython.viewer

import kotlin.math.max
import kotlin.math.min

/**
 * The viewer's arithmetic, kept apart from Android: how much to downsample on decode, and how
 * zoom and pan stay sensible. Pans are measured from the centre of the view.
 */
object ImageMath {
    /** The zoom a double tap goes to from fit-to-screen. */
    const val DOUBLE_TAP_ZOOM = 2.5f

    /** The most the image can be zoomed in, over fit-to-screen. */
    const val MAX_ZOOM = 8f

    /**
     * The `inSampleSize` (a power of two) that brings a [width]×[height] picture within
     * [maxSide] on each side and [maxPixels] in all.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int, maxPixels: Long): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (true) {
            val w = width / sample
            val h = height / sample
            if ((w <= maxSide && h <= maxSide && w.toLong() * h <= maxPixels) || sample >= 1 shl 20) return sample
            sample *= 2
        }
    }

    /** The scale that shows the whole [width]×[height] picture in the view. */
    fun fitScale(width: Float, height: Float, viewWidth: Float, viewHeight: Float): Float =
        if (width <= 0f || height <= 0f) 1f else min(viewWidth / width, viewHeight / height)

    /** [pan] kept so that the picture ([content] long when fitted, times [zoom]) covers the [view] edge to edge. */
    fun clampPan(pan: Float, content: Float, zoom: Float, view: Float): Float {
        val limit = max(0f, (content * zoom - view) / 2f)
        return pan.coerceIn(-limit, limit)
    }

    /** The pan that keeps the point under [focus] (from the view's centre) in place when zooming. */
    fun panAfterZoom(pan: Float, focus: Float, oldZoom: Float, newZoom: Float): Float =
        focus - (focus - pan) * (newZoom / oldZoom)

    /** Double tap: zoom in from fit-to-screen, back to fit from anything else. */
    fun doubleTapZoom(zoom: Float): Float = if (zoom <= 1.01f) DOUBLE_TAP_ZOOM else 1f
}
