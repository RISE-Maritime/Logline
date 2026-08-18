package se.rise.logline

import se.rise.logline.config.Settings
import se.rise.logline.keelson.Subjects
import se.rise.logline.sensors.achievedHz
import se.rise.logline.sensors.hzToIntervalMillis
import se.rise.logline.sensors.hzToRateUs
import se.rise.logline.sensors.SensorRate
import se.rise.logline.sensors.parseRateHz
import se.rise.logline.sensors.parseSensorRate
import se.rise.logline.sensors.serialise
import se.rise.logline.sensors.toIntervalMillis
import se.rise.logline.sensors.toRateUs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorRateTest {

    @Test
    fun `hz converts to the microsecond delay registerListener wants`() {
        assertEquals(20_000, hzToRateUs(50.0))   // SENSOR_DELAY_GAME, the previous hard-coded value
        assertEquals(10_000, hzToRateUs(100.0))
        assertEquals(2_500, hzToRateUs(400.0))
    }

    @Test
    fun `hz converts to the millisecond interval LocationRequest wants`() {
        assertEquals(1_000L, hzToIntervalMillis(1.0))
        assertEquals(5_000L, hzToIntervalMillis(0.2))   // one fix every 5 s on a long run
        assertEquals(100L, hzToIntervalMillis(10.0))
    }

    @Test
    fun `a usable rate parses`() {
        assertEquals(50.0, parseRateHz("50")!!, 1e-9)
        assertEquals(0.2, parseRateHz(" 0.2 ")!!, 1e-9)
        assertEquals(12.5, parseRateHz("12,5")!!, 1e-9)  // comma decimal separator
    }

    /** Free-form entry means the field is the last line of defence against a nonsense run. */
    @Test
    fun `unusable input is rejected rather than substituted`() {
        assertNull(parseRateHz(""))
        assertNull(parseRateHz("   "))
        assertNull(parseRateHz("abc"))
        assertNull(parseRateHz("0"))
        assertNull(parseRateHz("-5"))
        assertNull(parseRateHz("NaN"))
        assertNull(parseRateHz("Infinity"))
    }

    @Test
    fun `defaults reproduce the previously hard-coded behaviour`() {
        assertEquals(SensorRate.Hz(50.0), Settings.defaultRate(Subjects.LINEAR_ACCELERATION_MPSS))
        assertEquals(SensorRate.Hz(50.0), Settings.defaultRate(Subjects.ORIENTATION_QUATERNION))
        assertEquals(SensorRate.Hz(1.0), Settings.defaultRate(Subjects.LOCATION_FIX))
    }

    @Test
    fun `a configured rate wins over the default`() {
        val settings = Settings(
            realm = "rise",
            entityId = "pixel_6",
            routerEndpoints = listOf("tcp/127.0.0.1:7447"),
            locationSource = "phone",
            imuSource = "phone",
            sensorRates = mapOf(Subjects.ANGULAR_VELOCITY_RADPS to SensorRate.Hz(10.0)),
        )

        assertEquals(SensorRate.Hz(10.0), settings.rate(Subjects.ANGULAR_VELOCITY_RADPS))
        assertEquals(SensorRate.Hz(50.0), settings.rate(Subjects.LINEAR_ACCELERATION_MPSS))
    }

    // ---- Max: let the hardware decide ----

    /**
     * `SENSOR_DELAY_FASTEST` is a zero delay, and interval 0 asks the location provider for whatever
     * it can produce. Anything non-zero here would silently impose a ceiling.
     */
    @Test
    fun `max asks for a zero delay rather than a number`() {
        assertEquals(0, SensorRate.Max.toRateUs())
        assertEquals(0L, SensorRate.Max.toIntervalMillis())
    }

    @Test
    fun `an explicit rate still converts to a real delay`() {
        assertEquals(20_000, SensorRate.Hz(50.0).toRateUs())
        assertEquals(1_000L, SensorRate.Hz(1.0).toIntervalMillis())
    }

    @Test
    fun `rates round-trip through storage`() {
        assertEquals(SensorRate.Max, parseSensorRate(SensorRate.Max.serialise()))
        assertEquals(SensorRate.Hz(0.2), parseSensorRate(SensorRate.Hz(0.2).serialise()))
        assertEquals(SensorRate.Hz(415.97), parseSensorRate(SensorRate.Hz(415.97).serialise()))
    }

    @Test
    fun `unusable stored values fall back rather than crashing`() {
        assertNull(parseSensorRate(null))
        assertNull(parseSensorRate(""))
        assertNull(parseSensorRate("max"))       // case matters; only the exact token counts
        assertNull(parseSensorRate("fastest"))
        assertNull(parseSensorRate("0"))
        assertNull(parseSensorRate("-1"))
    }

    /** n samples span n-1 intervals; counting n would overstate the rate on a short run. */
    @Test
    fun `achieved rate spans the intervals between samples`() {
        assertEquals(10.0, achievedHz(samples = 11, firstEpochMillis = 1_000, lastEpochMillis = 2_000)!!, 1e-9)
        assertEquals(1.0, achievedHz(samples = 2, firstEpochMillis = 500, lastEpochMillis = 1_500)!!, 1e-9)
    }

    @Test
    fun `achieved rate is unavailable before there is anything to divide`() {
        assertNull(achievedHz(samples = 0, firstEpochMillis = 0, lastEpochMillis = 0))
        assertNull(achievedHz(samples = 1, firstEpochMillis = 1_000, lastEpochMillis = 1_000))
        assertNull(achievedHz(samples = 5, firstEpochMillis = 1_000, lastEpochMillis = 1_000))
    }
}
