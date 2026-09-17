package se.rise.logline

import keelson.interfaces.ErrorResponseOuterClass.ErrorResponse
import se.rise.logline.platform.setConfigRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    /**
     * **The code must be `UNSUPPORTED`, and `PERMISSION_DENIED` is now a spec violation rather than an
     * approximation.** This app used the latter until `0.6.0-pre.18`, because the enum had no value for
     * a permanent structural refusal and the permanence could only be spelled in the description. That
     * release added one, and rewrote `PERMISSION_DENIED` to mean "refused under current conditions…
     * the answer MAY change" — the opposite of what this refusal means, since a platform's geometry is
     * never writable over the bus by design.
     *
     * §3.6 pairs the codes explicitly and says why the enum alone has to carry it: *"the distinction
     * has to survive in the code, not only in `error_description`, because a UI reading the enum alone
     * decides whether to keep the procedure callable."* A consumer told the answer may change will
     * offer a retry that can never succeed.
     */
    @Test
    fun `the set_config refusal decodes to a permanent, structural refusal`() {
        val decoded = ErrorResponse.parseFrom(setConfigRefusal().toByteArray())

        assertEquals(ErrorResponse.Code.UNSUPPORTED, decoded.code)
        // The conditional twin, named so the failure message says which mistake was made.
        assertNotEquals(
            "PERMISSION_DENIED means the answer may change; this one never will",
            ErrorResponse.Code.PERMISSION_DENIED,
            decoded.code,
        )
        assertTrue("a code with no words is half an answer", decoded.errorDescription.isNotBlank())
    }

    /**
     * The description says it too, and still should.
     *
     * Not because the enum cannot — it can now — but because the two are read by different things. A
     * UI branches on the code; a person reads the sentence, and "UNSUPPORTED" on its own does not say
     * what to do instead. §3.6 asks for the distinction in the code *as well as* the text, not
     * instead of it, so softening this wording is still a regression.
     */
    @Test
    fun `the description says the refusal is permanent, not transient`() {
        val text = setConfigRefusal().errorDescription.lowercase()

        assertTrue("must say it is permanent", text.contains("permanent"))
        assertTrue("must point at what does work", text.contains("get_config"))
    }
}
