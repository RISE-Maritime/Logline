package se.rise.logline

import se.rise.logline.calibrate.Enu
import se.rise.logline.calibrate.LatLonAlt
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.bodyOffsetMetres
import se.rise.logline.calibrate.enuFromBodyOffset
import se.rise.logline.calibrate.enuOffsetMetres
import se.rise.logline.calibrate.pointFromEnuOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two inverses a map picker needs, checked against the functions they invert.
 *
 * **Sign and axis errors here are invisible.** A sensor placed on the wrong side of a boat is a
 * plausible-looking number, not a crash, and every one of these has two axes that read equally well
 * swapped — which is the same argument `SensorAttitudeTest` makes about a mirror reading as a
 * perfectly good rotation. Round-tripping is the only cheap way to know.
 */
class GeodesyInverseTest {

    private val zero = LatLonAlt(57.4359, 12.0326, 0.0)

    @Test
    fun `a body offset survives the trip out to ENU and back`() {
        val heading = 137.5
        val body = Vec3M(x = 12.5, y = -3.25, z = -8.0)

        val there = enuFromBodyOffset(body, heading)
        val back = bodyOffsetMetres(there, heading)

        assertEquals(body.x, back.x, 1e-9)
        assertEquals(body.y, back.y, 1e-9)
        assertEquals(body.z, back.z, 1e-9)
    }

    /**
     * With the bow pointing north, forward *is* north and starboard *is* east. The case where a
     * swapped pair would still look plausible, so it is stated rather than left to the round trip.
     */
    @Test
    fun `heading north puts forward on north and starboard on east`() {
        val enu = enuFromBodyOffset(Vec3M(x = 10.0, y = 4.0, z = -2.0), platformHeadingDeg = 0.0)

        assertEquals(10.0, enu.northM, 1e-9)
        assertEquals(4.0, enu.eastM, 1e-9)
        // Down is positive in the body frame and up is positive in ENU: a sensor 2 m *up* a mast is
        // z = -2, which is the sign the field labels warn about.
        assertEquals(2.0, enu.upM, 1e-9)
    }

    /** And with the bow east, forward is east and starboard is south. */
    @Test
    fun `heading east puts forward on east and starboard on south`() {
        val enu = enuFromBodyOffset(Vec3M(x = 10.0, y = 4.0, z = 0.0), platformHeadingDeg = 90.0)

        assertEquals(10.0, enu.eastM, 1e-9)
        assertEquals(-4.0, enu.northM, 1e-9)
    }

    @Test
    fun `a point survives the trip out to ENU and back`() {
        val offset = Enu(eastM = 37.5, northM = -12.25, upM = 4.0)

        val there = pointFromEnuOffset(zero, offset)
        val back = enuOffsetMetres(zero, there)

        // Sub-millimetre at this range. The inverse uses the origin's latitude for the radii where
        // the forward direction uses the mean of both, so the two disagree by a little more the
        // further out you go — see the next test for how much.
        assertEquals(offset.eastM, back.eastM, 1e-3)
        assertEquals(offset.northM, back.northM, 1e-3)
        assertEquals(offset.upM, back.upM, 1e-9)
    }

    /**
     * **How far the approximation actually goes, measured rather than assumed.**
     *
     * Half a kilometre out — past anything platform-scale — the round trip loses about three
     * centimetres. This test is here because the first version of `pointFromEnuOffset`'s KDoc
     * claimed sub-millimetre accuracy "out to a few hundred metres" and this disagreed. The number
     * in that comment is now this one.
     */
    @Test
    fun `the approximation costs about three centimetres at five hundred metres`() {
        val offset = Enu(eastM = 500.0, northM = 500.0, upM = 0.0)

        val back = enuOffsetMetres(zero, pointFromEnuOffset(zero, offset))

        assertEquals(500.0, back.eastM, 0.05)
        assertEquals(500.0, back.northM, 0.05)
        // And it really is that small rather than accidentally passing a loose bound.
        assertEquals(0.031, 500.0 - back.eastM, 0.01)
    }

    /**
     * The whole path a map pick takes: a point on the chart becomes forward/starboard, and the
     * sensor drawn back onto the chart lands where it was put.
     */
    @Test
    fun `a picked point round-trips through the offset the model stores`() {
        val heading = 42.0
        val picked = LatLonAlt(57.4365, 12.0339, 0.0)

        val offset = bodyOffsetMetres(enuOffsetMetres(zero, picked), heading)
        val drawn = pointFromEnuOffset(zero, enuFromBodyOffset(offset, heading))

        assertEquals(picked.latitude, drawn.latitude, 1e-9)
        // ~0.6 mm at this latitude, which is the same approximation as above at a shorter range.
        assertEquals(picked.longitude, drawn.longitude, 1e-7)
    }
}
