package se.rise.logline

import keelson.interfaces.ErrorResponseOuterClass.ErrorResponse
import se.rise.logline.platform.setConfigRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone serves `configurable/v1` read-only, and this is the half that makes that legal.
 *
 * §3.6's full-interface rule allows a source to refuse a procedure but not to ignore one: the reply
 * must be *typed*, "never silence". A refusal a caller cannot decode is worth no more than the timeout
 * it replaces — which is precisely what this test exists to stop, since nothing else in the app parses
 * these bytes back.
 */
class ConfigurableRpcTest {

    @Test
    fun `the set_config refusal decodes to a permanent denial`() {
        val decoded = ErrorResponse.parseFrom(setConfigRefusal().toByteArray())

        assertEquals(ErrorResponse.Code.PERMISSION_DENIED, decoded.code)
        assertTrue("a code with no words is half an answer", decoded.errorDescription.isNotBlank())
    }

    /**
     * The description carries what the enum cannot. `ErrorResponse.Code` has no `UNSUPPORTED`, so
     * "this will never work" and "not right now" are the same value on the wire; the only place the
     * distinction survives is the text, and a consumer's operator reads it. If somebody ever softens
     * this wording, the refusal starts reading as retryable.
     */
    @Test
    fun `the description says the refusal is permanent, not transient`() {
        val text = setConfigRefusal().errorDescription.lowercase()

        assertTrue("must say it is permanent", text.contains("permanent"))
        assertTrue("must point at what does work", text.contains("get_config"))
    }
}
