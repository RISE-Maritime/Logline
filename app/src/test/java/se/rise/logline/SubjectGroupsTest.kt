package se.rise.logline

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.PublisherStatus
import se.rise.logline.publish.SubjectStatus
import se.rise.logline.ui.SubjectHealth
import se.rise.logline.ui.groupBadge
import se.rise.logline.ui.groupSummary
import se.rise.logline.keelson.Subjects
import se.rise.logline.ui.labelOf
import se.rise.logline.ui.subjectGroups
import se.rise.logline.ui.subjectHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main screen's sections are derived from the registry rather than listed, which is the rule the
 * registry exists to enforce. These tests are what keeps a new subject from quietly falling out of the
 * UI — the exact failure mode the registry replaced.
 */
class SubjectGroupsTest {

    private val now = 1_700_000_000_000L

    private fun live(samples: Long, hz: Double, ageMillis: Long = 0): SubjectStatus {
        val last = now - ageMillis
        val span = if (samples >= 2) ((samples - 1) * 1_000.0 / hz).toLong() else 0L
        return SubjectStatus(
            samplesPublished = samples,
            lastPublishEpochMillis = last,
            firstPublishEpochMillis = last - span,
        )
    }

    @Test
    fun `every subject appears exactly once`() {
        val grouped = subjectGroups().flatMap { it.entries }

        assertEquals("nothing dropped or duplicated", PublishedSubject.entries.size, grouped.size)
        assertEquals(PublishedSubject.entries.toSet(), grouped.toSet())
    }

    /**
     * Grouping is allowed to move a subject between sections but never within one — the registry's
     * order is deliberate, and the radio entries interleave cellular and wifi, which is exactly the
     * interleaving the sections undo.
     */
    @Test
    fun `each group keeps the registry's order`() {
        subjectGroups().forEach { group ->
            assertEquals(
                group.title,
                PublishedSubject.entries.filter { it in group.entries },
                group.entries,
            )
        }
    }

    @Test
    fun `groups are the five sources, with radio split by link`() {
        assertEquals(
            listOf("GNSS", "IMU", "Device", "Radio · cellular", "Radio · wifi", "Platform calibration"),
            subjectGroups().map { it.title },
        )
    }

    /**
     * Two entries may share a subject, and when they do the screen has to tell them apart.
     *
     * There are two legitimate ways, and each duplicated subject must use one of them:
     *
     * - `radio_rssi_dbm` is published from both links and both rows are called just "RSSI", so the
     *   *heading* carries it — `Radio · cellular` against `Radio · wifi`. That is what `sourceInTitle`
     *   promises, and a row name that short is only honest while it holds.
     * - `location_fix` is the phone's live position under GNSS and the platform's surveyed zero under Platform
     *   calibration. Those headings do not name a source id, so the *rows* differ instead: "Position"
     *   against "Zero point". Two rows both reading "Position", one of them a fix from a survey days
     *   ago, is the confusion this exists to prevent.
     */
    @Test
    fun `a subject published twice is distinguishable on screen`() {
        val duplicated = PublishedSubject.entries
            .groupBy { it.subject }
            .filterValues { it.size > 1 }

        assertEquals(
            setOf(Subjects.RADIO_RSSI_DBM, Subjects.LOCATION_FIX),
            duplicated.keys,
        )

        duplicated.forEach { (subject, entries) ->
            val groups = subjectGroups().filter { group -> group.entries.any { it in entries } }
            val headingsNameTheSource = groups.all { it.sourceInTitle }
            val rowNamesDiffer = entries.map { labelOf(it).name }.toSet().size == entries.size
            assertTrue(
                "$subject is published ${'$'}{entries.size} times and reads the same either way",
                headingsNameTheSource || rowNamesDiffer,
            )
        }
    }

    @Test
    fun `a subject publishing now is live`() {
        assertEquals(
            SubjectHealth.Live,
            subjectHealth(live(samples = 100, hz = 55.0), running = true, nowMillis = now),
        )
    }

    /**
     * The state this whole thing exists for. A subject that published and stopped looks identical to a
     * healthy one if all you show is a running total — which is how one came to look missing when it
     * was not.
     */
    @Test
    fun `a subject that stopped is stalled, once its own intervals have passed`() {
        val fast = live(samples = 1_000, hz = 55.0, ageMillis = 11_000)
        assertEquals(SubjectHealth.Stalled, subjectHealth(fast, running = true, nowMillis = now))

        assertEquals(
            "still within the floor",
            SubjectHealth.Live,
            subjectHealth(live(1_000, 55.0, ageMillis = 9_000), running = true, nowMillis = now),
        )
    }

    /** A 0.2 Hz subject publishes every five seconds; a flat ten-second rule would libel it. */
    @Test
    fun `a slow subject is judged against its own rate`() {
        val slow = live(samples = 20, hz = 0.2, ageMillis = 12_000)
        assertEquals(SubjectHealth.Live, subjectHealth(slow, running = true, nowMillis = now))

        val reallyStopped = live(samples = 20, hz = 0.2, ageMillis = 40_000)
        assertEquals(SubjectHealth.Stalled, subjectHealth(reallyStopped, running = true, nowMillis = now))
    }

    @Test
    fun `the other states are told apart`() {
        assertEquals(
            SubjectHealth.Idle,
            subjectHealth(SubjectStatus(), running = false, nowMillis = now),
        )
        assertEquals(
            SubjectHealth.Waiting,
            subjectHealth(SubjectStatus(), running = true, nowMillis = now),
        )
        assertEquals(
            SubjectHealth.Failed,
            subjectHealth(SubjectStatus(failure = "collector died"), running = true, nowMillis = now),
        )
        assertEquals(
            "a missing sensor is not a fault",
            SubjectHealth.Unavailable,
            subjectHealth(SubjectStatus(), running = true, nowMillis = now, available = false),
        )
    }

    // ---- the heading's badge ----

    /**
     * The all-off case, which a section's master switch reaches in a single tap.
     *
     * A switched-off subject leaves the denominator, so a fully switched-off group has `total == 0` —
     * and `unavailable == total` is then trivially true, which had the badge announcing perfectly good
     * hardware as "not on this device".
     */
    @Test
    fun `a group with everything switched off says so`() {
        val imu = subjectGroups().first { it.title == "IMU" }
        val summary = groupSummary(
            entries = imu.entries,
            status = PublisherStatus(running = true),
            running = true,
            nowMillis = now,
            disabled = imu.entries.toSet(),
        )

        assertEquals(0, summary.total)
        assertEquals("off", groupBadge(summary, running = true))
    }

    /** `3/3 ✓` on its own would hide the two subjects somebody switched off. */
    @Test
    fun `a partly switched-off group counts the ones that are off`() {
        val battery = subjectGroups().first { it.title == "Device" }
        val off = battery.entries.take(2).toSet()
        val status = PublisherStatus(
            running = true,
            subjects = battery.entries.filterNot { it in off }.associateWith { live(samples = 100, hz = 1.0) },
        )

        val summary = groupSummary(battery.entries, status, running = true, nowMillis = now, disabled = off)
        val badge = groupBadge(summary, running = true)

        assertEquals(battery.entries.size - 2, summary.total)
        assertEquals(2, summary.off)
        assertTrue(badge, badge.endsWith("· 2 off"))
        assertTrue(badge, badge.startsWith("${summary.live}/${summary.total}"))
    }

    @Test
    fun `a group with nothing switched off says nothing about it`() {
        val gnss = subjectGroups().first { it.title == "GNSS" }
        val summary = groupSummary(gnss.entries, PublisherStatus(), running = false, nowMillis = now)

        assertEquals("${gnss.entries.size}", groupBadge(summary, running = false))
    }

    @Test
    fun `a group summary counts states and sums only what is live`() {
        val imu = subjectGroups().first { it.title == "IMU" }
        val (first, second) = imu.entries.take(2)
        val status = PublisherStatus(
            running = true,
            subjects = mapOf(
                first to live(samples = 1_000, hz = 50.0),
                second to live(samples = 1_000, hz = 50.0, ageMillis = 60_000),
            ),
        )

        val summary = groupSummary(imu.entries, status, running = true, nowMillis = now)

        assertEquals(imu.entries.size, summary.total)
        assertEquals(1, summary.live)
        assertEquals(1, summary.stalled)
        assertEquals("the stalled one contributes no rate", 50.0, summary.samplesPerSecond, 0.5)
        assertTrue(summary.needsAttention)
    }

    @Test
    fun `a quiet group needs no attention`() {
        val gnss = subjectGroups().first { it.title == "GNSS" }
        val summary = groupSummary(gnss.entries, PublisherStatus(), running = false, nowMillis = now)

        assertEquals(0, summary.live)
        assertEquals(0.0, summary.samplesPerSecond, 0.0)
        assertFalse(summary.needsAttention)
    }

    @Test
    fun `unavailable subjects are counted apart from failures`() {
        val device = subjectGroups().first { it.title == "Device" }
        val summary = groupSummary(
            entries = device.entries,
            status = PublisherStatus(running = true),
            running = true,
            nowMillis = now,
            unavailable = setOf(device.entries.first()),
        )

        assertEquals(1, summary.unavailable)
        assertEquals(0, summary.failed)
        assertFalse("a sensor this phone lacks is not a problem to flag", summary.needsAttention)
    }

    // ---- event-driven subjects ----

    /**
     * An annotation subject is healthy while it says nothing, which is the opposite of every other
     * subject here.
     *
     * Without the exemption a run nobody marked would show `Waiting` for its whole length, and one
     * marked once would show `Stalled` ten seconds later — a red group heading reporting a feature
     * working exactly as designed as broken.
     */
    @Test
    fun `an event-driven subject with no samples is live, not waiting`() {
        assertEquals(
            SubjectHealth.Live,
            subjectHealth(SubjectStatus(), running = true, nowMillis = now, eventDriven = true),
        )
    }

    @Test
    fun `an event-driven subject does not go stale`() {
        val marked = SubjectStatus(
            samplesPublished = 1,
            firstPublishEpochMillis = now - 3_600_000L,
            lastPublishEpochMillis = now - 3_600_000L,
        )

        assertEquals(
            SubjectHealth.Stalled,
            subjectHealth(marked, running = true, nowMillis = now),
        )
        assertEquals(
            SubjectHealth.Live,
            subjectHealth(marked, running = true, nowMillis = now, eventDriven = true),
        )
    }

    /** The exemption is about silence only — a real failure, or a switch, still outranks it. */
    @Test
    fun `an event-driven subject still reports failure, off and idle`() {
        assertEquals(
            SubjectHealth.Failed,
            subjectHealth(SubjectStatus(failure = "boom"), true, now, eventDriven = true),
        )
        assertEquals(
            SubjectHealth.Off,
            subjectHealth(SubjectStatus(), true, now, enabled = false, eventDriven = true),
        )
        assertEquals(
            SubjectHealth.Idle,
            subjectHealth(SubjectStatus(), running = false, nowMillis = now, eventDriven = true),
        )
    }

    /** `log_message` is the entry this exists for; if it stops being event-driven, this should fail. */
    @Test
    fun `log_message is the event-driven subject`() {
        assertEquals(
            listOf(PublishedSubject.LOG_MESSAGE),
            PublishedSubject.entries.filter { it.eventDriven },
        )
    }

}
