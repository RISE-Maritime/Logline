package se.rise.logline

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.ui.derivedName
import se.rise.logline.ui.formatBitrate
import se.rise.logline.ui.formatLiveValue
import se.rise.logline.ui.formatPosition
import se.rise.logline.ui.labelOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display names are what the main screen shows instead of the wire names. They are hand-written
 * rather than derived, so the risk is a subject added later falling through to the mechanical fallback
 * and appearing as "Radio rsrp" next to properly named neighbours — which these tests catch.
 */
class SubjectLabelsTest {

    @Test
    fun `every subject is named deliberately, not by the fallback`() {
        val fellThrough = PublishedSubject.entries
            .filter { labelOf(it).name == derivedName(it.subject) && it.subject.contains('_') }
            .map { it.subject }

        assertTrue("no display name for $fellThrough — add one to labelOf()", fellThrough.isEmpty())
    }

    @Test
    fun `names read as words and never as the wire name`() {
        PublishedSubject.entries.forEach {
            val name = labelOf(it).name
            assertTrue("${it.subject} still shows an underscore", !name.contains('_'))
            assertTrue("${it.subject} has an empty name", name.isNotBlank())
        }
    }

    @Test
    fun `the units are the keelson ones, not Android's`() {
        assertEquals("kn", labelOf(PublishedSubject.SPEED_OVER_GROUND).unit)
        assertEquals("m/s²", labelOf(PublishedSubject.LINEAR_ACCEL).unit)
        assertEquals("G", labelOf(PublishedSubject.MAGNETIC_FIELD).unit)
        assertEquals("Pa", labelOf(PublishedSubject.AIR_PRESSURE).unit)
        assertEquals("°", labelOf(PublishedSubject.HEADING_MAGNETIC).unit)
    }

    /** A subject whose value is not a number has no unit to hang off it. */
    @Test
    fun `unitless subjects declare no unit`() {
        assertEquals(null, labelOf(PublishedSubject.LOCATION_FIX).unit)
        assertEquals(null, labelOf(PublishedSubject.ORIENTATION).unit)
        assertEquals(null, labelOf(PublishedSubject.BATTERY_IS_CHARGING).unit)
        assertEquals(null, labelOf(PublishedSubject.BAND).unit)
    }

    /**
     * Precision is a claim about the measurement. GNSS speed is good to a tenth of a knot, so three
     * more digits would be inventing accuracy the sensor does not have.
     */
    @Test
    fun `values print at the precision they mean`() {
        assertEquals("3.4", formatLiveValue(PublishedSubject.SPEED_OVER_GROUND, 3.44827f))
        assertEquals("127", formatLiveValue(PublishedSubject.COURSE_OVER_GROUND, 127.3f))
        assertEquals("+5.4", formatLiveValue(PublishedSubject.MAGNETIC_VARIATION, 5.447f))
        assertEquals("-95", formatLiveValue(PublishedSubject.CELLULAR_RSRP, -95.2f))
        assertEquals("4.26", formatLiveValue(PublishedSubject.BATTERY_VOLTAGE, 4.264f))
    }

    /** A boolean on the wire is 1 or 0; nobody reads that as "charging". */
    @Test
    fun `a boolean reads as a word`() {
        assertEquals("Yes", formatLiveValue(PublishedSubject.BATTERY_IS_CHARGING, 1f))
        assertEquals("No", formatLiveValue(PublishedSubject.BATTERY_IS_CHARGING, 0f))
    }

    @Test
    fun `bit rates carry an SI prefix rather than nine digits`() {
        assertEquals("219M", formatBitrate(219_000_000f))
        assertEquals("1.5G", formatBitrate(1_500_000_000f))
        assertEquals("300k", formatBitrate(300_000f))
        assertEquals("900", formatBitrate(900f))
    }

    /** Magnitudes span decades — a milli-g and 20 m/s² both have to be readable. */
    @Test
    fun `vector magnitudes keep significant figures across decades`() {
        assertEquals("0", formatLiveValue(PublishedSubject.LINEAR_ACCEL, 0f))
        assertEquals("0.087", formatLiveValue(PublishedSubject.LINEAR_ACCEL, 0.0874f))
        assertEquals("9.81", formatLiveValue(PublishedSubject.LINEAR_ACCEL, 9.8123f))
        // Not scientific notation: a phone at rest reads near zero and "0.0020" is legible at arm's
        // length where "2.0e-03" is not.
        assertEquals("0.0020", formatLiveValue(PublishedSubject.ANGULAR_VEL, 0.002f))
        assertNotNull(formatLiveValue(PublishedSubject.LINEAR_ACCEL, 0.000031f))
    }

    /**
     * Sweden writes decimals with a comma, so a plain "lat, lon" is three commas doing two jobs. The
     * hemisphere letters are what keep it readable wherever the app runs.
     */
    @Test
    fun `a position is unambiguous whatever the decimal separator`() {
        val onsala = formatPosition(57.4359, 12.0326)
        assertTrue(onsala, onsala.contains("N") && onsala.contains("E"))
        assertTrue("negatives become S and W", formatPosition(-33.9, -18.4).let {
            it.contains("S") && it.contains("W") && !it.contains("-")
        })
    }

    /**
     * The live view used to print `9.54\u00d710^-03 kn`, which is unreadable on a boat. Nothing this
     * formatter produces may be exponential, at any magnitude any subject can reach.
     */
    @Test
    fun `no subject ever formats to scientific notation`() {
        val extremes = listOf(
            0f, 1e-7f, -1e-7f, 0.0001f, 0.5f, 9.81f, 127f, -96f, 1750f,
            100_425f, 2_160_999_936f, -2_160_999_936f,
        )
        PublishedSubject.entries.forEach { entry ->
            extremes.forEach { value ->
                val text = formatLiveValue(entry, value)
                assertFalse(
                    "${entry.subject} formatted $value as \"$text\"",
                    text.contains("e-") || text.contains("E-") || text.contains("\u00d710"),
                )
            }
        }
    }
}
