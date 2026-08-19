package se.rise.logline

import se.rise.logline.sensors.chargePercent
import se.rise.logline.sensors.normaliseHeadingDegrees
import se.rise.logline.sensors.radiansToDegrees
import se.rise.logline.sensors.deciCelsiusToCelsius
import se.rise.logline.sensors.hectopascalToPascal
import se.rise.logline.sensors.metresPerSecondToKnots
import se.rise.logline.sensors.microampToAmp
import se.rise.logline.sensors.microteslaToGauss
import se.rise.logline.sensors.millivoltToVolt
import se.rise.logline.sensors.WIFI_RSSI_DISCONNECTED
import se.rise.logline.sensors.kilohertzToMegahertz
import se.rise.logline.sensors.megabitsPerSecondToBitsPerSecond
import se.rise.logline.sensors.orAbsent
import se.rise.logline.sensors.wifiLinkSpeedOrAbsent
import se.rise.logline.sensors.wifiRssiOrAbsent
import se.rise.logline.sensors.formatBands
import se.rise.logline.sensors.intOrAbsent
import se.rise.logline.sensors.longOrAbsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Android's units are not keelson's units, and a missed conversion is off by a constant factor —
 * a number that still looks like a plausible reading, which is exactly why it survives review.
 *
 * Every expectation here is worked from a known physical constant or a value read off the device,
 * not from the implementation.
 */
class UnitsTest {

    @Test
    fun `earth's magnetic field converts to a fraction of a gauss`() {
        // Earth's field is 25-65 µT. 1 gauss = 100 µT, so a correct conversion lands in 0.25-0.65 —
        // the exact range used as the on-device sanity check.
        assertEquals(0.50f, microteslaToGauss(50f), 1e-6f)
        assertEquals(0.25f, microteslaToGauss(25f), 1e-6f)
        assertEquals(0.65f, microteslaToGauss(65f), 1e-6f)
    }

    @Test
    fun `standard sea level pressure converts to pascals`() {
        // 1013.25 hPa is the ISA standard atmosphere, i.e. 101325 Pa exactly.
        assertEquals(101_325f, hectopascalToPascal(1013.25f), 0.01f)
        // What this device actually reads.
        assertEquals(100_456f, hectopascalToPascal(1004.56f), 0.5f)
    }

    @Test
    fun `one knot is one nautical mile per hour`() {
        // A nautical mile is exactly 1852 m, so 1852 m/s is exactly 3600 knots.
        assertEquals(3600f, metresPerSecondToKnots(1852f), 1e-2f)
        // The conventional factor, to four decimals.
        assertEquals(1.9438f, metresPerSecondToKnots(1f), 1e-4f)
        assertEquals(0f, metresPerSecondToKnots(0f), 1e-6f)
    }

    @Test
    fun `battery values convert from the units the platform reports`() {
        assertEquals(4.247f, millivoltToVolt(4247), 1e-6f)      // EXTRA_VOLTAGE is millivolts
        assertEquals(31.2f, deciCelsiusToCelsius(312), 1e-5f)   // EXTRA_TEMPERATURE is tenths
        assertEquals(-0.066875f, microampToAmp(-66875), 1e-9f)  // CURRENT_NOW is microamps
    }

    /** Negative means discharging. Taking a magnitude here would erase the distinction. */
    @Test
    fun `discharging current keeps its sign`() {
        assertEquals(-0.08f, microampToAmp(-80_000), 1e-9f)
        assertEquals(0.08f, microampToAmp(80_000), 1e-9f)
    }

    @Test
    fun `charge percent divides by the reported scale`() {
        assertEquals(100f, chargePercent(100, 100)!!, 1e-6f)
        assertEquals(42f, chargePercent(42, 100)!!, 1e-6f)
        // EXTRA_SCALE is documented as a divisor, not fixed at 100. A device reporting out of 255
        // would otherwise publish 100 as "100%" when it is really 39%.
        assertEquals(39.2157f, chargePercent(100, 255)!!, 1e-3f)
    }

    // ---- radio sentinels: the values that must never reach the wire ----

    /**
     * `CellInfo.UNAVAILABLE` is `Integer.MAX_VALUE`; `-140 dBm` is a real RSRP meaning "barely alive".
     * They are semantically opposite and two billion apart, so letting the sentinel through does not
     * add noise to an averaged series — it destroys it.
     */
    @Test
    fun `the telephony sentinel becomes absent while a floor reading survives`() {
        val unavailable = Int.MAX_VALUE

        assertNull((Int.MAX_VALUE).orAbsent(unavailable))
        assertEquals(-140f, (-140).orAbsent(unavailable)!!, 1e-6f)
        assertEquals(-94f, (-94).orAbsent(unavailable)!!, 1e-6f)
        // A legitimate zero must not be mistaken for absence.
        assertEquals(0f, 0.orAbsent(unavailable)!!, 1e-6f)
    }

    /**
     * WiFi uses a different sentinel family again — `-127` for a disconnected radio, `-1` for unknown
     * link speed. A single shared "is it unavailable" helper across telephony and WiFi would be wrong
     * in both directions.
     */
    @Test
    fun `wifi sentinels are their own family`() {
        assertNull(wifiRssiOrAbsent(WIFI_RSSI_DISCONNECTED))
        assertNull(wifiRssiOrAbsent(-128))
        assertEquals(-41f, wifiRssiOrAbsent(-41)!!, 1e-6f)
        // -90 dBm is a weak but real association, not a sentinel.
        assertEquals(-90f, wifiRssiOrAbsent(-90)!!, 1e-6f)

        assertNull(wifiLinkSpeedOrAbsent(-1))
        assertNull(wifiLinkSpeedOrAbsent(0))
        assertEquals(2_161_000_000f, wifiLinkSpeedOrAbsent(2161)!!, 1f)
    }

    /**
     * The trap: `getNci()` returns `CellInfo.UNAVAILABLE_LONG` (Long.MAX_VALUE) while every other
     * identity getter returns `CellInfo.UNAVAILABLE` (Integer.MAX_VALUE). Checking a 36-bit NCI against
     * the 32-bit sentinel would publish 9223372036854775807 as a cell id.
     */
    @Test
    fun `the two sentinel widths are not interchangeable`() {
        val unavailable = Int.MAX_VALUE
        val unavailableLong = Long.MAX_VALUE

        assertNull(Int.MAX_VALUE.intOrAbsent(unavailable))
        assertNull(Long.MAX_VALUE.longOrAbsent(unavailableLong))

        // Integer.MAX_VALUE as a *long* is a perfectly legal cell id, and must survive the 64-bit check.
        assertEquals(2147483647L, 2147483647L.longOrAbsent(unavailableLong))
        // ...and the 64-bit sentinel must not be mistaken for the 32-bit one by a narrowing check.
        assertNotEquals(unavailable.toLong(), unavailableLong)
    }

    @Test
    fun `real identity values at the range boundaries survive`() {
        val unavailable = Int.MAX_VALUE
        assertEquals(0, 0.intOrAbsent(unavailable))            // PCI 0 is valid
        assertEquals(503, 503.intOrAbsent(unavailable))        // top of the LTE PCI range
        assertEquals(1007, 1007.intOrAbsent(unavailable))      // top of the NR PCI range
        assertEquals(1750, 1750.intOrAbsent(unavailable))      // this device's EARFCN
        assertEquals(268435455, 268435455.intOrAbsent(unavailable))  // 28-bit ECI maximum
        // 36-bit NCI maximum, which does not fit in an Int at all.
        assertEquals(68719476735L, 68719476735L.longOrAbsent(Long.MAX_VALUE))
    }

    @Test
    fun `bands format per technology and join under aggregation`() {
        assertEquals("3", formatBands(intArrayOf(3), nr = false))
        assertEquals("3,7", formatBands(intArrayOf(3, 7), nr = false))
        // NR bands conventionally carry an n prefix.
        assertEquals("n78", formatBands(intArrayOf(78), nr = true))
        assertEquals("n78,n28", formatBands(intArrayOf(78, 28), nr = true))
    }

    /** An empty array means the platform did not report bands — absence, not "no bands". */
    @Test
    fun `no bands reported is absent rather than empty string`() {
        assertNull(formatBands(intArrayOf(), nr = false))
        assertNull(formatBands(intArrayOf(), nr = true))
    }

    /**
     * Every LTE carrier width, because the narrow ones are where an integer division would hide.
     *
     * 20 MHz is what a Pixel 6 on Tele2 band 7 reported while this was written (`dumpsys
     * telephony.registry`, `mBandwidth=20000`) — the check that the factor is 1000 and not 1 000 000.
     * 1.4 MHz is the one that cannot survive integer arithmetic: it would come out 1.
     */
    @Test
    fun `cell bandwidth converts from kilohertz to megahertz`() {
        assertEquals(20f, kilohertzToMegahertz(20_000), 0.001f)
        assertEquals(1.4f, kilohertzToMegahertz(1_400), 0.001f)
        assertEquals(3f, kilohertzToMegahertz(3_000), 0.001f)
        assertEquals(15f, kilohertzToMegahertz(15_000), 0.001f)
    }

    /**
     * The sentinel is the 32-bit one. `getBandwidth()` returns `CellInfo.UNAVAILABLE`, and publishing
     * that unchecked would put 2 147 483 647 kHz — two terahertz of spectrum — on the bus.
     */
    @Test
    fun `an unavailable bandwidth is absent, not a sentinel`() {
        assertNull(Integer.MAX_VALUE.intOrAbsent(Integer.MAX_VALUE))
        assertEquals(20_000, 20_000.intOrAbsent(Integer.MAX_VALUE))
    }

    @Test
    fun `link speed converts from megabits to bits`() {
        assertEquals(1_633_000_000f, megabitsPerSecondToBitsPerSecond(1633), 1f)
        assertEquals(1_000_000f, megabitsPerSecondToBitsPerSecond(1), 1f)
    }

    /** Absent rather than zero: a phone that will not say is not a phone at 0%. */
    @Test
    fun `charge percent is null when the platform reports nothing usable`() {
        assertNull(chargePercent(-1, 100))
        assertNull(chargePercent(50, 0))
        assertNull(chargePercent(50, -1))
    }

    /**
     * A bearing is measured clockwise from north over [0, 360) — Android's azimuth is signed over
     * (-π, π], so west arrives as -90°, which no compass rose has.
     */
    @Test
    fun `a heading is normalised onto the compass rose`() {
        assertEquals(0f, normaliseHeadingDegrees(0f), 1e-4f)
        assertEquals(90f, normaliseHeadingDegrees(90f), 1e-4f)
        assertEquals(270f, normaliseHeadingDegrees(-90f), 1e-4f)
        assertEquals(180f, normaliseHeadingDegrees(-180f), 1e-4f)
        // Adding a declination can push a heading past a full turn.
        assertEquals(10f, normaliseHeadingDegrees(370f), 1e-4f)
        assertEquals(350f, normaliseHeadingDegrees(-370f), 1e-4f)
        assertEquals(0f, normaliseHeadingDegrees(360f), 1e-4f)
    }

    /** Half a turn is π radians and 180 degrees, in any century. */
    @Test
    fun `radians convert to degrees`() {
        assertEquals(180f, radiansToDegrees(Math.PI.toFloat()), 1e-3f)
        assertEquals(0f, radiansToDegrees(0f), 1e-6f)
        // The estimate the Pixel 6 was reporting: 1.05 rad is a 60-degree heading uncertainty.
        assertEquals(60.16f, radiansToDegrees(1.05f), 0.01f)
    }
}
