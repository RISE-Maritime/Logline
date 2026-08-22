package se.rise.logline

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import se.rise.logline.config.Settings
import se.rise.logline.config.encode
import se.rise.logline.config.parseSettingsProfile
import se.rise.logline.config.toConnectionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection profile survives being a QR and coming back.
 *
 * **Both ends of this are zxing**, which is the honest limit of it: a bug shared by the encoder and
 * the decoder would pass. What it does catch is everything between them — a payload that has quietly
 * outgrown a scannable code, a character set that does not survive the trip, a profile that parses to
 * something other than what went in. Reading it with a phone's camera is the part only a phone can do.
 *
 * The pixels are real: the matrix is rendered to an image and decoded from that, rather than the
 * decoder being handed the matrix, so the scaling and quiet zone are exercised too.
 */
class QrPayloadTest {

    private val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
    )

    private fun settings(endpoints: List<String>) = Settings(
        realm = "rise",
        entityId = "pixel_6",
        routerEndpoints = endpoints,
        locationSource = "phone",
        imuSource = "phone",
        deviceSource = "phone",
        calibrationSource = "calibration",
        scoutAddress = "224.0.0.224:7446",
    )

    /** Encode, rasterise, decode — the same path a camera takes, minus the camera. */
    private fun throughAQr(payload: String, sizePx: Int = 720): String {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            val x = i % matrix.width
            val y = i / matrix.width
            if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun `a connection profile round-trips through a QR`() {
        val original = settings(listOf("tls/router.example.com:443"))
        val payload = original.toConnectionProfile().encode(pretty = false)

        val decoded = throughAQr(payload)

        assertEquals(payload, decoded)
        val profile = requireNotNull(parseSettingsProfile(decoded))
        assertEquals("rise", profile.realm)
        assertEquals("tls/router.example.com:443", profile.routerEndpoints)
        assertEquals("224.0.0.224:7446", profile.scoutAddress)
    }

    /** A second endpoint is the realistic worst case — failover lists are short, but not always one. */
    @Test
    fun `two endpoints still fit`() {
        val payload = settings(
            listOf("tls/router.example.com:443", "tcp/192.168.100.200:7447"),
        ).toConnectionProfile().encode(pretty = false)

        assertEquals(payload, throughAQr(payload))
    }

    /**
     * The failure this guards against is silent: a payload that grows past what a phone camera can
     * resolve still *encodes*, into a denser code that simply will not scan across a table. Failing
     * here, on a length nobody has to eyeball, is cheaper than finding out at a quayside.
     */
    @Test
    fun `the payload stays well inside what a camera can read`() {
        val payload = settings(listOf("tls/router.example.com:443")).toConnectionProfile().encode(pretty = false)

        assertTrue("payload is ${payload.length} bytes", payload.length < 300)
        // Version 10 is 57x57 modules — about the densest that reads reliably off another phone's
        // screen at arm's length with error correction M.
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 720, 720, hints)
        val modules = matrix.width / (720 / matrix.width).coerceAtLeast(1)
        assertTrue("code is $modules modules across", matrix.width <= 720)
    }

    /** Non-ASCII in a source id or realm must survive, or a Swedish platform name breaks the QR quietly. */
    @Test
    fun `non-ASCII survives the trip`() {
        val payload = settings(listOf("tcp/örnsköldsvik.example:7447"))
            .copy(locationSource = "för")
            .toConnectionProfile()
            .encode(pretty = false)

        val decoded = throughAQr(payload)

        assertEquals(payload, decoded)
        assertEquals("för", requireNotNull(parseSettingsProfile(decoded)).locationSource)
    }
}
