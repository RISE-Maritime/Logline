package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.ui.CeilingBasis
import se.rise.logline.ui.rateCeilings

/**
 * What each source can produce at best — the half of it that is a decision rather than a reading.
 *
 * Every branch in `rateCeilings()` is either a query this device answers or a judgement somebody made,
 * and on a phone the two look identical: a number appears in a row. These tests exercise the
 * judgements in a JVM, with the platform lookups stubbed, because the alternative is to notice a wrong
 * ceiling only if you happen to be holding hardware that contradicts it.
 */
class RateCeilingTest {

    /** Every sensor reports 5 ms, i.e. 200 Hz, so a `Reported` ceiling is unmistakable. */
    private fun ceilings(
        sensorMinDelayUs: (Int) -> Int? = { 5_000 },
        imuTemperatureMinDelayUs: () -> Int? = { 100_000 },
    ) = rateCeilings(sensorMinDelayUs, imuTemperatureMinDelayUs)

    /**
     * The whole point of the feature: a row with no ceiling has nothing to say about its source.
     *
     * `log_message` is the sole exemption and is asserted separately rather than filtered out here, so
     * a second event-driven subject added later fails this test until somebody decides it belongs.
     */
    @Test
    fun `every subject on present hardware has a ceiling`() {
        val resolved = ceilings()
        PublishedSubject.entries.filterNot { it.eventDriven }.forEach { entry ->
            assertTrue("${entry.name} has no ceiling", entry in resolved)
        }
    }

    @Test
    fun `an event-driven subject has none, because a button press is not a rate`() {
        assertNull(ceilings()[PublishedSubject.LOG_MESSAGE])
        assertEquals(
            listOf(PublishedSubject.LOG_MESSAGE),
            PublishedSubject.entries.filter { it.eventDriven },
        )
    }

    /**
     * A derived subject cannot outrun the samples it rides.
     *
     * `heading_true_north_deg` is published from the rotation vector's own callback, so a ceiling
     * computed any other way would promise a rate its source never produces.
     */
    @Test
    fun `a subject that rides another gets that one's ceiling`() {
        val resolved = ceilings()
        PublishedSubject.entries.filter { it.rateOwner != null }.forEach { entry ->
            val owner = PublishedSubject.forSubject(entry.rateOwner!!)
            assertEquals("${entry.name} disagrees with its owner", resolved[owner], resolved[entry])
        }
    }

    /** `Sensor.getMinDelay()` in microseconds, as a rate. 5 000 µs is 200 Hz. */
    @Test
    fun `a SensorManager sensor reports its own minimum delay`() {
        val imu = ceilings()[PublishedSubject.LINEAR_ACCEL]
        assertEquals(200.0, imu?.hz!!, 0.001)
        assertEquals(CeilingBasis.Reported, imu.basis)
    }

    /**
     * `minDelay == 0` is the platform saying **on-change**, not "infinitely fast".
     *
     * `TYPE_LIGHT` reports it and can then be silent for twenty minutes. Dividing by it would either
     * crash or print a rate no reader could act on.
     */
    @Test
    fun `an on-change sensor has no number`() {
        val light = ceilings(sensorMinDelayUs = { 0 })[PublishedSubject.ILLUMINANCE]
        assertEquals(CeilingBasis.OnChange, light?.basis)
        assertNull(light?.hz)
        assertEquals("on change", light?.label())
    }

    /** Hardware that is not there answers nothing, and the row already says "Not on this device". */
    @Test
    fun `an absent sensor has no ceiling`() {
        assertNull(ceilings(sensorMinDelayUs = { null })[PublishedSubject.AIR_PRESSURE])
    }

    /**
     * The poll floors are quoted from the providers, not copied — so this asserts the arithmetic, and
     * a change to `RadioProvider.MIN_POLL_MILLIS` moves the ceiling with it rather than breaking here.
     */
    @Test
    fun `a polled source is capped by its own loop`() {
        val resolved = ceilings()
        assertEquals(1.0, resolved[PublishedSubject.BATTERY_STATE_OF_CHARGE]?.hz!!, 0.001)
        assertEquals(4.0, resolved[PublishedSubject.CELLULAR_RSRP]?.hz!!, 0.001)
        assertEquals(CeilingBasis.Imposed, resolved[PublishedSubject.CELLULAR_RSRP]?.basis)
    }

    /**
     * GNSS is the one estimate, and it must read as one.
     *
     * There is no supported-rate query on the fused provider, so the number is a judgement about phone
     * chipsets. The `~` is what stops a 5 Hz receiver's row looking like a contradiction.
     */
    @Test
    fun `the location ceiling is marked as an estimate`() {
        val fix = ceilings()[PublishedSubject.LOCATION_FIX]
        assertEquals(CeilingBasis.Estimated, fix?.basis)
        assertEquals("max ~1.0", fix?.label())
    }

    /** The IMU's die temperature is a real sensor, just one found by string rather than by constant. */
    @Test
    fun `the IMU temperature is read from the vendor sensor it is found by`() {
        val temp = ceilings()[PublishedSubject.IMU_TEMPERATURE]
        assertEquals(10.0, temp?.hz!!, 0.001)
        assertEquals(CeilingBasis.Reported, temp.basis)
        assertNull(ceilings(imuTemperatureMinDelayUs = { null })[PublishedSubject.IMU_TEMPERATURE])
    }
}
