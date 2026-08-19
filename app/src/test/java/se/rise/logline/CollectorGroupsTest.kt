package se.rise.logline

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.COLLECTOR_GROUPS
import se.rise.logline.publish.START_TIME_SUBJECTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collector grouping is what turns a per-subject switch into a sensor that is actually released.
 *
 * A subject missing from [COLLECTOR_GROUPS] fails **silently and expensively**: the sink gate still
 * keeps its samples off the bus and out of the file, so nothing looks broken, while its listener stays
 * registered for the whole run and the phone goes on paying for a stream nobody asked for. That is the
 * shape of miss the subject registry exists to prevent, so it is pinned here.
 */
class CollectorGroupsTest {

    @Test
    fun `every sampled subject comes off exactly one collector`() {
        val counted = COLLECTOR_GROUPS.flatMap { it.subjects }.groupingBy { it }.eachCount()
        val sampled = PublishedSubject.entries.filter { !it.eventDriven }

        sampled.forEach { entry ->
            assertEquals("$entry is published by exactly one collector", 1, counted[entry] ?: 0)
        }
        assertEquals(sampled.size, counted.size)
    }

    /**
     * An event-driven subject has no collector, and must not acquire one by accident.
     *
     * There is no sensor stream behind it — `SensorPublisher.mark()` publishes it on demand — so a
     * `CollectorGroup` naming it would describe a listener that does not exist and give `supervise()` a
     * collector to start and stop for nothing.
     */
    @Test
    fun `an event-driven subject has no collector`() {
        val collected = COLLECTOR_GROUPS.flatMap { it.subjects }.toSet()

        PublishedSubject.entries.filter { it.eventDriven }.forEach { entry ->
            assertTrue("$entry is event-driven but is on a collector", entry !in collected)
        }
    }

    @Test
    fun `collector names are unique`() {
        val names = COLLECTOR_GROUPS.map { it.name }

        assertEquals(names.toSet().size, names.size)
    }

    @Test
    fun `no collector is empty`() {
        COLLECTOR_GROUPS.forEach { assertTrue("${it.name} publishes nothing", it.subjects.isNotEmpty()) }
    }

    /**
     * Subjects that ride another's sample stream must be on that stream's collector — otherwise
     * switching the owner off would strand them on a collector that no longer runs, or switching the
     * follower off would leave the owner's listener attached for nothing.
     */
    @Test
    fun `a following subject sits with the subject it follows`() {
        PublishedSubject.entries.filter { it.rateOwner != null }.forEach { follower ->
            val owner = PublishedSubject.forSubject(follower.rateOwner!!)!!
            val group = COLLECTOR_GROUPS.single { follower in it.subjects }
            assertTrue(
                "$follower follows ${owner.subject} but is on the '${group.name}' collector",
                owner in group.subjects,
            )
        }
    }

    /**
     * The three that cannot be switched on mid-run.
     *
     * Audio and the stills because their foreground-service type is fixed at `startForeground`. Video
     * for a second reason as well: it shares the camera collector with the stills, and `supervise()`
     * only starts a collector on the all-off → any-on edge — so switching video on while the
     * time-lapse was already running would rebind nothing and produce no frame, silently.
     */
    @Test
    fun `audio and both camera subjects are the start-time subjects`() {
        assertEquals(
            setOf(
                PublishedSubject.AUDIO,
                PublishedSubject.IMAGE_COMPRESSED,
                PublishedSubject.VIDEO_COMPRESSED,
            ),
            START_TIME_SUBJECTS,
        )
    }
}
