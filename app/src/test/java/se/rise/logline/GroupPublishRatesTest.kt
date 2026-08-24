package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.rise.logline.config.Settings
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects
import se.rise.logline.publish.PublishDecimator
import se.rise.logline.publish.publishIntervals
import se.rise.logline.sensors.SensorRate

/**
 * Several subjects riding **one** sample stream, each going out at its own rate.
 *
 * The pieces were each covered — the rate resolution in `RateSplitTest`, the thinning in
 * `PublishDecimatorTest` — and the combination was not, because the one place they meet used to be a
 * private member of `SensorPublisher`, a class that cannot be built without Android and Zenoh. So it
 * had only ever been checked by hand on a Pixel 6. `publishIntervals` is now top-level and pure, which
 * is enough to drive the whole resolution here: real `Settings`, the real registry, the real intervals
 * and the real decimators.
 *
 * The simulation is exactly the shape of the thing being modelled. `runLocation` gets **one** callback
 * and hands the same sample to every GNSS subject's sink; each sink holds its own decimator. So the
 * loop below ticks once per callback and offers that instant to every subject, which is the property
 * that matters: thinning happens per subject, never at the callback, or turning the fix down would drag
 * speed and course down with it.
 *
 * What this still does not reach is `SubjectSink.emit` itself — that `wrap()` runs first and always, so
 * the file keeps every sample whatever the wire does, and that a decimated sample returns success
 * rather than null. Those need the publisher and are checked on a device.
 */
class GroupPublishRatesTest {

    private val base = Settings(
        realm = "rise",
        entityId = "phone",
        routerEndpoints = listOf("tls/router.example.com:443"),
        locationSource = "phone",
        imuSource = "phone",
        recordAllMax = false,
    )

    /** One `Location` callback per record tick, offered to every subject that rides it. */
    private fun publishCounts(
        settings: Settings,
        subjects: List<PublishedSubject>,
        callbackHz: Double,
        seconds: Int,
    ): Map<PublishedSubject, Int> {
        val intervals = publishIntervals(settings)
        // One decimator per subject, which is what `SubjectSink` does — the whole reason a shared
        // listener can serve subjects running at different rates.
        val decimators = subjects.associateWith { intervals[it]?.let(::PublishDecimator) }
        val counts = subjects.associateWith { 0 }.toMutableMap()
        val stepNanos = (1_000_000_000.0 / callbackHz).toLong()
        var now = 0L
        repeat((seconds * callbackHz).toInt()) {
            subjects.forEach { subject ->
                // Absent from the map means "publish everything", the same reading `SubjectSink` takes.
                if (decimators.getValue(subject)?.due(now) != false) {
                    counts[subject] = counts.getValue(subject) + 1
                }
            }
            now += stepNanos
        }
        return counts
    }

    /**
     * **The case measured by hand on the phone**: the fix at 1 Hz and course at 0.2 Hz, off one
     * callback. Over a minute that is 60 positions and 12 courses — four different wire rates from one
     * `Location`, as the note in CLAUDE.md records.
     */
    @Test
    fun `subjects riding one callback each publish at their own rate`() {
        val settings = base.copy(
            sensorRates = mapOf(
                Subjects.LOCATION_FIX to SensorRate.Hz(1.0),
                Subjects.COURSE_OVER_GROUND_DEG to SensorRate.Hz(0.2),
            ),
            recordRates = mapOf(Subjects.LOCATION_FIX to SensorRate.Hz(1.0)),
        )
        val group = listOf(
            PublishedSubject.LOCATION_FIX,
            PublishedSubject.SPEED_OVER_GROUND,
            PublishedSubject.COURSE_OVER_GROUND,
        )

        val counts = publishCounts(settings, group, callbackHz = 1.0, seconds = 60)

        assertEquals(60, counts.getValue(PublishedSubject.LOCATION_FIX))
        // Untouched, so it follows the fix — the point being that turning course down did not move it.
        assertEquals(60, counts.getValue(PublishedSubject.SPEED_OVER_GROUND))
        assertEquals(12, counts.getValue(PublishedSubject.COURSE_OVER_GROUND))
    }

    /**
     * **Thinning one subject must not thin the callback.** This is the assertion the design exists for:
     * a decimator at the `Location` callback rather than at each sink would take all three down to the
     * slowest, and every count here would read 12.
     */
    @Test
    fun `the slowest subject in a group does not hold the others back`() {
        val settings = base.copy(
            sensorRates = mapOf(
                Subjects.LOCATION_FIX to SensorRate.Hz(1.0),
                Subjects.COURSE_OVER_GROUND_DEG to SensorRate.Hz(0.1),
            ),
            recordRates = mapOf(Subjects.LOCATION_FIX to SensorRate.Hz(1.0)),
        )
        val group = listOf(PublishedSubject.LOCATION_FIX, PublishedSubject.COURSE_OVER_GROUND)

        val counts = publishCounts(settings, group, callbackHz = 1.0, seconds = 60)

        assertEquals(60, counts.getValue(PublishedSubject.LOCATION_FIX))
        assertEquals(6, counts.getValue(PublishedSubject.COURSE_OVER_GROUND))
    }

    /**
     * **A sensor delivering faster than asked is thinned down to the request**, which is the other job
     * of the decimator and not a rate setting at all: Android runs a sensor at the fastest rate *any*
     * client asked for, so the barometer arrives at ~12.5 Hz when this app asked for 1 Hz and Google
     * Play Services asked for 10. Recording takes all of it; the wire must not.
     *
     * **58 rather than 60, and that is the honest answer.** The decimator can only publish on samples
     * it is given, and a 12.5 Hz stream is quantised at 80 ms — so a 1 Hz request goes out on the first
     * tick at or after a full second, i.e. every 13th, i.e. every 1.04 s. It rounds *down* to the
     * nearest achievable rate rather than up, which is the right way for a limiter to be wrong. Worth
     * knowing before reading a row that says `0.96 Hz · pub 1.0` as a bug: the two disagree by exactly
     * one sample interval of whatever is arriving, and the faster the source the smaller the gap.
     */
    @Test
    fun `a stream arriving faster than requested is thinned to the request`() {
        val settings = base.copy(
            recordRates = mapOf(Subjects.AIR_PRESSURE_PA to SensorRate.Hz(25.0)),
            sensorRates = mapOf(Subjects.AIR_PRESSURE_PA to SensorRate.Hz(1.0)),
        )

        val counts = publishCounts(
            settings,
            listOf(PublishedSubject.AIR_PRESSURE),
            callbackHz = 12.5,
            seconds = 60,
        )

        assertEquals(58, counts.getValue(PublishedSubject.AIR_PRESSURE))
    }

    /**
     * **A subject publishing at its record rate gets no entry at all**, and so no decimator — the map is
     * the app's record of "this one is thinned". Asserted directly because it is what makes the
     * unthinned majority free rather than merely cheap.
     */
    @Test
    fun `a subject whose rates agree is absent from the interval map`() {
        val settings = base.copy(
            recordRates = mapOf(Subjects.AIR_PRESSURE_PA to SensorRate.Hz(1.0)),
            sensorRates = mapOf(Subjects.AIR_PRESSURE_PA to SensorRate.Hz(1.0)),
        )

        assertNull(publishIntervals(settings)[PublishedSubject.AIR_PRESSURE])
    }

    /**
     * **`neverThinned` reaches the publish path**, not just the subject page that states it. Audio and
     * video carry a chunk length rather than a sample rate, so dropping one is dropping a second of
     * sound, and the exemption has to be enforced where the dropping would happen.
     */
    @Test
    fun `a never-thinned subject is never given an interval`() {
        // **Both maps, and that is what makes this test bite.** Written with `sensorRates` alone it
        // passed against a build with the exemption deleted: these subjects do not record continuously,
        // so `recordRate` falls back to `sensorRates` for them — record equalled publish, and the
        // `publish == record` guard returned first. The exemption was never reached, and the test was
        // true for a reason that had nothing to do with what it claimed to check.
        val settings = base.copy(
            recordRates = PublishedSubject.entries
                .filter { it.neverThinned }
                .associate { it.subject to SensorRate.Hz(10.0) },
            sensorRates = PublishedSubject.entries
                .filter { it.neverThinned }
                .associate { it.subject to SensorRate.Hz(0.1) },
        )
        val intervals = publishIntervals(settings)

        PublishedSubject.entries.filter { it.neverThinned }.forEach {
            // The rates genuinely differ, so only the exemption can be keeping it out of the map.
            assertNotEquals(settings.recordRate(it.subject), settings.publishRate(it.subject))
            assertNull("${it.subject} is exempt from thinning", intervals[it])
        }
    }
}
