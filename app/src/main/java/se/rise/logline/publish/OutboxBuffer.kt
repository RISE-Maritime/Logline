package se.rise.logline.publish

import se.rise.logline.keelson.PublishedSubject

/** One sample held for possible replay. Enough to rebuild a byte-identical envelope. */
data class OutboxEntry(
    val subject: PublishedSubject,
    val payload: ByteArray,
    /** The instant the envelope was stamped with, so a replay preserves `enclosed_at`. */
    val enclosedAtNanos: Long,
) {
    // Data classes compare arrays by reference; content equality is what the tests want to assert.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEntry) return false
        return subject == other.subject &&
            enclosedAtNanos == other.enclosedAtNanos &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int =
        (subject.hashCode() * 31 + enclosedAtNanos.hashCode()) * 31 + payload.contentHashCode()
}

/**
 * Recent samples, held so a dropped link can be filled in once it comes back.
 *
 * **Filled unconditionally, never gated on the connection state.** `isConnectedToRouter()` reads Zenoh's
 * transport table and is polled every 2 s, and Zenoh itself only tears a transport down after its
 * keepalive gives up — so `Disconnected` appears seconds after the samples actually started going
 * nowhere. A buffer that started on that signal would miss the front of every outage. Buffering
 * everything costs one lock and one array write per sample and has no such hole.
 *
 * A single ring rather than one per subject, because replay has to be **globally oldest-first**: the
 * router keeps a latest-value store per key, so flushing out of order would leave stale values behind.
 * At 217 samples/s the lock is uncontended enough not to matter.
 *
 * Capacity is in **entries, not seconds** — `SensorRate.Max` is a legal setting and the gyroscope was
 * measured at 442 Hz, which would make any seconds-based bound meaningless.
 */
class OutboxBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = arrayOfNulls<OutboxEntry>(capacity)
    private var count = 0
    private var next = 0
    private var evictedCount = 0L
    private var addedCount = 0L

    /**
     * Entries dropped because the buffer filled.
     *
     * **Ordinary turnover, not a loss** — the ring is full about two and a half minutes into any run
     * and every add evicts something from then on, so a healthy hour evicts most of a million samples
     * and means nothing by it. What a *gap* costs is [lostSince], which is the number worth showing
     * anyone.
     */
    val evicted: Long get() = synchronized(this) { evictedCount }

    /** Entries ever offered. Monotonic, so two readings bracket a period. */
    val added: Long get() = synchronized(this) { addedCount }

    val size: Int get() = synchronized(this) { count }

    /**
     * Add a sample. Called from the publish path, so it must never throw — `SubjectSink.guard` would
     * turn an exception into a permanent failure for that subject.
     */
    fun add(entry: OutboxEntry) = synchronized(this) {
        if (count == capacity) evictedCount++
        addedCount++
        entries[next] = entry
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /**
     * Everything stamped at or after [sinceNanos], oldest first.
     *
     * The caller passes the last instant it is confident reached a router, which is at most one poll
     * interval stale — so this deliberately returns a couple of seconds of already-delivered samples
     * too. Duplicates are the right trade against losing the head of an outage.
     */
    fun since(sinceNanos: Long): List<OutboxEntry> = synchronized(this) {
        if (count == 0) return emptyList()
        val start = ((next - count) % capacity + capacity) % capacity
        val out = ArrayList<OutboxEntry>(count)
        for (i in 0 until count) {
            val entry = entries[(start + i) % capacity] ?: continue
            if (entry.enclosedAtNanos >= sinceNanos) out += entry
        }
        out
    }

    /** Everything currently held, oldest first. */
    fun snapshot(): List<OutboxEntry> = since(Long.MIN_VALUE)

    /**
     * How many of the entries added since [addedMark] the ring has already dropped — the samples a
     * replay will *not* be able to fill in.
     *
     * Arithmetic rather than bookkeeping, and it is exact: take the mark at the last poll that saw a
     * router, and everything added after it belongs to the replay window. The ring holds [capacity] of
     * them and drops the rest, oldest first. Nothing older than the mark is in the window, so evicting
     * that costs nothing — which is why [evicted] is the wrong number here and this one is zero until
     * an outage has run longer than the whole buffer.
     */
    fun lostSince(addedMark: Long): Long = synchronized(this) {
        (addedCount - addedMark - capacity).coerceAtLeast(0L)
    }

    fun clear() = synchronized(this) {
        count = 0
        next = 0
        evictedCount = 0
        addedCount = 0
    }

    companion object {
        /** ~2.5 minutes at the default 217 samples/s; ~3 MB of heap. */
        const val DEFAULT_CAPACITY = 32_768
    }
}
