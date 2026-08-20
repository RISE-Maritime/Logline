package se.rise.logline.record

import java.util.concurrent.atomic.AtomicLong

/**
 * How far behind the recorder's writer is, and how far behind it has ever been this run.
 *
 * The queue between the publish path and the MCAP writer is a `Channel` of [capacity] samples — about
 * forty-five seconds of slack at the measured rate — and until this existed **nothing could see into
 * it**. Kotlin's `Channel` exposes no size, and the recorder counted only what it had already failed
 * to accept. So the depth went from zero to full with nothing reported, and the first observable event
 * was a sample that had already been lost. There was no "under strain" to show anybody.
 *
 * The counterpart on the publish side is [se.rise.logline.publish.OutboxBuffer], which has reported its
 * own `size`, `added` and `evicted` from the start; this is the recorder finally getting the same
 * treatment.
 *
 * **[peak] is the number that characterises a run, not [depth].** The UI polls at 1 Hz against a
 * 10 000-deep buffer, so an instantaneous depth reads about zero almost every time and misses exactly
 * the burst worth knowing about — a 512 MB file rotation stalls the drain for as long as the copy to
 * Downloads takes, and a poll is unlikely to land inside it. The high-water mark cannot miss it.
 *
 * Two `AtomicLong`s rather than one counter that goes up and down, because monotonic totals let two
 * readings bracket a period; and atomics rather than a `MutableStateFlow` because [drained] is called
 * once per written sample — the drain loop already allocates a status object at that rate and this must
 * not add a second. Nothing here allocates or locks.
 */
class QueueLoad(val capacity: Int) {

    private val enqueuedCount = AtomicLong(0)
    private val drainedCount = AtomicLong(0)
    private val peakDepth = AtomicLong(0)

    /** Count a sample the queue accepted. Not called for one it rejected — that is `dropped`. */
    fun enqueued() {
        val depth = enqueuedCount.incrementAndGet() - drainedCount.get()
        // Compare-and-set rather than a plain max: the drain coroutine and every collector reach this
        // concurrently, and a read-modify-write would lose the highest reading under exactly the load
        // this exists to measure.
        while (true) {
            val seen = peakDepth.get()
            if (depth <= seen || peakDepth.compareAndSet(seen, depth)) break
        }
    }

    /** Count a sample the writer has finished with. */
    fun drained() {
        drainedCount.incrementAndGet()
    }

    /**
     * Samples accepted but not yet written.
     *
     * Floored at zero: the two counters are read separately, so a drain landing between them can make
     * the subtraction momentarily negative. A negative backlog is not a thing worth showing anyone.
     */
    val depth: Long get() = (enqueuedCount.get() - drainedCount.get()).coerceAtLeast(0)

    /** The deepest [depth] has been this run. */
    val peak: Long get() = peakDepth.get()

    val enqueued: Long get() = enqueuedCount.get()

    /**
     * Start a fresh run.
     *
     * Called where the per-run `Channel` is created. A peak carried over from a previous run would
     * report the last run's worst moment against this one — the same class of bug as the single
     * long-lived channel that once made the second run in a process record nothing.
     */
    fun reset() {
        enqueuedCount.set(0)
        drainedCount.set(0)
        peakDepth.set(0)
    }
}
