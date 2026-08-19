package se.rise.logline

import keelson.Primitives.TimestampedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.rise.logline.keelson.enclose
import se.rise.logline.platform.decodeConfigurationJson

/**
 * One document, two wire shapes, and both are real.
 *
 * On pubsub `configuration_json` is an enveloped `keelson.TimestampedString` — what this app and
 * keelson's `make_configurable` publish. As a `configurable/v1 get_config` reply it is raw JSON bytes
 * (`op.reply_ok(json.dumps(...).encode())`). Crowsnest carries the same fork in its worker. Anything
 * reading these documents needs both paths, or it silently finds nothing on half the sources.
 */
class ConfigurationJsonDecodeTest {

    private val document = """{ "name": "SSRS18", "frame_transforms": [] }"""

    @Test
    fun `an enveloped TimestampedString yields the document`() {
        val payload = TimestampedString.newBuilder().setValue(document).build().toByteArray()

        assertEquals(document, decodeConfigurationJson(enclose(payload)))
    }

    @Test
    fun `raw JSON bytes yield the document too`() {
        assertEquals(document, decodeConfigurationJson(document.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Anything else is nothing, rather than a string of decoded rubbish.
     *
     * A neighbour publishing something unexpected on a wildcard-matched key is a reason to skip a
     * message, never a reason to report a platform that is not there.
     */
    @Test
    fun `bytes that are neither shape decode to nothing`() {
        assertNull(decodeConfigurationJson(ByteArray(0)))
        assertNull(decodeConfigurationJson("not json at all".toByteArray()))
        // An envelope carrying an empty document is not a document.
        val empty = TimestampedString.newBuilder().setValue("").build().toByteArray()
        assertNull(decodeConfigurationJson(enclose(empty)))
    }
}
