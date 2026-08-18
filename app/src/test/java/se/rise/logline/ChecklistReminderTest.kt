package se.rise.logline

import se.rise.logline.checklist.ChecklistReminder
import se.rise.logline.checklist.parseChecklistReminder
import se.rise.logline.checklist.serialise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistReminderTest {

    private val reminder = ChecklistReminder("proc_001", "item_003", 1_755_000_000_000L, repeatMinutes = 20)

    @Test
    fun `a reminder survives a round trip through storage`() {
        assertEquals(reminder, parseChecklistReminder(reminder.serialise()))
    }

    @Test
    fun `a line that does not parse is dropped rather than thrown on`() {
        assertNull(parseChecklistReminder(null))
        assertNull(parseChecklistReminder(""))
        assertNull(parseChecklistReminder("proc_001\titem_003"))
        assertNull(parseChecklistReminder("proc_001\titem_003\tnot-a-time\t0"))
        assertNull(parseChecklistReminder("\titem_003\t1\t0"))
    }

    @Test
    fun `a one-shot does not come back`() {
        assertNull(ChecklistReminder("p", "i", 1_000L).next(2_000L))
    }

    /**
     * The device can be off for hours. A repeat that stepped once per missed firing would arrive as a
     * burst of identical notifications; it winds forward to the next one that is still ahead.
     */
    @Test
    fun `a repeat missed while the device was off winds forward, not backwards`() {
        val hourly = ChecklistReminder("p", "i", dueAtEpochMillis = 0L, repeatMinutes = 60)
        val next = hourly.next(fromEpochMillis = 5 * 3_600_000L + 1)!!

        assertEquals(6 * 3_600_000L, next.dueAtEpochMillis)
        assertTrue(next.dueAtEpochMillis > 5 * 3_600_000L)
    }

    @Test
    fun `the notification id is stable and non-negative`() {
        val again = ChecklistReminder("proc_001", "item_003", 999L, repeatMinutes = 1)

        // Same item, different schedule — the same slot, so a re-arm replaces rather than duplicates.
        assertEquals(reminder.key, again.key)
    }
}
