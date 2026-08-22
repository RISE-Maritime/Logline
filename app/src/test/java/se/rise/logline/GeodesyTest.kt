package se.rise.logline

import se.rise.logline.calibrate.Enu
import se.rise.logline.calibrate.FixSample
import se.rise.logline.calibrate.LatLonAlt
import se.rise.logline.calibrate.averageFix
import se.rise.logline.calibrate.bodyOffsetMetres
import se.rise.logline.calibrate.circularMeanDegrees
import se.rise.logline.calibrate.enuOffsetMetres
import se.rise.logline.calibrate.initialBearingDegrees
import se.rise.logline.calibrate.meridianRadiusM
import se.rise.logline.calibrate.primeVerticalRadiusM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calibration maths, against known geodetic constants rather than against itself.
 *
 * A wrong radius or a flipped sign here produces offsets that look entirely reasonable — a mast three
 * metres below the keel is four correct-looking digits — so every expectation below comes from a
 * published figure or from a case that can be reasoned about on paper.
 */
class GeodesyTest {

    @Test
    fun `one degree of latitude is 110574 m at the equator and 111694 m at the pole`() {
        // The standard figures for the length of a degree of latitude on WGS84. That they differ by
        // over a kilometre is the reason this is not a sphere.
        assertEquals(110_574.0, meridianRadiusM(0.0) * Math.toRadians(1.0), 1.0)
        assertEquals(111_694.0, meridianRadiusM(90.0) * Math.toRadians(1.0), 1.0)
    }

    @Test
    fun `one degree of longitude shrinks with latitude`() {
        // 111 320 m at the equator, and 55 800 m at 60 degrees — the classic halving, because
        // cos(60 degrees) is exactly a half.
        val atEquator = primeVerticalRadiusM(0.0) * Math.toRadians(1.0) * Math.cos(0.0)
        val atSixty = primeVerticalRadiusM(60.0) * Math.toRadians(1.0) * Math.cos(Math.toRadians(60.0))
        assertEquals(111_320.0, atEquator, 1.0)
        assertEquals(55_800.0, atSixty, 5.0)
    }

    @Test
    fun `a short offset comes back as metres north and east`() {
        // Gothenburg. A tenth of a millidegree of latitude is about 11 m; the east-west figure follows
        // the cosine of the latitude, so at 57.7 degrees it is a little over half that per degree.
        val zero = LatLonAlt(57.7089, 11.9746, 10.0)
        val north = LatLonAlt(57.7089 + 0.0001, 11.9746, 10.0)
        val enu = enuOffsetMetres(zero, north)
        assertEquals(11.13, enu.northM, 0.02)
        assertEquals(0.0, enu.eastM, 1e-9)
        assertEquals(0.0, enu.upM, 1e-9)
    }

    @Test
    fun `altitude gained is upward in ENU`() {
        val enu = enuOffsetMetres(
            LatLonAlt(57.7089, 11.9746, 10.0),
            LatLonAlt(57.7089, 11.9746, 13.5),
        )
        assertEquals(3.5, enu.upM, 1e-9)
    }

    @Test
    fun `bearing due east is ninety degrees`() {
        val from = LatLonAlt(57.7089, 11.9746)
        assertEquals(90.0, initialBearingDegrees(from, LatLonAlt(57.7089, 11.9846)), 0.01)
        assertEquals(0.0, initialBearingDegrees(from, LatLonAlt(57.7189, 11.9746)), 0.01)
        assertEquals(180.0, initialBearingDegrees(from, LatLonAlt(57.6989, 11.9746)), 0.01)
        assertEquals(270.0, initialBearingDegrees(from, LatLonAlt(57.7089, 11.9646)), 0.01)
    }

    @Test
    fun `with the platform pointing north a point to the north is dead ahead`() {
        val body = bodyOffsetMetres(Enu(eastM = 0.0, northM = 10.0, upM = 0.0), platformHeadingDeg = 0.0)
        assertEquals(10.0, body.x, 1e-9)
        assertEquals(0.0, body.y, 1e-9)
    }

    @Test
    fun `with the platform pointing east a point to the north is to port`() {
        // The test that catches a flipped Y. Facing east, north is on the left — port — and port is
        // negative, because Y is positive to starboard.
        val body = bodyOffsetMetres(Enu(eastM = 0.0, northM = 10.0, upM = 0.0), platformHeadingDeg = 90.0)
        assertEquals(0.0, body.x, 1e-9)
        assertEquals(-10.0, body.y, 1e-9)
    }

    @Test
    fun `with the platform pointing east a point to the east is ahead`() {
        val body = bodyOffsetMetres(Enu(eastM = 10.0, northM = 0.0, upM = 0.0), platformHeadingDeg = 90.0)
        assertEquals(10.0, body.x, 1e-9)
        assertEquals(0.0, body.y, 1e-9)
    }

    @Test
    fun `up becomes negative Z`() {
        // Maritime convention: Z is positive *down*. Three metres up the mast is minus three.
        val body = bodyOffsetMetres(Enu(eastM = 0.0, northM = 0.0, upM = 3.0), platformHeadingDeg = 0.0)
        assertEquals(-3.0, body.z, 1e-9)
    }

    @Test
    fun `a captured offset round-trips through both conversions`() {
        // Stand at the zero, walk 4 m forward and 2 m to starboard on a platform heading 035, and the two
        // conversions together have to give those numbers back.
        val heading = 35.0
        val zero = LatLonAlt(57.7089, 11.9746, 12.0)
        val forward = Math.toRadians(heading)
        val north = 4.0 * Math.cos(forward) - 2.0 * Math.sin(forward)
        val east = 4.0 * Math.sin(forward) + 2.0 * Math.cos(forward)
        val sensor = LatLonAlt(
            latitude = zero.latitude + Math.toDegrees(north / meridianRadiusM(zero.latitude)),
            longitude = zero.longitude + Math.toDegrees(
                east / (primeVerticalRadiusM(zero.latitude) * Math.cos(Math.toRadians(zero.latitude)))
            ),
            altitudeM = 12.0,
        )
        val body = bodyOffsetMetres(enuOffsetMetres(zero, sensor), heading)
        assertEquals(4.0, body.x, 0.01)
        assertEquals(2.0, body.y, 0.01)
        assertEquals(0.0, body.z, 0.01)
    }

    @Test
    fun `averaging returns the mean position and the scatter about it`() {
        // Four samples symmetric about a centre: the mean is the centre, and the scatter is the RMS
        // distance out to them — which for four equal offsets is that offset.
        val centre = LatLonAlt(57.7089, 11.9746)
        val step = Math.toDegrees(1.0 / meridianRadiusM(centre.latitude)) // one metre of latitude
        val samples = listOf(
            FixSample(centre.latitude + step, centre.longitude, 10.0, 3.0),
            FixSample(centre.latitude - step, centre.longitude, 10.0, 3.0),
            FixSample(centre.latitude, centre.longitude, 12.0, 5.0),
            FixSample(centre.latitude, centre.longitude, 12.0, 5.0),
        )
        val averaged = averageFix(samples)!!
        assertEquals(centre.latitude, averaged.point.latitude, 1e-9)
        assertEquals(11.0, averaged.point.altitudeM, 1e-9)
        assertEquals(4.0, averaged.accuracyM!!, 1e-9)
        assertEquals(0.707, averaged.scatterM, 0.01)
        assertEquals(4, averaged.samples)
        assertTrue(averaged.hasAltitude)
    }

    @Test
    fun `an absent altitude survives averaging as absent`() {
        // proto3 cannot tell an unset altitude from sea level, so "nobody reported one" has to stay
        // its own answer rather than becoming 0.0 quietly.
        val averaged = averageFix(
            listOf(FixSample(57.7089, 11.9746, null, 3.0), FixSample(57.7089, 11.9746, null, 3.0))
        )!!
        assertFalse(averaged.hasAltitude)
    }

    @Test
    fun `averaging nothing is nothing`() {
        assertNull(averageFix(emptyList()))
    }

    @Test
    fun `headings either side of north average to north`() {
        // The reason this is not a plain mean: 359 and 1 average arithmetically to 180, which is the
        // opposite direction and looks entirely reasonable on screen.
        assertEquals(0.0, circularMeanDegrees(listOf(359.0, 1.0))!!, 1e-9)
        assertEquals(359.0, circularMeanDegrees(listOf(358.0, 0.0))!!, 1e-9)
        assertEquals(35.0, circularMeanDegrees(listOf(34.0, 35.0, 36.0))!!, 1e-9)
        assertEquals(180.0, circularMeanDegrees(listOf(179.0, 181.0))!!, 1e-9)
    }

    @Test
    fun `headings that cancel exactly have no mean to report`() {
        // Opposite directions sum to nothing. Answering with an arbitrary angle would be inventing a
        // heading out of a compass that was spinning.
        assertNull(circularMeanDegrees(listOf(0.0, 180.0)))
        assertNull(circularMeanDegrees(emptyList()))
    }
}
