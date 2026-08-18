package se.rise.logline

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.FramePreview
import se.rise.logline.publish.LiveSampleStore
import se.rise.logline.publish.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live store is written from eight concurrent collectors at ~217 samples/s and read from the
 * Compose main thread. That is the same shape as the lost-update bug this codebase already hit once, so
 * the concurrency behaviour is pinned here rather than assumed.
 */
class LiveSampleStoreTest {

    @Test
    fun `samples come back oldest first`() {
        val store = LiveSampleStore(samplesPerSubject = 8)

        repeat(4) { i -> store.record(PublishedSubject.AIR_PRESSURE, 100L + i, i.toFloat()) }

        val window = store.snapshot()[PublishedSubject.AIR_PRESSURE]
        assertEquals(4, window.size)
        assertEquals(listOf(0f, 1f, 2f, 3f), window.values.toList())
        assertEquals(listOf(100L, 101L, 102L, 103L), window.timesMillis.toList())
        assertEquals(3f, window.latest!!, 1e-6f)
    }

    /** The whole point of a ring: memory must not grow with run length. */
    @Test
    fun `capacity is bounded and the oldest samples fall off`() {
        val store = LiveSampleStore(samplesPerSubject = 4)

        repeat(10) { i -> store.record(PublishedSubject.AIR_PRESSURE, i.toLong(), i.toFloat()) }

        val window = store.snapshot()[PublishedSubject.AIR_PRESSURE]
        assertEquals(4, window.size)
        // Only the last four survive, still oldest-first.
        assertEquals(listOf(6f, 7f, 8f, 9f), window.values.toList())
        assertEquals(listOf(6L, 7L, 8L, 9L), window.timesMillis.toList())
    }

    @Test
    fun `an untouched subject reads as empty rather than null`() {
        val store = LiveSampleStore(samplesPerSubject = 4)
        val window = store.snapshot()[PublishedSubject.ORIENTATION]
        assertTrue(window.isEmpty)
        assertNull(window.latest)
        assertNull(window.min)
        assertNull(window.max)
    }

    @Test
    fun `min and max span the window`() {
        val store = LiveSampleStore(samplesPerSubject = 8)
        listOf(3f, -1f, 7f, 2f).forEach { store.record(PublishedSubject.CELLULAR_RSRP, 0L, it) }

        val window = store.snapshot()[PublishedSubject.CELLULAR_RSRP]
        assertEquals(-1f, window.min!!, 1e-6f)
        assertEquals(7f, window.max!!, 1e-6f)
    }

    /** A snapshot handed to Compose must not change underneath it while collectors keep writing. */
    @Test
    fun `a snapshot is isolated from later appends`() {
        val store = LiveSampleStore(samplesPerSubject = 8)
        store.record(PublishedSubject.AIR_PRESSURE, 1L, 1f)

        val before = store.snapshot()[PublishedSubject.AIR_PRESSURE]
        store.record(PublishedSubject.AIR_PRESSURE, 2L, 2f)

        assertEquals(1, before.size)
        assertEquals(2, store.snapshot()[PublishedSubject.AIR_PRESSURE].size)
    }

    /**
     * The regression test for the concurrent-append hazard: an `ArrayList` here would throw or silently
     * drop. Every subject has one writer in production, but they run simultaneously, so the store is
     * exercised from many coroutines at once.
     */
    @Test
    fun `concurrent appends across subjects neither lose nor corrupt samples`() = runBlocking {
        val perSubject = 2_000
        val store = LiveSampleStore(samplesPerSubject = perSubject)

        withContext(Dispatchers.Default) {
            PublishedSubject.entries.map { subject ->
                async {
                    repeat(perSubject) { i -> store.record(subject, i.toLong(), i.toFloat()) }
                }
            }.awaitAll()
        }

        val snapshot = store.snapshot()
        PublishedSubject.entries.forEach { subject ->
            val window = snapshot[subject]
            assertEquals("lost samples on $subject", perSubject, window.size)
            // Ordering must survive too — a torn write would show up as a value out of sequence.
            window.values.forEachIndexed { i, v ->
                assertEquals("out of order at $i on $subject", i.toFloat(), v, 1e-6f)
            }
        }
    }

    /**
     * Many writers hammering **one** ring — the case the per-subject test above cannot reach, since
     * there each subject has a single writer and therefore no contention.
     *
     * The ring is sized larger than the total so nothing should be evicted, and every write carries a
     * distinct value. Without the lock, `next = (next + 1) % capacity` is a read-modify-write that two
     * threads can interleave: writes land on the same slot and the count drifts, so both the size and
     * the set of surviving values come out wrong. Identical values or a too-small ring would hide that,
     * which is exactly how the first version of this test passed against an unsynchronised append.
     */
    @Test
    fun `concurrent appends to one ring lose nothing`() = runBlocking {
        val writers = 4
        val perWriter = 2_000
        val total = writers * perWriter
        val store = LiveSampleStore(samplesPerSubject = total * 2)

        withContext(Dispatchers.Default) {
            List(writers) { w ->
                async {
                    repeat(perWriter) { i ->
                        store.record(PublishedSubject.ANGULAR_VEL, 0L, (w * perWriter + i).toFloat())
                    }
                }
            }.awaitAll()
        }

        val window = store.snapshot()[PublishedSubject.ANGULAR_VEL]
        assertEquals("appends were lost or double-written", total, window.size)
        assertEquals(
            "every distinct value should survive exactly once",
            (0 until total).map { it.toFloat() }.toSet(),
            window.values.toSet(),
        )
    }

    @Test
    fun `the track keeps fixes oldest first and bounded`() {
        val store = LiveSampleStore(trackPoints = 3)
        repeat(5) { i ->
            store.recordFix(TrackPoint(57.4 + i, 12.0, 5f, null, i.toLong()))
        }

        val track = store.snapshot().track
        assertEquals(3, track.size)
        assertEquals(59.4, track.first().latitude, 1e-9)
        assertEquals(61.4, track.last().latitude, 1e-9)
        assertEquals(track.last(), store.snapshot().lastFix)
    }

    /**
     * An absent bearing is kept absent. The publisher sends `0.0` on the wire for a missing bearing, and
     * a map reading that would draw a heading arrow due north on a stationary phone.
     */
    @Test
    fun `an absent bearing stays absent rather than becoming north`() {
        val store = LiveSampleStore(trackPoints = 4)
        store.recordFix(TrackPoint(57.4, 12.0, 5f, bearingDegrees = null, timeMillis = 1L))
        store.recordFix(TrackPoint(57.4, 12.0, 5f, bearingDegrees = 0f, timeMillis = 2L))

        val track = store.snapshot().track
        assertNull("no bearing reported", track[0].bearingDegrees)
        // ...and a genuinely measured due-north bearing is still distinguishable from that.
        assertEquals(0f, track[1].bearingDegrees!!, 1e-6f)
    }

    /**
     * The camera slot keeps one frame, not a history: a thumbnail answers "what is it seeing now", and
     * a ring of 150 kB frames would dwarf every other live buffer put together.
     */
    @Test
    fun `the newest camera frame replaces the previous one`() {
        val store = LiveSampleStore()
        store.recordFrame(FramePreview(byteArrayOf(1, 2, 3), 320, 180, 10L))
        store.recordFrame(FramePreview(byteArrayOf(4, 5, 6), 320, 180, 20L))

        val frame = store.snapshot().frame
        assertNotNull(frame)
        assertEquals(20L, frame!!.timeMillis)
        assertArrayEquals(byteArrayOf(4, 5, 6), frame.jpeg)
    }

    @Test
    fun `no camera frame reads as null rather than an empty one`() {
        assertNull(LiveSampleStore().snapshot().frame)
    }

    @Test
    fun `a new run starts empty`() {
        val store = LiveSampleStore(samplesPerSubject = 8, trackPoints = 8)
        store.record(PublishedSubject.AIR_PRESSURE, 1L, 1f)
        store.recordFix(TrackPoint(57.4, 12.0, null, null, 1L))
        store.recordFrame(FramePreview(byteArrayOf(1), 2, 2, 1L))

        store.clear()

        val snapshot = store.snapshot()
        assertTrue(snapshot[PublishedSubject.AIR_PRESSURE].isEmpty)
        assertTrue(snapshot.track.isEmpty())
        assertNull(snapshot.lastFix)
        assertNull(snapshot.frame)
    }
}
