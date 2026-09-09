package se.rise.logline

import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.ChangeArea
import se.rise.logline.calibrate.EulerDeg
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.PlatformZero
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.calibrationChanges
import se.rise.logline.calibrate.revertArea
import se.rise.logline.calibrate.turnDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an edit is about to replace.
 *
 * The interesting cases are the ones a whole-object `!=` gets wrong. Every capture rewrites
 * `capturedAtEpochMillis`, and re-derives `samples`, `scatterM` and both accuracies — so a naive
 * comparison reports "changed" on a re-measure of the same point, which is the one answer that would
 * teach somebody to stop reading these lines.
 */
class CalibrationDiffTest {

    private fun zero(
        lat: Double = 57.4359,
        lon: Double = 12.0326,
        heading: Double = 20.0,
        source: HeadingSource = HeadingSource.COMPASS,
        baselineM: Double? = null,
        capture: CaptureMethod = CaptureMethod.GNSS_AVERAGE,
        accuracyM: Double? = 13.4,
        verticalAccuracyM: Double? = 26.8,
        samples: Int = 17,
        at: Long = 1_000L,
        altitudeM: Double? = 41.8,
    ) = PlatformZero(
        latitude = lat,
        longitude = lon,
        altitudeM = altitudeM,
        accuracyM = accuracyM,
        verticalAccuracyM = verticalAccuracyM,
        scatterM = 5.15,
        headingDeg = heading,
        headingSource = source,
        capture = capture,
        samples = samples,
        capturedAtEpochMillis = at,
        headingBaselineM = baselineM,
    )

    private fun platform(
        zero: PlatformZero? = zero(),
        sensors: List<SensorMount> = emptyList(),
        name: String = "Sealog",
    ) = PlatformCalibration.forName(name).copy(zero = zero, sensors = sensors)

    private fun sensor(
        frameId: String,
        label: String = frameId,
        translation: Vec3M = Vec3M(10.0, 2.0, -1.0),
        rotation: EulerDeg = EulerDeg.ZERO,
    ) = SensorMount(
        label = label,
        frameId = frameId,
        sensorType = SensorType.OTHER,
        translation = translation,
        rotation = rotation,
    )

    private fun List<se.rise.logline.calibrate.CalibrationChange>.summaries() = map { it.summary }

    // ── the noise cases, which are the reason this is not a `!=` ────────────────────────────────

    /**
     * **Re-capturing the same spot is not a position change.** The timestamp moves, the sample count
     * moves, the scatter and both accuracies move — and the platform has not.
     */
    @Test
    fun `a re-capture at the same point reports no movement`() {
        val before = platform(zero(at = 1_000, samples = 17, accuracyM = 13.4))
        val after = platform(zero(at = 9_999, samples = 23, accuracyM = 11.2))

        val moved = calibrationChanges(before, after).summaries().filter { it.contains("moved") }

        assertTrue("nothing moved, so nothing should say it did: $moved", moved.isEmpty())
    }

    /** But it *is* a provenance change, because how well it is known did change. */
    @Test
    fun `a re-capture still reports what the accuracy became`() {
        val before = platform(zero(accuracyM = 13.4, samples = 17))
        val after = platform(zero(accuracyM = 3.2, samples = 40))

        val change = calibrationChanges(before, after).single { it.area == ChangeArea.ZERO }

        assertEquals("Averaged fix ±13.4 m, 17 samples", change.was)
    }

    /** A save stamp is not an edit. */
    @Test
    fun `a changed update timestamp alone is not a change`() {
        val before = platform()
        val after = before.copy(updatedAtEpochMillis = 9_999L)

        assertTrue(calibrationChanges(before, after).isEmpty())
    }

    @Test
    fun `an unedited platform has nothing to say`() {
        assertTrue(calibrationChanges(platform(), platform()).isEmpty())
    }

    /** Nothing to differ from while a platform is being created. */
    @Test
    fun `a new platform reports no changes`() {
        assertTrue(calibrationChanges(null, platform()).isEmpty())
    }

    // ── positions and angles ────────────────────────────────────────────────────────────────────

    /**
     * The zero's move is a ground distance. A hundredth of a degree of latitude is about 1.1 km, and
     * the same step in longitude at 57°N is about 600 m — reporting a degree difference would make
     * those the same number.
     */
    @Test
    fun `a moved zero reports the distance on the ground`() {
        val before = platform(zero(lat = 57.4359))
        val after = platform(zero(lat = 57.4359 + 0.001))

        val change = calibrationChanges(before, after).single { it.summary.contains("moved") }

        assertTrue("expected about 111 m, got: ${change.summary}", change.summary.contains("111."))
        assertEquals("57.43590°, 12.03260°", change.was)
    }

    /**
     * **The short way round, and the side it went.** 350° to 10° is twenty degrees to starboard, not
     * three hundred and forty to port — the same rule `circularMeanDegrees` exists for.
     */
    @Test
    fun `a heading turn is reported the short way round`() {
        assertEquals(20.0, turnDegrees(350.0, 10.0), 1e-9)
        assertEquals(-20.0, turnDegrees(10.0, 350.0), 1e-9)
        assertEquals(180.0, turnDegrees(0.0, 180.0), 1e-9)
        // A reversal comes back positive rather than on the sign of the last bit.
        assertEquals(180.0, turnDegrees(180.0, 0.0), 1e-9)

        val change = calibrationChanges(platform(zero(heading = 350.0)), platform(zero(heading = 10.0)))
            .single { it.area == ChangeArea.FORWARD }
        assertEquals("Forward axis turned 20° right", change.summary)
        assertEquals("350° compass", change.was)
    }

    // ── what a change costs ─────────────────────────────────────────────────────────────────────

    /**
     * Losing both accuracies stops the zero publishing a covariance — `SensorPublisher` emits one
     * only when it holds both, so the branch silently stops firing. Nothing else on the screen would
     * mention it.
     */
    @Test
    fun `replacing an averaged fix with a map pick says the covariance goes`() {
        val before = platform(zero(capture = CaptureMethod.GNSS_AVERAGE, accuracyM = 13.4, verticalAccuracyM = 26.8))
        val after = platform(
            zero(capture = CaptureMethod.MAP, accuracyM = null, verticalAccuracyM = null, samples = 0)
        )

        val change = calibrationChanges(before, after).single { it.area == ChangeArea.ZERO }

        assertEquals("The zero point stops publishing a position covariance", change.cost)
    }

    /** And losing the baseline length loses the one number that says what the angle is worth. */
    @Test
    fun `replacing a map baseline with a compass says the length goes`() {
        val before = platform(zero(source = HeadingSource.MAP_BASELINE, baselineM = 77.0))
        val after = platform(zero(source = HeadingSource.COMPASS, baselineM = null))

        val change = calibrationChanges(before, after).single { it.summary.contains("Axis now from") }

        assertEquals("Map baseline, 77 m baseline", change.was)
        assertTrue(change.cost!!.contains("what the angle is worth"))
    }

    /** Renaming the entity id is a move, not a rename: every key this platform publishes on changes. */
    @Test
    fun `changing the entity id says every subject moves`() {
        val before = platform()
        val after = before.copy(entityId = "sealog-2")

        val change = calibrationChanges(before, after).single { it.summary.contains("Entity id") }

        assertTrue(change.cost!!.contains("moves to the new id"))
    }

    // ── sensors ─────────────────────────────────────────────────────────────────────────────────

    /**
     * **Matched by `frame_id`, not by index.** Deleting the first of three is one removal; by index
     * it would read as two moves and a removal, which is three wrong statements about two sensors
     * nobody touched.
     */
    @Test
    fun `deleting the first of three sensors reports one removal`() {
        val before = platform(sensors = listOf(sensor("a"), sensor("b"), sensor("c")))
        val after = platform(sensors = listOf(sensor("b"), sensor("c")))

        val changes = calibrationChanges(before, after).filter { it.area == ChangeArea.SENSORS }

        assertEquals(listOf("a removed"), changes.summaries())
    }

    @Test
    fun `a moved sensor reports how far, and an added one reports itself`() {
        val before = platform(sensors = listOf(sensor("a", translation = Vec3M(10.0, 2.0, -1.0))))
        val after = platform(
            sensors = listOf(
                sensor("a", translation = Vec3M(10.5, 2.0, -1.0)),
                sensor("b"),
            )
        )

        val changes = calibrationChanges(before, after).filter { it.area == ChangeArea.SENSORS }

        assertTrue(changes.summaries().contains("b added"))
        assertEquals("a moved 0.50 m", changes.single { it.summary.startsWith("a moved") }.summary)
    }

    /** A sensor keeps its identity through a rename, because the id on the wire has not moved. */
    @Test
    fun `renaming a sensor is one change, not a removal and an addition`() {
        val before = platform(sensors = listOf(sensor("os2", label = "os2")))
        val after = platform(sensors = listOf(sensor("os2", label = "Ouster")))

        val changes = calibrationChanges(before, after).filter { it.area == ChangeArea.SENSORS }

        assertEquals(listOf("os2 renamed to Ouster"), changes.summaries())
    }

    // ── the zero being set or cleared ───────────────────────────────────────────────────────────

    @Test
    fun `removing the zero says the platform stops publishing a position`() {
        val change = calibrationChanges(platform(), platform(zero = null))
            .single { it.area == ChangeArea.ZERO }

        assertTrue(change.cost!!.contains("stops publishing a position"))
    }

    /** A first capture replaces nothing, so there is no "was". */
    @Test
    fun `setting a zero for the first time has nothing to state as before`() {
        val change = calibrationChanges(platform(zero = null), platform())
            .single { it.area == ChangeArea.ZERO }

        assertEquals("Zero point set", change.summary)
        assertNull(change.was)
    }

    // ── reverting ───────────────────────────────────────────────────────────────────────────────

    /**
     * **The zero and the forward axis are exact complements.** They live on one object, so reverting
     * one must not touch the other — otherwise putting a position back would silently undo a forward
     * axis established after it moved.
     */
    @Test
    fun `reverting the zero leaves the heading, and reverting the heading leaves the zero`() {
        val saved = platform(zero(lat = 57.4359, heading = 20.0, source = HeadingSource.COMPASS))
        val draft = platform(
            zero(lat = 57.5000, heading = 144.0, source = HeadingSource.MAP_BASELINE, baselineM = 77.0)
        )

        val zeroBack = revertArea(saved, draft, ChangeArea.ZERO)
        assertEquals(57.4359, zeroBack.zero!!.latitude, 1e-9)
        assertEquals(144.0, zeroBack.zero!!.headingDeg, 1e-9)
        assertEquals(HeadingSource.MAP_BASELINE, zeroBack.zero!!.headingSource)

        val headingBack = revertArea(saved, draft, ChangeArea.FORWARD)
        assertEquals(57.5000, headingBack.zero!!.latitude, 1e-9)
        assertEquals(20.0, headingBack.zero!!.headingDeg, 1e-9)
        assertEquals(HeadingSource.COMPASS, headingBack.zero!!.headingSource)
        assertNull(headingBack.zero!!.headingBaselineM)
    }

    /** Reverting both in either order lands on exactly what was saved. */
    @Test
    fun `reverting both halves restores the saved zero whichever order they go in`() {
        val saved = platform(zero(lat = 57.4359, heading = 20.0))
        val draft = platform(zero(lat = 57.5000, heading = 144.0, source = HeadingSource.MAP_BASELINE))

        val zeroFirst = revertArea(saved, revertArea(saved, draft, ChangeArea.ZERO), ChangeArea.FORWARD)
        val headingFirst = revertArea(saved, revertArea(saved, draft, ChangeArea.FORWARD), ChangeArea.ZERO)

        assertEquals(saved.zero, zeroFirst.zero)
        assertEquals(saved.zero, headingFirst.zero)
        assertTrue(calibrationChanges(saved, zeroFirst).isEmpty())
    }

    /** One sensor at a time, by the id that identifies it on the wire. */
    @Test
    fun `reverting one sensor leaves the others alone`() {
        val saved = platform(
            sensors = listOf(sensor("a", translation = Vec3M(1.0, 0.0, 0.0)), sensor("b"))
        )
        val draft = platform(
            sensors = listOf(
                sensor("a", translation = Vec3M(9.0, 0.0, 0.0)),
                sensor("b", translation = Vec3M(9.0, 9.0, 9.0)),
            )
        )

        val back = revertArea(saved, draft, ChangeArea.SENSORS, frameId = "a")

        assertEquals(1.0, back.sensors.first { it.frameId == "a" }.translation.x, 1e-9)
        assertEquals(9.0, back.sensors.first { it.frameId == "b" }.translation.x, 1e-9)
    }

    /** A sensor deleted in the draft comes back, and one added is taken away again. */
    @Test
    fun `reverting restores a deleted sensor and removes an added one`() {
        val saved = platform(sensors = listOf(sensor("a")))
        val deleted = platform(sensors = emptyList())
        val added = platform(sensors = listOf(sensor("a"), sensor("b")))

        assertEquals(listOf("a"), revertArea(saved, deleted, ChangeArea.SENSORS, "a").sensors.map { it.frameId })
        assertEquals(listOf("a"), revertArea(saved, added, ChangeArea.SENSORS, "b").sensors.map { it.frameId })
    }
}
