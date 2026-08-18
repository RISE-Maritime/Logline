package se.rise.logline

import se.rise.logline.publish.RuntimeEstimator
import se.rise.logline.publish.RuntimeLimit
import se.rise.logline.publish.timeLeft
import se.rise.logline.publish.RuntimeEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The time-left estimate, which is a prediction shown to someone deciding whether to start a long run.
 *
 * Every case here is about *refusing* to predict as much as it is about predicting: a number that is
 * quietly wrong is worse than no number, because the whole point of it is to be trusted without being
 * checked.
 */
class RuntimeEstimateTest {

    private val minute = 60_000L
    private val hour = 3_600_000L

    /** Feed a constant drain, one reading every five seconds, and return the last estimate. */
    private fun run(
        estimator: RuntimeEstimator,
        startFuel: Double,
        perHour: Double,
        forMillis: Long,
        fromMillis: Long = 0L,
        charging: Boolean = false,
        stepMillis: Long = 5_000L,
    ): RuntimeEstimate {
        var last: RuntimeEstimate = RuntimeEstimate.Unknown
        var t = 0L
        while (t <= forMillis) {
            val fuel = startFuel - perHour * t / hour
            // What the battery collector does: charging is the caller's fact, not the estimator's.
            last = if (charging) {
                estimator.reset()
                RuntimeEstimate.Charging
            } else {
                estimator.record(fromMillis + t, fuel)
            }
            t += stepMillis
        }
        return last
    }

    @Test
    fun `a steady drain predicts time to empty`() {
        // 100% left, 10% an hour: ten hours, minus the ten minutes already spent measuring it.
        val estimate = run(RuntimeEstimator(), startFuel = 100.0, perHour = 10.0, forMillis = 10 * minute)

        assertTrue("expected an estimate, got $estimate", estimate is RuntimeEstimate.Remaining)
        val remaining = estimate as RuntimeEstimate.Remaining
        assertEquals(10.0, remaining.drainPerHour, 0.01)
        // 98.33% left at ten minutes in, at 10%/h.
        assertEquals(9.83, remaining.millis.toDouble() / hour, 0.02)
    }

    /**
     * The unit cancels. The estimator is fed microamp-hours where the gauge reports them and percent
     * where it does not, and it cannot tell which — so the same drain in different units has to give
     * the same answer, or one of the two device paths is silently wrong.
     */
    @Test
    fun `the unit does not matter, only the ratio`() {
        val inPercent = run(RuntimeEstimator(), startFuel = 80.0, perHour = 8.0, forMillis = 10 * minute)
        val inMicroAmpHours =
            run(RuntimeEstimator(), startFuel = 3_600_000.0, perHour = 360_000.0, forMillis = 10 * minute)

        val a = (inPercent as RuntimeEstimate.Remaining).millis
        val b = (inMicroAmpHours as RuntimeEstimate.Remaining).millis
        assertEquals(a.toDouble(), b.toDouble(), hour * 0.01)
    }

    @Test
    fun `nothing is claimed before the drain has been measured`() {
        // Two minutes is below the floor: at a 1% gauge resolution this is noise, not a trend.
        val estimate = run(RuntimeEstimator(), startFuel = 50.0, perHour = 12.0, forMillis = 2 * minute)

        assertEquals(RuntimeEstimate.Unknown, estimate)
    }

    @Test
    fun `a gauge that has not moved says nothing rather than forever`() {
        val estimate = run(RuntimeEstimator(), startFuel = 64.0, perHour = 0.0, forMillis = 30 * minute)

        assertEquals(RuntimeEstimate.Unknown, estimate)
    }

    /** A drain slow enough to run for days is a gauge that has stalled, not four days of logging. */
    @Test
    fun `an implausibly slow drain is not reported`() {
        val estimate = run(RuntimeEstimator(), startFuel = 100.0, perHour = 0.5, forMillis = 30 * minute)

        assertEquals(RuntimeEstimate.Unknown, estimate)
    }

    @Test
    fun `charging is its own answer, not a missing one`() {
        val estimate = run(RuntimeEstimator(), startFuel = 40.0, perHour = -20.0, forMillis = 10 * minute, charging = true)

        assertEquals(RuntimeEstimate.Charging, estimate)
    }

    /**
     * Coming off charge must not resurrect the drain measured before it. The window is cleared while
     * charging, so the first minutes after unplugging report nothing rather than a stale number.
     */
    @Test
    fun `a spell on charge clears the history`() {
        val estimator = RuntimeEstimator()
        run(estimator, startFuel = 90.0, perHour = 10.0, forMillis = 20 * minute)
        run(estimator, startFuel = 88.0, perHour = -30.0, forMillis = 5 * minute, fromMillis = 21 * minute, charging = true)

        val justAfterUnplug =
            run(estimator, startFuel = 95.0, perHour = 10.0, forMillis = minute, fromMillis = 27 * minute)

        assertEquals(RuntimeEstimate.Unknown, justAfterUnplug)
    }

    /**
     * The window is what makes the number follow the run: switching the camera on doubles the drain,
     * and an estimate averaged over the whole run would keep promising time the phone no longer has.
     */
    @Test
    fun `the estimate follows a load that changed`() {
        val estimator = RuntimeEstimator()
        // Forty minutes of light load, then twenty-five of heavy — longer than the window, so the light
        // half has fallen out of it entirely.
        run(estimator, startFuel = 100.0, perHour = 4.0, forMillis = 40 * minute)
        val heavy = run(
            estimator,
            startFuel = 97.3,
            perHour = 20.0,
            forMillis = 25 * minute,
            fromMillis = 40 * minute + 5_000L,
        )

        val drain = (heavy as RuntimeEstimate.Remaining).drainPerHour
        assertEquals("the window still has the light-load samples in it", 20.0, drain, 0.5)
    }

    /**
     * A jump resets rather than predicting from it. Two ways this happens for real: a fuel gauge
     * recalibrating after a deep discharge, and the app switching gauge unit between microamp-hours and
     * percent — which would otherwise read as the battery losing 99.99% of itself in five seconds.
     */
    @Test
    fun `an implausible jump is not a drain`() {
        val estimator = RuntimeEstimator()
        run(estimator, startFuel = 3_600_000.0, perHour = 360_000.0, forMillis = 20 * minute)

        val afterSwitch = estimator.record(21 * minute, 88.0)

        assertEquals(RuntimeEstimate.Unknown, afterSwitch)
    }

    /** A backwards clock — the epoch offset is re-read per sample, so an NTP step can do this. */
    @Test
    fun `a clock that goes backwards restarts the window`() {
        val estimator = RuntimeEstimator()
        run(estimator, startFuel = 70.0, perHour = 10.0, forMillis = 20 * minute)

        val afterStep = estimator.record(minute, 68.0)

        assertEquals(RuntimeEstimate.Unknown, afterStep)
    }

    @Test
    fun `a new run starts with no history`() {
        val estimator = RuntimeEstimator()
        run(estimator, startFuel = 70.0, perHour = 10.0, forMillis = 20 * minute)

        estimator.reset()

        assertEquals(
            RuntimeEstimate.Unknown,
            estimator.record(21 * minute, 66.0),
        )
    }
}

/**
 * Which of the two ends comes first.
 *
 * The battery and the disk are independent — a phone on a charger still fills its storage, and a phone
 * with 200 GB free still goes flat — so the run ends at whichever is nearer, and the screen has to say
 * which one it means or the number is not actionable.
 */
class TimeLeftTest {

    private val hour = 3_600_000L
    private val minute = 60_000L

    private fun remaining(millis: Long) = RuntimeEstimate.Remaining(millis, drainPerHour = 1.0)

    @Test
    fun `the nearer end wins and is named`() {
        val left = timeLeft(battery = remaining(4 * hour), storage = remaining(40 * minute))

        assertEquals(RuntimeLimit.Storage, left?.limit)
        assertEquals(40 * minute, left?.millis)
    }

    @Test
    fun `the battery wins when it is the nearer one`() {
        val left = timeLeft(battery = remaining(2 * hour), storage = remaining(9 * hour))

        assertEquals(RuntimeLimit.Battery, left?.limit)
        assertEquals(2 * hour, left?.millis)
    }

    /** A flat battery stops the publishing too, not just the recording, so a tie is the battery's. */
    @Test
    fun `a tie goes to the battery`() {
        val left = timeLeft(battery = remaining(3 * hour), storage = remaining(3 * hour))

        assertEquals(RuntimeLimit.Battery, left?.limit)
    }

    @Test
    fun `one measured estimate is enough`() {
        assertEquals(
            RuntimeLimit.Battery,
            timeLeft(battery = remaining(5 * hour), storage = RuntimeEstimate.Unknown)?.limit,
        )
        assertEquals(
            RuntimeLimit.Storage,
            timeLeft(battery = RuntimeEstimate.Unknown, storage = remaining(5 * hour))?.limit,
        )
    }

    /** Plugged in and still recording: the disk is the only thing left that can end the run. */
    @Test
    fun `a charging phone still fills its disk`() {
        val left = timeLeft(battery = RuntimeEstimate.Charging, storage = remaining(90 * minute))

        assertEquals(RuntimeLimit.Storage, left?.limit)
        assertEquals(90 * minute, left?.millis)
    }

    @Test
    fun `nothing measured says nothing`() {
        assertNull(timeLeft(RuntimeEstimate.Unknown, RuntimeEstimate.Unknown))
        assertNull(timeLeft(RuntimeEstimate.Charging, RuntimeEstimate.Unknown))
    }
}
