package se.rise.logline.ui

import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.BinaryBitmap

/**
 * A QR code for a short piece of text, black on white.
 *
 * Error correction **M** rather than the default L: a code held up on one phone and read off another's
 * screen is a poor optical path — glare, focus, a hand that will not stay still — and M tolerates a
 * quarter of the code being unreadable for about a third more modules. That trade is worth it here
 * because the payload is small; it would not be if the whole settings file went in.
 *
 * Returns null rather than throwing on a payload too large for a QR at all, so the caller can say so.
 */
fun qrBitmap(text: String, sizePx: Int = 720): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        // One module of quiet zone is the spec's minimum of four scaled down; zxing's default of 4
        // modules at this size wastes a fifth of the image on white.
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    // Row at a time rather than pixel at a time: setPixels once per row is an order of magnitude
    // fewer JNI crossings than setPixel per module, and a 720px code is half a million of them.
    val bitmap = createBitmap(matrix.width, matrix.height)
    val row = IntArray(matrix.width)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            row[x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        bitmap.setPixels(row, 0, matrix.width, 0, y, matrix.width, 1)
    }
    bitmap.asImageBitmap()
}.getOrNull()

/**
 * Read a QR out of one camera frame's luminance plane, or null if there is not one in it.
 *
 * The Y plane of the YUV CameraX delivers *is* a greyscale image, which is exactly what zxing wants —
 * so there is no bitmap, no colour conversion and no allocation per frame beyond the row copy. That
 * matters: this runs on every frame the analyser delivers.
 *
 * Failing to find a code is the normal case, not an error — most frames are of a table.
 */
fun decodeQr(
    luminance: ByteArray,
    width: Int,
    height: Int,
    reader: MultiFormatReader = MultiFormatReader(),
): String? = runCatching {
    val source = PlanarYUVLuminanceSource(
        luminance,
        width,
        height,
        0,
        0,
        width,
        height,
        false,
    )
    reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
}.getOrNull()
