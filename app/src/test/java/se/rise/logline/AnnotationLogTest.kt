package se.rise.logline

import se.rise.logline.config.AnnotationSeverity
import se.rise.logline.publish.Annotation
import se.rise.logline.publish.AnnotationLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The list the annotation screen reads back.
 *
 * It is a confirmation that a press registered, not a second copy of the recording — so it is bounded,
 * and the count has to keep climbing past the bound or "Marked this run: 100" would be a lie the
 * moment somebody made a hundred and one marks.
 */
class AnnotationLogTest {

    private fun mark(n: Int) = Annotation(
        atEpochMillis = 1_700_000_000_000L + n,
        message = "mark $n",
        severity = AnnotationSeverity.Info,
        category = "note",
    )

    @Test
    fun `marks come back oldest first`() {
        val log = AnnotationLog()
        (1..3).forEach { log.add(mark(it)) }

        assertEquals(listOf("mark 1", "mark 2", "mark 3"), log.recent().map { it.message })
    }

    @Test
    fun `the oldest marks are evicted once the list is full`() {
        val log = AnnotationLog(capacity = 3)
        (1..5).forEach { log.add(mark(it)) }

        assertEquals(listOf("mark 3", "mark 4", "mark 5"), log.recent().map { it.message })
    }

    /** The count is of the run, not of the list — eviction must not make marks disappear from it. */
    @Test
    fun `the count keeps climbing past the capacity`() {
        val log = AnnotationLog(capacity = 3)
        (1..5).forEach { log.add(mark(it)) }

        assertEquals(5, log.count())
        assertEquals(3, log.recent().size)
    }

    @Test
    fun `clearing resets both the list and the count`() {
        val log = AnnotationLog()
        (1..3).forEach { log.add(mark(it)) }

        log.clear()

        assertTrue(log.recent().isEmpty())
        assertEquals(0, log.count())
    }

    /**
     * `add` runs on `Dispatchers.Default` and `recent` is read from Main, so this is about visibility
     * as much as about races. An unsynchronised `ArrayDeque` loses writes here rather than merely
     * ordering them oddly.
     */
    @Test
    fun `concurrent marks are all counted`() {
        val log = AnnotationLog(capacity = 1_000)
        val threads = 8
        val each = 100
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            thread {
                start.await()
                repeat(each) { i -> log.add(mark(t * each + i)) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("workers did not finish", done.await(10, TimeUnit.SECONDS))

        assertEquals(threads * each, log.count())
        assertEquals(threads * each, log.recent().size)
        assertEquals(threads * each, log.recent().map { it.message }.toSet().size)
    }

    /** A snapshot must not change under the reader while more marks arrive. */
    @Test
    fun `a snapshot is not a live view`() {
        val log = AnnotationLog()
        log.add(mark(1))
        val snapshot = log.recent()

        log.add(mark(2))

        assertEquals(1, snapshot.size)
        assertEquals(2, log.recent().size)
    }
}
