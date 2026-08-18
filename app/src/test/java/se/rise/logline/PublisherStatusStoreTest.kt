package se.rise.logline

import se.rise.logline.publish.ConnectionState
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.PublisherStatusStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TICKS = 25_000

class PublisherStatusStoreTest {

    /**
     * A failure that resolves before it can publish again.
     *
     * `tick` already clears a failure, which covers everything that recovers by producing a sample.
     * Location does not: switched back on mid-run, the time to first fix is tens of seconds, and
     * leaving "Location is switched off" on the row through all of it tells the user their setting did
     * not take. Counters must not move — nothing was published, and inventing a sample here would show
     * up as a rate.
     */
    @Test
    fun `a subject can recover without publishing`() {
        val store = PublisherStatusStore()
        store.started()
        store.tick(PublishedSubject.LOCATION_FIX)
        store.failed(PublishedSubject.LOCATION_FIX, "Location is switched off in Android settings")
        val published = store.status.value[PublishedSubject.LOCATION_FIX].samplesPublished

        store.recovered(PublishedSubject.LOCATION_FIX)

        val after = store.status.value[PublishedSubject.LOCATION_FIX]
        assertNull(after.failure)
        assertEquals("recovering is not a sample", published, after.samplesPublished)
    }

    /** Recovering something that was never broken must not churn the state the UI collects. */
    @Test
    fun `recovering a healthy subject changes nothing`() {
        val store = PublisherStatusStore()
        store.started()
        store.tick(PublishedSubject.LOCATION_FIX)
        val before = store.status.value

        store.recovered(PublishedSubject.LOCATION_FIX)

        assertTrue("an untouched status should be the same value", before === store.status.value)
    }

    /**
     * The regression test for the lost-update race: on a plain
     * `_status.value = _status.value.copy(...)` these counters come out short.
     *
     * Driven off the registry rather than a fixed list, so it scales with the subject set — the
     * contention this guards against gets worse with every subject added, not better.
     */
    @Test
    fun `concurrent ticks across subjects do not lose updates`() = runBlocking {
        val store = PublisherStatusStore()
        store.started()

        withContext(Dispatchers.Default) {
            PublishedSubject.entries
                .map { subject -> async { repeat(TICKS) { store.tick(subject) } } }
                .awaitAll()
        }

        val status = store.status.value
        PublishedSubject.entries.forEach { subject ->
            assertEquals(
                "lost updates on $subject",
                TICKS.toLong(),
                status[subject].samplesPublished,
            )
        }
        assertEquals(PublishedSubject.entries.size * TICKS.toLong(), status.totalSamplesPublished)
    }

    /** Maximum contention: four coroutines incrementing the same counter. */
    @Test
    fun `concurrent ticks on one subject do not lose updates`() = runBlocking {
        val store = PublisherStatusStore()
        store.started()

        withContext(Dispatchers.Default) {
            List(4) { async { repeat(TICKS) { store.tick(PublishedSubject.LINEAR_ACCEL) } } }.awaitAll()
        }

        assertEquals(4L * TICKS, store.status.value[PublishedSubject.LINEAR_ACCEL].samplesPublished)
    }

    @Test
    fun `a tick records the publish time`() {
        val store = PublisherStatusStore()
        val before = System.currentTimeMillis()

        store.tick(PublishedSubject.LOCATION_FIX)

        val subject = store.status.value[PublishedSubject.LOCATION_FIX]
        assertEquals(1L, subject.samplesPublished)
        assertTrue(subject.lastPublishEpochMillis >= before)
    }

    @Test
    fun `started resets counters and clears a previous error`() {
        val store = PublisherStatusStore()
        store.setupFailed("router unreachable")
        store.tick(PublishedSubject.LOCATION_FIX)

        store.started()

        val status = store.status.value
        assertTrue(status.running)
        assertNull(status.error)
        assertEquals(0L, status[PublishedSubject.LOCATION_FIX].samplesPublished)
    }

    @Test
    fun `stopped keeps the counters readable`() {
        val store = PublisherStatusStore()
        store.started()
        repeat(3) { store.tick(PublishedSubject.ANGULAR_VEL) }

        store.stopped()

        val status = store.status.value
        assertEquals(false, status.running)
        assertEquals(3L, status[PublishedSubject.ANGULAR_VEL].samplesPublished)
    }

    @Test
    fun `setupFailed surfaces the message and marks the run stopped`() {
        val store = PublisherStatusStore()
        store.started()

        store.setupFailed("connect failed")

        val status = store.status.value
        assertEquals(false, status.running)
        assertEquals("connect failed", status.error)
    }

    /**
     * The regression test for the failure that bricked every start after the first.
     *
     * `PublisherService` collects [PublisherStatus.error] to know when to tear a run down, and a
     * `StateFlow` hands a new collector the current value the moment it subscribes. So a failed run
     * left its error in place, the next run's watcher read it as its own, and the service stopped
     * itself before the session had opened — for every start after that, until the process was killed.
     *
     * The value being cleared is what makes the next watcher see nothing to act on.
     */
    @Test
    fun `clearError drops a previous run's failure`() {
        val store = PublisherStatusStore()
        store.setupFailed("Unable to connect to any of [tls/router.example.com:443]")

        store.clearError()

        assertNull(store.status.value.error)
    }

    /**
     * Two failures in a row with the *same* message must both be visible.
     *
     * Retrying against an unreachable router produces byte-identical text every time, so anything that
     * told runs apart by comparing the message would silently swallow the second failure. Clearing
     * rather than comparing is what makes this work.
     */
    @Test
    fun `an identical error after a clear is surfaced again`() {
        val store = PublisherStatusStore()
        val message = "Unable to connect to any of [tls/router.example.com:443]"
        store.setupFailed(message)

        store.clearError()
        store.setupFailed(message)

        assertEquals(message, store.status.value.error)
    }

    /**
     * Clearing touches the error and nothing else.
     *
     * This is the stop-then-start path, which is the common one: the main screen shows "3 samples last
     * run" until the new session opens and [PublisherStatusStore.started] replaces the totals. Clearing
     * the error must not blank that out a second early.
     */
    @Test
    fun `clearError leaves the last run's counters readable`() {
        val store = PublisherStatusStore()
        store.started()
        repeat(3) { store.tick(PublishedSubject.LOCATION_FIX) }
        store.stopped()

        store.clearError()

        val status = store.status.value
        assertNull(status.error)
        assertEquals(3, status[PublishedSubject.LOCATION_FIX].samplesPublished)
    }

    @Test
    fun `connection state tracks the router link`() {
        val store = PublisherStatusStore()
        assertEquals(ConnectionState.Idle, store.status.value.connection)

        store.connectionChanged(true)
        assertEquals(ConnectionState.Connected, store.status.value.connection)

        store.connectionChanged(false)
        assertEquals(ConnectionState.Disconnected, store.status.value.connection)
    }

    /** A lost link must not look like a failed run, or the service would shut down on a blip. */
    @Test
    fun `losing the connection does not set the fatal error`() {
        val store = PublisherStatusStore()
        store.started()

        store.connectionChanged(false)

        val status = store.status.value
        assertNull(status.error)
        assertTrue(status.running)
    }

    @Test
    fun `a subject failure is recorded without touching the others`() {
        val store = PublisherStatusStore()
        store.started()

        store.failed(PublishedSubject.ANGULAR_VEL, "publisher undeclared")

        val status = store.status.value
        assertEquals("publisher undeclared", status[PublishedSubject.ANGULAR_VEL].failure)
        PublishedSubject.entries
            .filter { it != PublishedSubject.ANGULAR_VEL }
            .forEach { assertNull("$it should be unaffected", status[it].failure) }
    }

    @Test
    fun `a successful tick clears a previous failure`() {
        val store = PublisherStatusStore()
        store.started()
        store.failed(PublishedSubject.LOCATION_FIX, "publish failed")

        store.tick(PublishedSubject.LOCATION_FIX)

        assertNull(store.status.value[PublishedSubject.LOCATION_FIX].failure)
    }
}
