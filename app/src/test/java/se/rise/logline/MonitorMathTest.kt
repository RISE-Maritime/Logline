package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.monitor.CardKind
import se.rise.logline.monitor.ImuFrame
import se.rise.logline.monitor.MPS_TO_KNOTS
import se.rise.logline.monitor.MonitorCard
import se.rise.logline.monitor.STANDARD_GRAVITY
import se.rise.logline.monitor.Topic
import se.rise.logline.monitor.VectorWindow
import se.rise.logline.monitor.circularMeanDeg
import se.rise.logline.monitor.driftAngleDeg
import se.rise.logline.monitor.gapColumns
import se.rise.logline.monitor.imuAnalysis
import se.rise.logline.monitor.inputs
import se.rise.logline.monitor.rotFromHeadings
import se.rise.logline.monitor.timeColumns
import se.rise.logline.monitor.transverseAtKn
import se.rise.logline.monitor.wantedTopics
import se.rise.logline.monitor.within
import se.rise.logline.publish.SampleWindow

class MonitorMathTest {

    @Test
    fun `time columns bin by time, not by index, and leave empty columns empty`() {
        val times = longArrayOf(0, 10, 20, 900)
        val values = floatArrayOf(1f, 3f, 2f, 7f)
        val cols = timeColumns(times, values, 0, 1000, 10)

        assertEquals(1f, cols.mins[0])
        assertEquals(3f, cols.maxs[0])
        assertEquals(2f, cols.means[0])
        assertTrue(cols.means[5].isNaN())
        assertEquals(7f, cols.means[9])
    }

    @Test
    fun `an ordinary series is bridged, a paused one is broken`() {
        // 10 Hz over a 60 s axis in 500 columns: consecutive samples land ~0.8 columns apart, and a
        // line joining only adjacent columns would draw almost nothing.
        val tenHz = LongArray(600) { it * 100L }
        assertTrue(gapColumns(tenHz, 0, 60_000, 500) >= 2)
        // The bridge is at least two seconds, never the whole axis.
        assertTrue(gapColumns(tenHz, 0, 60_000, 500) < 100)
    }

    @Test
    fun `within keeps the samples inside the range`() {
        val w = SampleWindow(longArrayOf(1, 2, 3, 4), floatArrayOf(1f, 2f, 3f, 4f)).within(2, 3)
        assertEquals(listOf(2L, 3L), w.timesMillis.toList())
    }

    @Test
    fun `a heading averaged across north is north, not south`() {
        assertEquals(0f, circularMeanDeg(floatArrayOf(350f, 10f))!!, 1e-3f)
        assertNull(circularMeanDeg(FloatArray(0)))
    }

    @Test
    fun `drift is COG minus heading, folded to a side`() {
        assertEquals(10.0, driftAngleDeg(5.0, 355.0)!!, 1e-9)
        assertEquals(-10.0, driftAngleDeg(355.0, 5.0)!!, 1e-9)
        assertNull(driftAngleDeg(null, 5.0))
    }

    /** Transcribed from shipMotion.ts: sway plus the yaw term at the bow, in knots. */
    @Test
    fun `bow sway adds the turn to the sway, and falls back to drift`() {
        // 1 m/s sway, 1 °/s ROT, 50 m forward: (1 + 0.017453 * 50) m/s.
        val expected = (1.0 + Math.toRadians(1.0) * 50) * MPS_TO_KNOTS
        assertEquals(expected, transverseAtKn(50.0, 1.0, 1.0, null, null, null)!!, 1e-9)
        // No sway: SOG·sin(drift) plus the same turn term.
        val drifted = transverseAtKn(50.0, null, 0.0, 10.0, 10.0, 0.0)!!
        assertEquals(10.0 * Math.sin(Math.toRadians(10.0)), drifted, 1e-9)
        assertNull("no ROT, no derived figure", transverseAtKn(50.0, null, null, 10.0, 10.0, 0.0))
    }

    @Test
    fun `rate of turn from headings unwraps across north`() {
        val w = SampleWindow(longArrayOf(0, 1_000, 2_000), floatArrayOf(358f, 0f, 2f))
        assertEquals(2.0, rotFromHeadings(w, 2_000)!!, 1e-6)
    }

    private fun vector(n: Int, x: Float, y: Float, z: Float): VectorWindow {
        val t = LongArray(n) { it * 100L }
        return VectorWindow(
            SampleWindow(t, FloatArray(n) { x }),
            SampleWindow(t, FloatArray(n) { y }),
            SampleWindow(t, FloatArray(n) { z }),
        )
    }

    @Test
    fun `a still phone lying flat is one g with gravity detected and no dynamic`() {
        val a = imuAnalysis(vector(50, 0f, 0f, STANDARD_GRAVITY), ImuFrame.Logline, "auto", 4_900, 60_000)
        assertNotNull(a)
        assertTrue(a!!.gravityIncluded)
        assertEquals(1f, a.totalG, 1e-4f)
        assertEquals(0f, a.dynamicG, 1e-4f)
    }

    @Test
    fun `gravity-free input is left alone`() {
        val a = imuAnalysis(vector(50, 0f, 0.98f, 0f), ImuFrame.Logline, "auto", 4_900, 60_000)!!
        assertFalse(a.gravityIncluded)
        assertEquals(0.1f, a.dynamicG, 1e-3f)
        // Logline frame: forward is the phone's +y.
        assertEquals(0.1f, a.ggLatest!!.first, 1e-3f)
        assertEquals(0f, a.ggLatest!!.second, 1e-6f)
    }

    @Test
    fun `the Logline frame is a rotation of FRD, not a mirror`() {
        val w = vector(1, 1f, 2f, 3f)
        val logline = ImuFrame.Logline.vesselAxes(w).map { it.second.latest }
        assertEquals(listOf(2f, 1f, -3f), logline)
        assertEquals(listOf(1f, 2f, 3f), ImuFrame.Frd.vesselAxes(w).map { it.second.latest })
    }

    @Test
    fun `only the windows some card reads are wanted`() {
        val available = listOf(
            Topic("heading_true_north_deg", "gnss"),
            Topic("speed_over_ground_knots", "gnss"),
            Topic("battery_state_of_charge_pct", "bms"),
        )
        val cards = listOf(MonitorCard("h", CardKind.Heading), MonitorCard("v", CardKind.Value))
        val wanted = wantedTopics(cards, available)
        assertEquals(
            setOf(Topic("heading_true_north_deg", "gnss"), Topic("speed_over_ground_knots", "gnss")),
            wanted,
        )
        assertTrue("an unused plot series is not an input", MonitorCard("p", CardKind.Plot).inputs().size == 1)
    }
}
