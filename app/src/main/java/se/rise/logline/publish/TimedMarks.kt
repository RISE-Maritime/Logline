package se.rise.logline.publish

/**
 * The quick-mark timers currently running, and when each started.
 *
 * A mark says *when* something happened. Some of what gets marked on a boat is an interval rather than
 * an instant — a manoeuvre, a leg, an engine run — and recording that as a point loses the half of it
 * that matters. Holding a button arms one of these; tapping it again closes it.
 *
 * **Pure, and on the publisher rather than the screen.** Pure so it can be tested without a device, the
 * same reason [AnnotationLog] and [OutboxBuffer] are shaped this way. On the publisher because a timer
 * has to survive leaving the Events tab, because it is run-scoped like the annotation log, and because
 * the teardown is the only place that can close one the operator forgot.
 *
 * **Keyed on the button's label**, which is what `SensorPublisher.mark` already receives — it takes a
 * label, a severity and a category rather than an `AnnotationButton`, and reaching into `config` from
 * here to key on the whole button would invert the dependency for nothing. Two buttons sharing a label
 * therefore share a timer: starting one and stopping the other closes the first. Cheap to live with,
 * and stated rather than guarded.
 *
 * `synchronized` for the same reason the sample rings are: [closeAll] runs on the teardown path while
 * the rest is touched from Main, and a plain map write would not be reliably visible across them.
 */
class TimedMarks {

    private val lock = Any()
    private val startedAt = LinkedHashMap<String, Long>()

    /**
     * Begin timing [label], or do nothing if it is already running.
     *
     * Returns false when it was already going, which the caller reads as "no mark to publish" — a second
     * start would otherwise put a second `started` on the bus and silently move the origin, so the
     * duration would come out short.
     */
    fun start(label: String, atEpochMillis: Long): Boolean = synchronized(lock) {
        if (startedAt.containsKey(label)) return false
        startedAt[label] = atEpochMillis
        true
    }

    /** How long [label] ran, and forget it. Null when it was not running. */
    fun stop(label: String, atEpochMillis: Long): Long? = synchronized(lock) {
        val started = startedAt.remove(label) ?: return null
        // Never negative: a backwards wall-clock step is possible mid-run — the boot-to-epoch offset is
        // re-read per sample and an NTP correction can move it — and a negative duration on a log line
        // is worse than a zero one.
        (atEpochMillis - started).coerceAtLeast(0L)
    }

    /** Label to start time, for the screen to tick against. A copy, safe to read from Compose. */
    fun running(): Map<String, Long> = synchronized(lock) { LinkedHashMap(startedAt) }

    /**
     * Close everything still running, oldest first, and forget it all.
     *
     * Insertion-ordered so the marks a teardown publishes come out in the order the timers were armed,
     * which is the order a reader of the log expects.
     */
    fun closeAll(atEpochMillis: Long): List<Pair<String, Long>> = synchronized(lock) {
        val out = startedAt.map { (label, started) ->
            label to (atEpochMillis - started).coerceAtLeast(0L)
        }
        startedAt.clear()
        out
    }

    /** A new run starts with nothing running, matching the annotation log beside it. */
    fun clear() = synchronized(lock) { startedAt.clear() }
}
