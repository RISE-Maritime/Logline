package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.config.MINIMUM_POSITION_RATE
import se.rise.logline.config.MINIMUM_SUBJECTS
import se.rise.logline.config.Settings
import se.rise.logline.config.minimumForcedOff
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects
import se.rise.logline.sensors.SensorRate
import se.rise.logline.sensors.slowerOf

/** Minimum logging is a mode over the settings: it narrows a run without editing what it narrows. */
class MinimumModeTest {

    private val tuned = Settings(
        realm = "rise",
        entityId = "phone",
        routerEndpoints = listOf("tls/router.example.com:443"),
        locationSource = "phone",
        imuSource = "phone",
        recordAllMax = true,
        disabledSubjects = setOf(PublishedSubject.WIFI_RSSI),
        sensorRates = mapOf(Subjects.LOCATION_FIX to SensorRate.Hz(1.0)),
        recordRates = mapOf(Subjects.ANGULAR_VELOCITY_RADPS to SensorRate.Hz(10.0)),
    )
    private val minimum = tuned.copy(minimumMode = true)

    @Test
    fun `only the minimum set is left on`() {
        assertEquals(minimumForcedOff(), minimum.offSubjects())
        assertTrue(MINIMUM_SUBJECTS.none { it in minimum.offSubjects() })
    }

    /** The unfused fixes publish `location_fix` too, and are not the phone's position. */
    @Test
    fun `the unfused fixes and the surveyed zero stay off`() {
        assertTrue(PublishedSubject.LOCATION_FIX_GNSS in minimum.offSubjects())
        assertTrue(PublishedSubject.LOCATION_FIX_NETWORK in minimum.offSubjects())
        assertTrue(PublishedSubject.CALIBRATION_ZERO in minimum.offSubjects())
    }

    @Test
    fun `marks are never switched off`() {
        assertFalse(PublishedSubject.LOG_MESSAGE in minimum.offSubjects())
    }

    /** recordAllMax would otherwise lift position straight back to Max. */
    @Test
    fun `position is capped even with recording at maximum`() {
        assertEquals(MINIMUM_POSITION_RATE, minimum.recordRate(Subjects.LOCATION_FIX))
    }

    @Test
    fun `a slower tuned position rate still wins`() {
        val slow = minimum.copy(recordAllMax = false, recordRates = mapOf(Subjects.LOCATION_FIX to SensorRate.Hz(0.1)))
        assertEquals(SensorRate.Hz(0.1), slow.recordRate(Subjects.LOCATION_FIX))
    }

    @Test
    fun `what rides the fix is no faster than the fix`() {
        listOf(Subjects.LOCATION_FIX, Subjects.SPEED_OVER_GROUND_KNOTS, Subjects.COURSE_OVER_GROUND_DEG).forEach {
            val rate = minimum.publishRate(it)
            assertEquals(it, rate, slowerOf(rate, MINIMUM_POSITION_RATE))
        }
    }

    /** The point of a mode: switching back returns the tuned profile, so nothing tuned was written. */
    @Test
    fun `leaving minimum restores the switches and rates`() {
        val back = minimum.copy(minimumMode = false)
        assertEquals(tuned.offSubjects(), back.offSubjects())
        assertEquals(tuned.disabledSubjects, minimum.disabledSubjects)
        assertEquals(tuned.sensorRates, minimum.sensorRates)
        assertEquals(tuned.recordRates, minimum.recordRates)
        PublishedSubject.entries.map { it.subject }.distinct().forEach {
            assertEquals(it, tuned.recordRate(it), back.recordRate(it))
            assertEquals(it, tuned.publishRate(it), back.publishRate(it))
        }
    }
}
