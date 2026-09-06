package se.rise.logline.publish

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import se.rise.logline.keelson.PublishedSubject

/**
 * Whether the session still has a router to publish to.
 *
 * This is deliberately separate from [PublisherStatus.error]: a lost link is a recoverable state, not
 * a failed run, and the foreground service must not shut down because of one.
 */
enum class ConnectionState { Idle, Connected, Disconnected }

data class SubjectStatus(
    val samplesPublished: Long = 0,
    val lastPublishEpochMillis: Long = 0,
    /** When this subject's first sample of the run went out, for deriving the achieved rate. */
    val firstPublishEpochMillis: Long = 0,
    val failure: String? = null,
    /**
     * Samples this subject's sensor produced that never reached the publish path at all.
     *
     * The `callbackFlow` each sensor runs on holds 64 samples, and a collector that cannot keep up
     * makes `trySend` start failing. Every provider used to discard that result, so the earliest and
     * most direct evidence that *the phone* — rather than the link or the disk — cannot keep up was
     * thrown away at the point it was generated.
     *
     * Distinct from a failed publish, which is [failure], and from a sample the recorder's queue
     * refused, which is `RecordingStatus.dropped`: this one never got as far as either. Normally zero
     * for a whole run, which is what makes it worth showing when it is not.
     */
    val shed: Long = 0,
)

data class PublisherStatus(
    val running: Boolean = false,
    /** Fatal setup failure only — opening the session or declaring publishers. */
    val error: String? = null,
    /**
     * Connected, and nothing more — deliberately not *which* endpoint.
     *
     * Zenoh's session info exposes the routers' zids, not the locator a transport was opened on, so with
     * several endpoints configured the app cannot tell which one answered. A field promising that would
     * have to be permanently null.
     */
    val connection: ConnectionState = ConnectionState.Idle,
    /**
     * Keyed by subject rather than one field each: a named field per subject meant every new subject
     * touched this class, the `when` that updated it, the notification's total, and the UI's row list.
     */
    val subjects: Map<PublishedSubject, SubjectStatus> = emptyMap(),
    /** Samples currently being replayed after a reconnect, and how many have gone out. */
    val replayPending: Int = 0,
    val replayed: Long = 0,
    /**
     * Samples this run's outages outran the outbox by, and which therefore can never be replayed.
     *
     * Zero for any gap shorter than the buffer, which is most of them; it starts climbing only once an
     * outage has lasted longer than the whole ring — about two and a half minutes at the measured rate.
     * Surfaced because the alternative is a run that says "Replayed 32 768 samples" after a twenty-
     * minute hole and looks like it caught up. A run total, not per gap.
     */
    val replayLost: Long = 0,
    /**
     * How much longer the battery can carry this run, measured from its own drain.
     *
     * Derived rather than published: keelson has no subject for "time left", and the number is about
     * this phone's ability to keep going rather than about anything it is observing.
     */
    val batteryRuntime: RuntimeEstimate = RuntimeEstimate.Unknown,
    /**
     * The battery has fallen to the point where the run's recording was secured — see
     * `SensorPublisher.watchBattery`.
     *
     * A **state, not an event**: it stays true for the rest of the run unless the phone goes on
     * charge, so a screen opened afterwards still says what happened. Its one job on screen is to
     * turn "battery running out" from a prediction into a statement that something was done about it.
     */
    val batteryCritical: Boolean = false,
) {
    /** Never null — a subject with no samples yet reads as a zeroed status, which is what the UI wants. */
    operator fun get(subject: PublishedSubject): SubjectStatus = subjects[subject] ?: SubjectStatus()

    val totalSamplesPublished: Long get() = subjects.values.sumOf { it.samplesPublished }

    /**
     * How long this run's samples span — first sample to last, across every subject.
     *
     * Derived rather than stored: the per-subject first and last publish times are already kept, and a
     * separate pair of run timestamps would be a second answer to the same question, free to disagree
     * with the first. It is also the more honest number for a summary — it measures the data, not how
     * long a session object existed, so a run whose collectors died an hour ago does not claim the
     * hour. Zero until something has been published.
     */
    val publishedSpanMillis: Long
        get() {
            val first = subjects.values
                .filter { it.firstPublishEpochMillis > 0 }
                .minOfOrNull { it.firstPublishEpochMillis } ?: return 0
            val last = subjects.values.maxOfOrNull { it.lastPublishEpochMillis } ?: return 0
            return (last - first).coerceAtLeast(0)
        }
}

/**
 * Owns the published status of a run.
 *
 * Every subject collector runs concurrently on `Dispatchers.Default` and they all write into the same
 * value, so every incremental update goes through [MutableStateFlow.update] — a compare-and-set loop.
 * A plain `_status.value = _status.value.copy(...)` loses increments whenever two collectors read the
 * same snapshot before either writes; under a contended unit test that pattern lost 60–70% of them.
 * That is load-bearing, and it matters more now than it did with four subjects.
 */
class PublisherStatusStore {

    private val _status = MutableStateFlow(PublisherStatus())
    val status: StateFlow<PublisherStatus> = _status.asStateFlow()

    /** A run has begun: replaces the whole state, resetting counters and clearing any error. */
    fun started() {
        _status.value = PublisherStatus(running = true)
    }

    /** Setup failed — the session never opened. This is the one that ends a run. */
    fun setupFailed(message: String) {
        _status.value = PublisherStatus(running = false, error = message)
    }

    /** The run ended; the counters stay put so the last totals remain readable. */
    fun stopped() = _status.update { it.copy(running = false) }

    /**
     * A new run is beginning: the previous one's setup failure is no longer current.
     *
     * Separate from [started], which cannot do this job because it only runs once the Zenoh session is
     * open — seconds later. `PublisherService` watches [PublisherStatus.error] to know when to tear a
     * run down, and a `StateFlow` hands a fresh collector the *current* value the moment it subscribes.
     * So a run that had failed left its error sitting there, and the next run's watcher read it as its
     * own failure and stopped the service before the session ever opened. Every start after a single
     * failure died that way, which looked like the app refusing to run until it was force-stopped.
     *
     * A copy rather than a replacement, so nothing else is disturbed. After a normal stop the counters
     * still describe the finished run — which is what the main screen shows as "N samples last run"
     * while the next session opens — and only [started] replaces them. ([setupFailed] has already
     * replaced the whole value, so on that path there is nothing left to preserve.)
     */
    fun clearError() = _status.update { it.copy(error = null) }

    fun connectionChanged(connected: Boolean) = _status.update {
        it.copy(connection = if (connected) ConnectionState.Connected else ConnectionState.Disconnected)
    }

    /** The battery collector's latest verdict. Updated at the battery rate, which is 0.2 Hz by default. */
    fun batteryRuntime(estimate: RuntimeEstimate) = _status.update { it.copy(batteryRuntime = estimate) }

    fun batteryCritical(critical: Boolean) = _status.update { it.copy(batteryCritical = critical) }

    fun replayStarted(count: Int) = _status.update { it.copy(replayPending = count) }

    fun replayFinished(sent: Int) = _status.update {
        it.copy(replayPending = 0, replayed = it.replayed + sent)
    }

    /**
     * The run total of samples no replay can fill in.
     *
     * Set rather than incremented: the watchdog recomputes it from the outbox on every poll, including
     * while the gap is still open, so the number is right on screen during the outage instead of only
     * after it.
     */
    fun replayLostChanged(total: Long) = _status.update {
        if (it.replayLost == total) it else it.copy(replayLost = total)
    }

    /** A sample went out. Clears any recorded failure for that subject — this is how recovery shows. */
    fun tick(subject: PublishedSubject) = _status.update { status ->
        status.replace(subject) { prev ->
            val now = System.currentTimeMillis()
            prev.copy(
                samplesPublished = prev.samplesPublished + 1,
                lastPublishEpochMillis = now,
                firstPublishEpochMillis = if (prev.samplesPublished == 0L) now else prev.firstPublishEpochMillis,
                failure = null,
            )
        }
    }

    /**
     * A sample this subject's sensor produced and its collector could not take.
     *
     * Updated per event rather than pulled like the recorder's depth, and the difference is the rate:
     * shedding is rare and only happens under strain, where a drain happens hundreds of times a second.
     * A `MutableStateFlow.update` is the right cost for something that should never fire.
     */
    fun shed(subject: PublishedSubject) = _status.update { status ->
        status.replace(subject) { it.copy(shed = it.shed + 1) }
    }

    /** This subject stopped working — a failed publish, or a collector that died. */
    fun failed(subject: PublishedSubject, message: String) = _status.update { status ->
        status.replace(subject) { it.copy(failure = message) }
    }

    /**
     * A subject's recorded failure no longer applies, and no sample has arrived to say so.
     *
     * [tick] already clears a failure, which covers everything that recovers by publishing again. This
     * is for the case that recovers *before* it can publish: location switched back on mid-run has a
     * time to first fix of tens of seconds, and leaving "Location is switched off" on the row for all
     * of it would be telling the user their setting did not take. Counters are untouched — nothing has
     * been published, and pretending otherwise would show up as a rate.
     */
    fun recovered(subject: PublishedSubject) = _status.update { status ->
        if (status[subject].failure == null) status
        else status.replace(subject) { it.copy(failure = null) }
    }

    private inline fun PublisherStatus.replace(
        subject: PublishedSubject,
        change: (SubjectStatus) -> SubjectStatus,
    ): PublisherStatus = copy(subjects = subjects + (subject to change(this[subject])))
}
