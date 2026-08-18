package se.rise.logline

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.OutboxBuffer
import se.rise.logline.publish.OutboxEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outbox is written by eight concurrent collectors at ~217 samples/s and drained by the connection
 * watchdog. Its ordering guarantee is load-bearing: the router keeps a latest-value store per key, so a
 * replay that ran newest-first would leave stale values behind.
 */
class OutboxBufferTest {

    private fun entry(n: Int, subject: PublishedSubject = PublishedSubject.AIR_PRESSURE) =
        OutboxEntry(subject, byteArrayOf(n.toByte()), n.toLong())

    @Test
    fun `entries come back oldest first`() {
        val outbox = OutboxBuffer(capacity = 8)
        repeat(4) { outbox.add(entry(it)) }

        assertEquals(listOf(0L, 1L, 2L, 3L), outbox.snapshot().map { it.enclosedAtNanos })
    }

    @Test
    fun `capacity is bounded and the oldest fall off`() {
        val outbox = OutboxBuffer(capacity = 4)
        repeat(10) { outbox.add(entry(it)) }

        assertEquals(4, outbox.size)
        assertEquals(listOf(6L, 7L, 8L, 9L), outbox.snapshot().map { it.enclosedAtNanos })
    }

    /** Evictions are counted, not hidden — the same rule the recorder's dropped counter follows. */
    @Test
    fun `evictions are counted`() {
        val outbox = OutboxBuffer(capacity = 4)
        repeat(4) { outbox.add(entry(it)) }
        assertEquals(0L, outbox.evicted)

        repeat(3) { outbox.add(entry(100 + it)) }

        assertEquals(3L, outbox.evicted)
    }

    /**
     * Replay starts from the last poll that saw a router, which is up to a poll interval stale — so the
     * selection is inclusive and deliberately returns a little already-delivered data.
     */
    @Test
    fun `since selects from a timestamp inclusively`() {
        val outbox = OutboxBuffer(capacity = 16)
        listOf(10L, 20L, 30L, 40L).forEach { outbox.add(OutboxEntry(PublishedSubject.AIR_PRESSURE, ByteArray(1), it)) }

        assertEquals(listOf(20L, 30L, 40L), outbox.since(20L).map { it.enclosedAtNanos })
        assertEquals(listOf(30L, 40L), outbox.since(25L).map { it.enclosedAtNanos })
        assertTrue(outbox.since(100L).isEmpty())
    }

    @Test
    fun `an empty outbox replays nothing`() {
        assertTrue(OutboxBuffer(capacity = 4).since(0L).isEmpty())
        assertEquals(0, OutboxBuffer(capacity = 4).size)
    }

    /**
     * Distinct values per writer, deliberately. `LiveSampleStoreTest`'s first version passed against an
     * unsynchronised buffer because every writer wrote the same value — a lost update is invisible when
     * the writes are identical.
     */
    @Test
    fun `concurrent writers lose nothing`() = runBlocking {
        val writers = 8
        val perWriter = 2_000
        val total = writers * perWriter
        val outbox = OutboxBuffer(capacity = total * 2)

        withContext(Dispatchers.Default) {
            List(writers) { w ->
                async {
                    repeat(perWriter) { i ->
                        outbox.add(
                            OutboxEntry(
                                PublishedSubject.AIR_PRESSURE,
                                ByteArray(1),
                                (w * perWriter + i).toLong(),
                            )
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals("writes were lost or overwrote each other", total, outbox.size)
        assertEquals(
            "every distinct entry should survive exactly once",
            (0 until total).map { it.toLong() }.toSet(),
            outbox.snapshot().map { it.enclosedAtNanos }.toSet(),
        )
        assertEquals(0L, outbox.evicted)
    }

    @Test
    fun `clear resets the buffer and the eviction count`() {
        val outbox = OutboxBuffer(capacity = 2)
        repeat(5) { outbox.add(entry(it)) }
        assertTrue(outbox.evicted > 0)

        outbox.clear()

        assertEquals(0, outbox.size)
        assertEquals(0L, outbox.evicted)
        assertTrue(outbox.snapshot().isEmpty())
    }

    /** Entries must carry enough to rebuild a byte-identical envelope. */
    @Test
    fun `an entry keeps its payload and original enclose time`() {
        val payload = byteArrayOf(9, 8, 7)
        val outbox = OutboxBuffer(capacity = 4)
        outbox.add(OutboxEntry(PublishedSubject.LOCATION_FIX, payload, 1_700_000_000_123_456_789L))

        val back = outbox.snapshot().single()
        assertEquals(PublishedSubject.LOCATION_FIX, back.subject)
        assertEquals(1_700_000_000_123_456_789L, back.enclosedAtNanos)
        assertTrue(payload.contentEquals(back.payload))
    }
}
