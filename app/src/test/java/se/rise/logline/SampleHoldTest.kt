package se.rise.logline

import se.rise.logline.sensors.heldAt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold is what turns an on-change sensor into a steady subject.
 *
 * Real delays rather than a virtual-time test dispatcher, because `kotlinx-coroutines-test` is not a
 * dependency here — so the intervals are kept short and the assertions are about ordering and content
 * rather than exact timing, which would be flaky on a loaded CI runner.
 */
class SampleHoldTest {

    /** The property the whole design rests on: a value the sensor reported once is published again. */
    @Test
    fun `the last value is republished on the tick`() = runBlocking {
        val upstream = flow {
            emit("first")
            delay(10_000) // Never emits again: the sensor has nothing new to report.
        }

        val held = upstream.heldAt(intervalMillis = 100).take(3).toList()

        assertEquals(listOf("first", "first", "first"), held)
    }

    /**
     * Nothing before the first reading. A sensor the device does not have must publish silence, not a
     * zero — the same rule the battery and radio scalars follow.
     */
    @Test
    fun `nothing is emitted before the first upstream value`() = runBlocking {
        val slow = flow<String> { delay(10_000) }

        val first = withTimeoutOrNull(400) { slow.heldAt(intervalMillis = 100).take(1).toList() }

        assertNull("emitted before the sensor ever reported", first)
    }

    @Test
    fun `a sensor that never reports at all stays silent`() = runBlocking {
        val nothing = withTimeoutOrNull(400) {
            emptyFlow<String>().heldAt(intervalMillis = 100).take(1).toList()
        }

        assertNull(nothing)
    }

    /** A new reading takes over from the held one, and is then what gets repeated. */
    @Test
    fun `a fresh value replaces the held one`() = runBlocking {
        val upstream = MutableSharedFlow<String>(replay = 1)
        upstream.emit("dark")

        val collected = mutableListOf<String>()
        val job = launch {
            upstream.heldAt(intervalMillis = 60).collect { collected += it }
        }

        // Long enough for a few repeats of the first value…
        delay(250)
        upstream.emit("bright")
        // …and a few of the second.
        delay(250)
        job.cancel()

        assertTrue("expected repeats of the held value, got $collected", collected.size >= 4)
        assertEquals("dark", collected.first())
        assertEquals("bright", collected.last())
        // Once it switches it stays switched: no interleaving of the stale reading.
        val firstBright = collected.indexOf("bright")
        assertTrue(
            "a stale reading came back after a fresh one: $collected",
            collected.drop(firstBright).all { it == "bright" },
        )
    }

    /**
     * `SensorRate.Max` resolves to an interval of zero, which without a floor is a loop with no delay
     * republishing a value that changes over minutes.
     */
    @Test
    fun `a zero interval is floored rather than spinning`() = runBlocking {
        val upstream = flow {
            emit(1)
            delay(10_000)
        }

        val started = System.nanoTime()
        val held = upstream.heldAt(intervalMillis = 0).take(3).toList()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf(1, 1, 1), held)
        assertTrue("three ticks took only $elapsedMillis ms — the floor is not applied", elapsedMillis >= 250)
    }
}
