package se.rise.logline.ui

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SourceKind
import se.rise.logline.publish.PublisherStatus
import se.rise.logline.publish.SubjectStatus
import se.rise.logline.sensors.achievedHz

/**
 * The subject list, cut into sections a person can scan.
 *
 * Twenty-five subjects in one flat list means reading all of them to find the one that stopped. The cut
 * is [SourceKind], which the registry already knows, so this stays derived rather than becoming the
 * eleventh hand-maintained list of subjects. Radio splits further by source id: twelve rows under one
 * heading is the same wall of text in a different shape, and `cellular` and `wifi` are genuinely
 * different links.
 */
data class SubjectGroup(
    val title: String,
    val entries: List<PublishedSubject>,
    /** What the group *is*, so nothing downstream has to recognise it by its display title. */
    val source: SourceKind,
    /** The link, where one source kind covers several — `cellular` and `wifi`. Null otherwise. */
    val sourceId: String?,
    /**
     * True when the heading already names the source. It is what lets a row be called just "RSSI":
     * that subject appears under both `Radio · cellular` and `Radio · wifi`, and the heading is the
     * only thing telling the two apart — see `ui/SubjectLabels.kt`.
     */
    val sourceInTitle: Boolean,
)

/** Rows in registry order, grouped without reordering: the registry decides what comes first. */
fun subjectGroups(): List<SubjectGroup> =
    PublishedSubject.entries
        // Only the radio links split by source id. They are two different *pieces of hardware*
        // reporting the same subjects, so the heading is the only thing telling the rows apart. The
        // unfused position solutions also carry a fixed id and must not split: they belong beside the
        // fix they are compared against, and their rows say which is which in their own names.
        .groupBy { it.source to it.fixedSourceId.takeIf { _ -> it.source == SourceKind.RADIO } }
        .map { (key, entries) ->
            val (source, sourceId) = key
            SubjectGroup(
                title = groupTitle(source, sourceId),
                entries = entries,
                source = source,
                sourceId = sourceId,
                sourceInTitle = sourceId != null,
            )
        }

private fun groupTitle(source: SourceKind, sourceId: String?): String {
    val head = when (source) {
        // "GNSS" rather than "Location": it is what the hardware is called everywhere else in this app,
        // and it distinguishes the fix from the phone's other position-ish sensors.
        SourceKind.LOCATION -> "GNSS"
        SourceKind.IMU -> "IMU"
        SourceKind.DEVICE -> "Device"
        SourceKind.RADIO -> "Radio"
        // Not a sensor group: these two describe the platform the phone is mounted on, not the phone.
        SourceKind.CALIBRATION -> "Platform calibration"
    }
    return sourceId?.let { "$head · $it" } ?: head
}

/**
 * Whether the phone is keeping up with what it has been asked to do.
 *
 * Three states rather than two, and the middle one is the point of the whole thing. Before this the
 * screen went straight from a healthy recording readout to a red "N samples dropped" — the recorder's
 * queue holds ten thousand samples, about forty-five seconds of slack, and nothing reported the depth
 * climbing through it. The first thing anybody saw was data that had already been lost.
 *
 * Note what this deliberately does **not** claim. It covers the two backlogs the app can actually see:
 * the sensor flows, whose 64-slot buffers shed when a collector falls behind, and the recorder's queue.
 * Zenoh's egress queue is not among them and cannot be — every QoS profile is `DROP` and a `put`
 * returns success whether or not anything received it. This says the *phone* is keeping up; it says
 * nothing about the link.
 */
enum class ThroughputHealth {
    /** Nothing has been lost and the queue is not filling. */
    KeepingUp,

    /**
     * The queue has been deep enough to be worth knowing about, but nothing has been lost yet.
     *
     * A quarter of capacity is a judgement, not a measurement: it is far enough above the handful of
     * samples a healthy drain sits at to mean something, and far enough below full to leave time to
     * act. Read against the *peak*, because a 1 Hz poll against a ten-thousand-deep buffer will
     * otherwise sit at nearly zero and miss every burst.
     */
    UnderStrain,

    /** Samples have gone missing — shed by a sensor flow, or refused by the recorder's queue. */
    Losing,
}

/** The share of the recorder queue that counts as strain. */
private const val STRAIN_FRACTION = 0.25

fun throughputHealth(
    peakDepth: Long,
    capacity: Int,
    dropped: Long,
    shed: Long,
): ThroughputHealth = when {
    dropped > 0 || shed > 0 -> ThroughputHealth.Losing
    capacity > 0 && peakDepth >= capacity * STRAIN_FRACTION -> ThroughputHealth.UnderStrain
    else -> ThroughputHealth.KeepingUp
}

/**
 * Whether a subject is doing what it should.
 *
 * [Stalled] is the state this exists for: a subject that published and then quietly stopped looks
 * exactly like a healthy one if all you show is a total, which is how a subject came to look missing
 * when it was not.
 */
enum class SubjectHealth {
    /** Publishing, recently. */
    Live,

    /** Published earlier in this run, but nothing for several expected intervals. */
    Stalled,

    /** The run is going and this subject has not produced anything yet. */
    Waiting,

    /** A collector died or a publish failed. */
    Failed,

    /** No such sensor on this device — it was never going to publish. */
    Unavailable,

    /**
     * Switched off in settings, so it is not meant to be publishing.
     *
     * Distinct from [Unavailable] on purpose: the hardware is there and the fix is a switch, not a
     * different phone. Audio is the only one today, and it is off unless you turn it on — without this
     * state its row would sit at "Waiting for the first sample" for an entire run.
     */
    Off,

    /** Nothing is running. */
    Idle,
}

/**
 * A subject is stale after five of its own intervals, and never sooner than this.
 *
 * Self-calibrating on purpose: a fixed threshold would either flag the 0.2 Hz battery subjects
 * constantly or take a minute to notice a 55 Hz one had died.
 */
private const val STALE_FLOOR_MILLIS = 10_000L
private const val STALE_INTERVALS = 5

fun subjectHealth(
    status: SubjectStatus,
    running: Boolean,
    nowMillis: Long,
    available: Boolean = true,
    enabled: Boolean = true,
    /**
     * Set for a subject nobody samples — see [PublishedSubject.eventDriven].
     *
     * Silence is the healthy state for one of these, so the two checks that read silence as a problem
     * are skipped. Without this an annotation row spends every run either `Waiting`, because nobody has
     * marked anything, or `Stalled` ten seconds after the last mark — a red group heading reporting
     * that a feature working exactly as intended is broken.
     */
    eventDriven: Boolean = false,
): SubjectHealth = when {
    !available -> SubjectHealth.Unavailable
    !enabled -> SubjectHealth.Off
    status.failure != null -> SubjectHealth.Failed
    !running -> SubjectHealth.Idle
    eventDriven -> SubjectHealth.Live
    status.samplesPublished == 0L -> SubjectHealth.Waiting
    isStale(status, nowMillis) -> SubjectHealth.Stalled
    else -> SubjectHealth.Live
}

private fun isStale(status: SubjectStatus, nowMillis: Long): Boolean {
    if (status.lastPublishEpochMillis <= 0L) return false
    val age = nowMillis - status.lastPublishEpochMillis
    val hz = achievedHz(
        samples = status.samplesPublished,
        firstEpochMillis = status.firstPublishEpochMillis,
        lastEpochMillis = status.lastPublishEpochMillis,
    )
    val expected = hz?.takeIf { it > 0 }?.let { (STALE_INTERVALS * 1_000.0 / it).toLong() } ?: 0L
    return age > maxOf(STALE_FLOOR_MILLIS, expected)
}

/** A group heading's right-hand side: enough to know whether to look inside. */
data class GroupSummary(
    /** How many subjects in the group are *meant* to be publishing — switched-off ones excluded. */
    val total: Int,
    val live: Int,
    val stalled: Int,
    val failed: Int,
    val unavailable: Int,
    val off: Int,
    /** Summed achieved rate of the subjects that are actually producing. */
    val samplesPerSecond: Double,
) {
    val needsAttention: Boolean get() = stalled > 0 || failed > 0
}

fun groupSummary(
    entries: List<PublishedSubject>,
    status: PublisherStatus,
    running: Boolean,
    nowMillis: Long,
    unavailable: Set<PublishedSubject> = emptySet(),
    disabled: Set<PublishedSubject> = emptySet(),
): GroupSummary {
    var live = 0
    var stalled = 0
    var failed = 0
    var missing = 0
    var off = 0
    var rate = 0.0
    entries.forEach { entry ->
        val subjectStatus = status[entry]
        val health = subjectHealth(
            status = subjectStatus,
            running = running,
            nowMillis = nowMillis,
            available = entry !in unavailable,
            enabled = entry !in disabled,
            eventDriven = entry.eventDriven,
        )
        when (health) {
            SubjectHealth.Live -> {
                live++
                rate += achievedHz(
                    samples = subjectStatus.samplesPublished,
                    firstEpochMillis = subjectStatus.firstPublishEpochMillis,
                    lastEpochMillis = subjectStatus.lastPublishEpochMillis,
                ) ?: 0.0
            }
            SubjectHealth.Stalled -> stalled++
            SubjectHealth.Failed -> failed++
            SubjectHealth.Unavailable -> missing++
            SubjectHealth.Off -> off++
            SubjectHealth.Waiting, SubjectHealth.Idle -> Unit
        }
    }
    // A switched-off subject leaves the denominator rather than counting against it: 5/5 ✓ is the truth
    // about a group whose sixth subject nobody asked for.
    return GroupSummary(entries.size - off, live, stalled, failed, missing, off, rate)
}

/** The whole run in one line, for the status card. */
fun runSummary(
    status: PublisherStatus,
    running: Boolean,
    nowMillis: Long,
    unavailable: Set<PublishedSubject> = emptySet(),
    disabled: Set<PublishedSubject> = emptySet(),
): GroupSummary =
    groupSummary(PublishedSubject.entries, status, running, nowMillis, unavailable, disabled)
