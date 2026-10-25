package dev.skyloft.skypython.viewer

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import dev.skyloft.skypython.report.AppLog
import java.io.File
import java.io.IOException
import kotlin.math.max

/** An image file read for the viewer. */
sealed interface LoadedImage {
    /** [bitmap] may be smaller than the file's [width]×[height] (decoded downsampled). */
    data class Ok(val bitmap: Bitmap, val width: Int, val height: Int, val bytes: Long) : LoadedImage

    data class Failed(val reason: Reason, val detail: String? = null) : LoadedImage

    enum class Reason { Missing, Unsupported, TooBig, Unreadable }
}

/**
 * Decodes image files small enough for the device: never more than about twice the screen on a
 * side, and never more than a slice of the app's memory, so a 50-megapixel photo opens on a weak
 * phone (as a lighter copy) instead of crashing. Call off the main thread.
 */
object ImageLoader {
    private const val TAG = "ImageViewer"

    /** No side over this, whatever the screen. */
    private const val MAX_SIDE = 4096

    fun load(context: Context, file: File): LoadedImage {
        if (!file.isFile) return LoadedImage.Failed(LoadedImage.Reason.Missing)
        val metrics = context.resources.displayMetrics
        val maxSide = (max(metrics.widthPixels, metrics.heightPixels) * 2).coerceIn(1024, MAX_SIDE)
        val maxPixels = pixelBudget(context)
        var extra = 1
        // Out of memory anyway (other screens hold a lot): try again at half the size, twice.
        repeat(3) {
            try {
                return decode(file, maxSide / extra, maxPixels / (extra * extra))
            } catch (e: OutOfMemoryError) {
                AppLog.w(TAG, "out of memory decoding ${file.name} at 1/$extra", e)
                extra *= 2
            } catch (e: IOException) {
                return LoadedImage.Failed(LoadedImage.Reason.Unreadable, e.message)
            } catch (e: SecurityException) {
                return LoadedImage.Failed(LoadedImage.Reason.Unreadable, e.message)
            }
        }
        return LoadedImage.Failed(LoadedImage.Reason.TooBig)
    }

    /** Pixels (4 bytes each) the decoded picture may take: an eighth of the app's heap, at most 16 M. */
    private fun pixelBudget(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val heapMb = (am?.memoryClass ?: 64).toLong()
        return (heapMb * 1024 * 1024 / 4 / 8).coerceIn(1_000_000L, 16_000_000L)
    }

    private fun decode(file: File, maxSide: Int, maxPixels: Long): LoadedImage {
        val bytes = file.length()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var width = 0
            var height = 0
            val bitmap = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    width = info.size.width
                    height = info.size.height
                    decoder.setTargetSampleSize(ImageMath.sampleSize(width, height, maxSide, maxPixels))
                    // Drawn by the CPU-side canvas and kept while zooming: a plain bitmap.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: ImageDecoder.DecodeException) {
                return LoadedImage.Failed(LoadedImage.Reason.Unsupported, e.message)
            }
            return LoadedImage.Ok(bitmap, width, height, bytes)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return LoadedImage.Failed(LoadedImage.Reason.Unsupported)
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageMath.sampleSize(bounds.outWidth, bounds.outHeight, maxSide, maxPixels)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options)
            ?: return LoadedImage.Failed(LoadedImage.Reason.Unsupported)
        return LoadedImage.Ok(bitmap, bounds.outWidth, bounds.outHeight, bytes)
    }
}
