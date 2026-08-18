package se.rise.logline.sensors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

/** A re-encoded copy of a frame at a smaller size. */
data class ScaledJpeg(val jpeg: ByteArray, val width: Int, val height: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScaledJpeg) return false
        return width == other.width && height == other.height && jpeg.contentEquals(other.jpeg)
    }

    override fun hashCode(): Int = (jpeg.contentHashCode() * 31 + width) * 31 + height
}

/**
 * Shrink a JPEG to a target width, preserving its aspect ratio.
 *
 * Two steps, and both are needed. The decode is *sampled* (`inSampleSize`), so the full-size bitmap is
 * never materialised — a 4 MB frame decodes as a fraction of the 8 MB an ARGB_8888 decode of it would
 * cost. `inSampleSize` only halves, though, so the sampled result is the first size at or above the
 * target, and an exact scale finishes the job.
 *
 * Returns null when the bytes will not decode. That is the honest answer: a caller can then fall back
 * to the original frame rather than publish nothing.
 */
fun scaleJpeg(jpeg: ByteArray, targetWidth: Int, quality: Int): ScaledJpeg? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    if (bounds.outWidth <= targetWidth) return ScaledJpeg(jpeg, bounds.outWidth, bounds.outHeight)

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null
    val height = (targetWidth.toLong() * decoded.height / decoded.width).toInt().coerceAtLeast(1)
    val scaled = if (decoded.width == targetWidth) {
        decoded
    } else {
        decoded.scale(targetWidth, height)
    }
    return try {
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        ScaledJpeg(out.toByteArray(), scaled.width, scaled.height)
    } finally {
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
    }
}

/** The live view's copy of a frame: small enough that decoding it on the UI thread is free. */
fun thumbnail(jpeg: ByteArray, maxWidth: Int = DEFAULT_THUMBNAIL_WIDTH, quality: Int = THUMBNAIL_QUALITY): ScaledJpeg? =
    scaleJpeg(jpeg, maxWidth, quality)

/** Wide enough to fill a card on a phone, small enough to decode per frame without thinking about it. */
const val DEFAULT_THUMBNAIL_WIDTH = 320

/** Lower than the published frame's: this one is looked at, not analysed. */
const val THUMBNAIL_QUALITY = 60
