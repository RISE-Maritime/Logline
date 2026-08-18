package se.rise.logline

import se.rise.logline.ui.formatAge
import org.junit.Assert.assertEquals
import org.junit.Test

private const val NOW = 1_800_000_000_000L
private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE

class AgeTest {

    @Test
    fun `never published shows a dash`() {
        assertEquals("—", formatAge(lastPublishEpochMillis = 0L, nowMillis = NOW))
    }

    @Test
    fun `a fresh sample is just now`() {
        assertEquals("just now", formatAge(NOW, NOW))
        assertEquals("just now", formatAge(NOW - 1_999, NOW))
    }

    /** The whole point of the change: an age that keeps growing once a subject stops. */
    @Test
    fun `seconds are reported once past the just-now threshold`() {
        assertEquals("2 s ago", formatAge(NOW - 2 * SECOND, NOW))
        assertEquals("32 s ago", formatAge(NOW - 32 * SECOND, NOW))
        assertEquals("59 s ago", formatAge(NOW - 59 * SECOND, NOW))
    }

    @Test
    fun `minutes take over at a minute`() {
        assertEquals("1 min ago", formatAge(NOW - MINUTE, NOW))
        assertEquals("3 min ago", formatAge(NOW - 3 * MINUTE - 20 * SECOND, NOW))
        assertEquals("59 min ago", formatAge(NOW - 59 * MINUTE, NOW))
    }

    @Test
    fun `hours take over at an hour`() {
        assertEquals("1 h ago", formatAge(NOW - HOUR, NOW))
        assertEquals("2 h ago", formatAge(NOW - 2 * HOUR - 30 * MINUTE, NOW))
    }

    /**
     * The boot→epoch offset is re-read per sample, so an NTP correction mid-run can place the last
     * publish in the future. Better to read as fresh than to render "-4 s ago".
     */
    @Test
    fun `a clock that stepped backwards reads as fresh, not negative`() {
        assertEquals("just now", formatAge(NOW + 5 * SECOND, NOW))
    }
}
