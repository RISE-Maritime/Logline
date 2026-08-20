package se.rise.logline

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.SampleWindow
import se.rise.logline.ui.FixQuality
import se.rise.logline.ui.cardinal
import se.rise.logline.ui.envelope
import se.rise.logline.ui.featuredValue
import se.rise.logline.ui.gnssQuality
import se.rise.logline.ui.cellularQuality
import se.rise.logline.ui.batteryQuality
import se.rise.logline.ui.fixKindQuality
import se.rise.logline.sensors.FixKind
import se.rise.logline.ui.isCircularDegrees
import se.rise.logline.ui.subjectGroups
import se.rise.logline.ui.unwrapAngles
import se.rise.logline.ui.windowRateHz
import se.rise.logline.ui.windowedTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maths behind the live view. Plotting a compass angle as an ordinary number, or slicing a window
 * with an off-by-one, both produce a picture that looks like a sensor fault — so this is the part worth
 * pinning down.
 */
class LiveSignalsTest {

    // -- circular angles -------------------------------------------------------------------------

    /** The case the whole thing exists for: crossing north is a two-degree turn, not a 358° fall. */
    @Test
    fun `crossing north unwraps to a small step`() {
        val unwrapped = unwrapAngles(floatArrayOf(357f, 359f, 1f, 3f))

        assertEquals(357f, unwrapped[0], 1e-3f)
        assertEquals(359f, unwrapped[1], 1e-3f)
        assertEquals(361f, unwrapped[2], 1e-3f)
        assertEquals(363f, unwrapped[3], 1e-3f)
        // Every step small, which is what keeps the sparkline flat-ish through the wrap.
        unwrapped.toList().zipWithNext { a, b -> assertTrue("step ${b - a}", kotlin.math.abs(b - a) <= 180f) }
    }

    @Test
    fun `turning the other way unwraps downwards`() {
        val unwrapped = unwrapAngles(floatArrayOf(3f, 1f, 359f, 357f))

        assertEquals(3f, unwrapped[0], 1e-3f)
        assertEquals(1f, unwrapped[1], 1e-3f)
        assertEquals(-1f, unwrapped[2], 1e-3f)
        assertEquals(-3f, unwrapped[3], 1e-3f)
    }

    @Test
    fun `a series that never wraps is left alone`() {
        val values = floatArrayOf(100f, 110f, 130f, 128f)

        assertArrayEqualsF(values, unwrapAngles(values))
    }

    @Test
    fun `several turns keep accumulating rather than folding back`() {
        val unwrapped = unwrapAngles(floatArrayOf(350f, 10f, 350f, 10f))

        assertEquals(350f, unwrapped[0], 1e-3f)
        assertEquals(370f, unwrapped[1], 1e-3f)
        assertEquals(350f, unwrapped[2], 1e-3f)
        assertEquals(370f, unwrapped[3], 1e-3f)
    }

    @Test
    fun `too few points to unwrap is not an error`() {
        assertEquals(0, unwrapAngles(FloatArray(0)).size)
        assertArrayEqualsF(floatArrayOf(42f), unwrapAngles(floatArrayOf(42f)))
    }

    /**
     * Order matters, and binning makes it starker than stride sampling did.
     *
     * Bin a wrapping series and one bin holds both 359° and 1°: its minimum and maximum span the whole
     * circle, so the band fills the card and says nothing at all. Unwrap first and the same bin spans
     * two degrees.
     */
    @Test
    fun `unwrapping must come before binning`() {
        // A slow turn straight through north. The phase matters: at 350.0 the crossing lands exactly on
        // a bin boundary and no single bin straddles it, which hides the very artefact being tested.
        val crossing = FloatArray(600) { i -> ((350.7f + i * 0.05f) % 360f) }

        val rightWay = envelope(unwrapAngles(crossing), 30)!!
        val wrongWay = envelope(crossing, 30)!!

        val widestRight = rightWay.maxs.indices.maxOf { rightWay.maxs[it] - rightWay.mins[it] }
        val widestWrong = wrongWay.maxs.indices.maxOf { wrongWay.maxs[it] - wrongWay.mins[it] }

        assertTrue("unwrapped bins should be narrow, widest was $widestRight", widestRight < 5f)
        assertTrue(
            "binning first should smear a bin across the circle, widest was $widestWrong",
            widestWrong > 300f,
        )
    }

    @Test
    fun `only the wrapping subjects are treated as circular`() {
        assertTrue(isCircularDegrees(PublishedSubject.COURSE_OVER_GROUND))
        assertTrue(isCircularDegrees(PublishedSubject.HEADING_MAGNETIC))
        assertTrue(isCircularDegrees(PublishedSubject.HEADING_TRUE_NORTH))
        // Declination is a small signed offset — unwrapping it would be solving a problem it never has.
        assertFalse(isCircularDegrees(PublishedSubject.MAGNETIC_VARIATION))
        assertFalse(isCircularDegrees(PublishedSubject.SPEED_OVER_GROUND))
        assertFalse(isCircularDegrees(PublishedSubject.HEADING_ACCURACY))
    }

    // -- the time window -------------------------------------------------------------------------

    /** Ages in seconds, oldest first — the order the ring appends in, which the binary search needs. */
    private fun window(vararg agesSeconds: Int, now: Long = 1_000_000L) = SampleWindow(
        timesMillis = agesSeconds.map { now - it * 1000L }.toLongArray(),
        values = agesSeconds.mapIndexed { i, _ -> i.toFloat() }.toFloatArray(),
    )

    @Test
    fun `the window keeps only what is inside it`() {
        val now = 1_000_000L
        val w = window(120, 90, 60, 30, 10, now = now)

        val recent = windowedTo(w, seconds = 45, nowMillis = now)

        assertEquals(2, recent.size)
        assertTrue(recent.timesMillis.all { it >= now - 45_000 })
    }

    @Test
    fun `a window that covers everything returns the same instance`() {
        val now = 1_000_000L
        val w = window(30, 20, 10, now = now)

        assertEquals(w.size, windowedTo(w, seconds = 600, nowMillis = now).size)
    }

    @Test
    fun `everything older than the window leaves nothing`() {
        val now = 1_000_000L
        val w = window(600, 500, 400, now = now)

        assertTrue(windowedTo(w, seconds = 30, nowMillis = now).isEmpty)
    }

    @Test
    fun `an empty window and a nonsensical span are handled`() {
        assertTrue(windowedTo(SampleWindow(), 30, 1_000L).isEmpty)
        assertTrue(windowedTo(window(10, 5), seconds = 0, nowMillis = 1_000_000L).isEmpty)
    }

    /** The footer's rate comes from the plotted window, so it stays right when the view is paused. */
    @Test
    fun `the window's own rate is derived from its span`() {
        val now = 1_000_000L
        val w = SampleWindow(
            timesMillis = longArrayOf(now - 4000, now - 3000, now - 2000, now - 1000, now),
            values = floatArrayOf(1f, 2f, 3f, 4f, 5f),
        )

        assertEquals(1.0, windowRateHz(w)!!, 1e-6)
        assertNull("one sample cannot have a rate", windowRateHz(SampleWindow(longArrayOf(now), floatArrayOf(1f))))
    }

    // -- presentation ----------------------------------------------------------------------------

    @Test
    fun `bearings become compass points at the right boundaries`() {
        assertEquals("N", cardinal(0f))
        assertEquals("N", cardinal(359f))
        assertEquals("N", cardinal(22.4f))
        assertEquals("NE", cardinal(22.6f))
        assertEquals("E", cardinal(90f))
        assertEquals("SE", cardinal(128f))
        assertEquals("S", cardinal(180f))
        assertEquals("W", cardinal(270f))
        assertEquals("NW", cardinal(315f))
        // Negative and over-a-turn angles are normalised rather than crashing an index.
        assertEquals("W", cardinal(-90f))
        assertEquals("E", cardinal(450f))
    }

    @Test
    fun `fix quality reflects what the accuracy is good for`() {
        assertEquals(FixQuality.Good, gnssQuality(3f))
        assertEquals(FixQuality.Good, gnssQuality(5f))
        assertEquals(FixQuality.Fair, gnssQuality(9f))
        assertEquals(FixQuality.Poor, gnssQuality(40f))
        assertEquals(FixQuality.Unknown, gnssQuality(null))
    }

    /**
     * The three vitals thresholds, which exist only to decide a colour — so the thing worth pinning is
     * that the *normal* case is never coloured. A row that shouts on a healthy run is a row nobody
     * reads by the second day of a trial.
     */
    @Test
    fun `cellular quality follows the modulation thresholds`() {
        assertEquals(FixQuality.Good, cellularQuality(25f))
        assertEquals(FixQuality.Good, cellularQuality(13f))
        assertEquals(FixQuality.Fair, cellularQuality(5f))
        assertEquals(FixQuality.Fair, cellularQuality(0f))
        // Below zero the noise is louder than the signal — a real reading, not a sentinel.
        assertEquals(FixQuality.Poor, cellularQuality(-5f))
        assertEquals(FixQuality.Unknown, cellularQuality(null))
    }

    @Test
    fun `battery quality warns before it is too late to act`() {
        assertEquals(FixQuality.Good, batteryQuality(100f))
        assertEquals(FixQuality.Good, batteryQuality(21f))
        // Twenty is where Android itself starts warning, so it is a warning here too.
        assertEquals(FixQuality.Fair, batteryQuality(20f))
        assertEquals(FixQuality.Fair, batteryQuality(10f))
        assertEquals(FixQuality.Poor, batteryQuality(9f))
        assertEquals(FixQuality.Unknown, batteryQuality(null))
    }

    /**
     * `No fix` is an *error* even though a position is usually still on the bus beside it — the fused
     * provider will happily derive one from wifi and cell with the GNSS engine solving nothing, and a
     * track being interpolated from cell towers is the thing an operator most needs to notice.
     */
    @Test
    fun `the fix kind colours itself off the same enum the word comes from`() {
        assertEquals(FixQuality.Good, fixKindQuality(FixKind.ThreeD.ordinal.toFloat()))
        assertEquals(FixQuality.Fair, fixKindQuality(FixKind.TwoD.ordinal.toFloat()))
        assertEquals(FixQuality.Poor, fixKindQuality(FixKind.NoFix.ordinal.toFloat()))
        assertEquals(FixQuality.Unknown, fixKindQuality(null))
        // A value the enum does not cover is unknown, not "no fix" — an out-of-range ordinal means the
        // wire said something this build does not understand, which is not the same as a stated fault.
        assertEquals(FixQuality.Unknown, fixKindQuality(99f))
    }

    /** Every group should say something useful when folded, or the badge is just a chevron. */
    @Test
    fun `every group has a headline`() {
        val latest: (PublishedSubject) -> Float? = { 42f }
        val rate: (PublishedSubject) -> Double? = { 55.3 }

        subjectGroups().forEach { group ->
            assertNotNull(
                "${group.title} shows nothing when collapsed",
                featuredValue(group, latest, rate, accuracyMetres = 3f),
            )
        }
    }

    @Test
    fun `a headline is absent rather than invented when its value is`() {
        val nothing: (PublishedSubject) -> Float? = { null }
        val noRate: (PublishedSubject) -> Double? = { null }

        subjectGroups().forEach { group ->
            assertNull(
                "${group.title} invented a headline from no data",
                featuredValue(group, nothing, noRate, accuracyMetres = null),
            )
        }
    }

    @Test
    fun `the headlines say what they are`() {
        val groups = subjectGroups().associateBy { it.title }
        val latest: (PublishedSubject) -> Float? = {
            when (it) {
                PublishedSubject.BATTERY_STATE_OF_CHARGE -> 89f
                PublishedSubject.CELLULAR_SINR -> 8f
                PublishedSubject.WIFI_RSSI -> -45f
                else -> null
            }
        }
        val rate: (PublishedSubject) -> Double? = { 55.3 }

        assertEquals("±3 m", featuredValue(groups.getValue("GNSS"), latest, rate, 3.2f))
        assertEquals("55.3 Hz", featuredValue(groups.getValue("IMU"), latest, rate, null))
        assertEquals("89 %", featuredValue(groups.getValue("Device"), latest, rate, null))
        assertEquals("SINR 8 dB", featuredValue(groups.getValue("Radio · cellular"), latest, rate, null))
        assertEquals("-45 dBm", featuredValue(groups.getValue("Radio · wifi"), latest, rate, null))
    }

    private fun assertArrayEqualsF(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], 1e-3f) }
    }
}
