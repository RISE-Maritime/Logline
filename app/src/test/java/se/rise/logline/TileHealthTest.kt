package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.ui.MapLayer
import se.rise.logline.ui.MapTilerKeyStatus
import se.rise.logline.ui.mapTilerKeyStatusFor
import se.rise.logline.ui.usesMapTiler

/**
 * When the chart is allowed to say a MapTiler key has stopped working.
 *
 * The status mapping is the whole of the judgement, so it is pure and pinned here; the request around
 * it is four lines and needs a network. The codes are not guessed — probed against the service with a
 * real key and a deliberately invalid one, which answer **200** and **403** respectively, the 403
 * carrying "Invalid key" as its body.
 */
class TileHealthTest {

    /** The good case. */
    @Test
    fun `a key the service accepts is ok`() {
        assertEquals(MapTilerKeyStatus.Ok, mapTilerKeyStatusFor(200))
    }

    /**
     * The four ways a key can be alive and still fetch nothing. They are one thing as far as the person
     * holding the phone is concerned, which is why the screen says "not accepted" and does not guess
     * between invalid, expired and over quota.
     */
    @Test
    fun `refused, unpaid and over quota all mean the key will not fetch tiles`() {
        listOf(401, 402, 403, 429).forEach {
            assertEquals("HTTP $it", MapTilerKeyStatus.Rejected, mapTilerKeyStatusFor(it))
        }
    }

    /**
     * **Everything unrecognised is Unknown, and that asymmetry is the safety property.** A 500 from the
     * service, a captive portal's 302, a proxy's 407 — none is evidence about the key, and calling one
     * a refusal sends somebody to replace a key that was fine. Same rule that keeps a phone indoors
     * from being reported as a GNSS fault: only causes that are certain and actionable warn.
     */
    @Test
    fun `an answer that says nothing about the key says nothing`() {
        listOf(0, 204, 302, 404, 407, 418, 500, 502, 503).forEach {
            assertEquals("HTTP $it", MapTilerKeyStatus.Unknown, mapTilerKeyStatusFor(it))
        }
    }

    /**
     * **Satellite counts when a key is set, and that is the point.** It is not a `needsKey` layer,
     * since it falls back to Esri and always draws — but *with* a key it silently upgrades to MapTiler,
     * and it is the default layer, so it is the likeliest place to meet a dead key. Scoping this to
     * `needsKey` would have missed the common case entirely.
     */
    @Test
    fun `satellite is a MapTiler layer only when a key is set`() {
        assertTrue(usesMapTiler(MapLayer.Satellite, hasKey = true))
        assertFalse(usesMapTiler(MapLayer.Satellite, hasKey = false))
    }

    /** Every keyed layer fetches from MapTiler whether or not a key happens to be set. */
    @Test
    fun `the keyed layers always use MapTiler`() {
        MapLayer.entries.filter { it.needsKey }.forEach {
            assertTrue("$it", usesMapTiler(it, hasKey = false))
        }
    }

    /** And OpenStreetMap never does, which is what stops it being blamed for its own 404s past zoom 19. */
    @Test
    fun `OpenStreetMap is never a MapTiler layer`() {
        assertFalse(usesMapTiler(MapLayer.Standard, hasKey = true))
    }
}
