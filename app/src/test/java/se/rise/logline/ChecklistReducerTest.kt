package se.rise.logline

import se.rise.logline.checklist.ChecklistEventRecord
import se.rise.logline.checklist.ChecklistEventType
import se.rise.logline.checklist.ChecklistState
import se.rise.logline.checklist.ItemProgress
import se.rise.logline.checklist.ItemStatus
import se.rise.logline.checklist.ProcedureSnapshot
import se.rise.logline.checklist.applyEvent
import se.rise.logline.checklist.applySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conflict rules, which are the whole reason two sites can work one checklist.
 *
 * Every case here is one that actually happens on a shared bus: the same item ticked at two ROCs, a
 * snapshot arriving after the events it was built from, a publisher's cache re-delivering on reconnect.
 */
class ChecklistReducerTest {

    private fun event(
        id: String,
        type: ChecklistEventType,
        at: Long,
        itemId: String = "item_001",
        who: String = "Ted",
        site: String = "ROC-A",
        detail: String = "",
        referenceId: String = "",
    ) = ChecklistEventRecord(
        eventId = id,
        atEpochMillis = at,
        type = type,
        operatorId = "op-$who",
        username = who,
        role = "",
        rocSite = site,
        procedureId = "proc_001",
        itemId = itemId,
        detail = detail,
        referenceId = referenceId,
    )

    @Test
    fun `completing an item records who and when`() {
        val state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.ItemCompleted, 1_000))

        val item = state.progressFor("proc_001").item("item_001")
        assertEquals(ItemStatus.Completed, item.status)
        assertEquals(1_000L, item.completedAtEpochMillis)
        assertEquals("Ted", item.completedBy)
        assertEquals("ROC-A", item.completedBySite)
    }

    /**
     * The publisher has a sample cache and the bootstrap query overlaps live delivery, so the same
     * event genuinely arrives twice. Without this it is applied twice — harmless for a completion,
     * not harmless for a note.
     */
    @Test
    fun `the same event id applied twice changes nothing the second time`() {
        val once = applyEvent(ChecklistState(), event("e1", ChecklistEventType.ItemCompleted, 1_000))
        val twice = applyEvent(once, event("e1", ChecklistEventType.ItemCompleted, 1_000))

        assertEquals(once, twice)
        assertEquals(1, twice.timeline.size)
    }

    /** Two operators ticking the same item is normal. The earlier tick is the one that happened. */
    @Test
    fun `a later completion does not overwrite an earlier one`() {
        var state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.ItemCompleted, 1_000))
        state = applyEvent(state, event("e2", ChecklistEventType.ItemCompleted, 5_000, who = "Ana", site = "ROC-B"))

        val item = state.progressFor("proc_001").item("item_001")
        assertEquals(1_000L, item.completedAtEpochMillis)
        assertEquals("Ted", item.completedBy)
        // Still recorded, as a confirmation — the second operator did do something.
        assertEquals(2, state.timeline.size)
    }

    @Test
    fun `an earlier completion arriving late wins`() {
        var state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.ItemCompleted, 5_000))
        state = applyEvent(state, event("e2", ChecklistEventType.ItemCompleted, 1_000, who = "Ana"))

        assertEquals(1_000L, state.progressFor("proc_001").item("item_001").completedAtEpochMillis)
    }

    /** Events carry no sequence number, so this is the only ordering defence there is. */
    @Test
    fun `a start arriving after a completion does not reopen the item`() {
        var state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.ItemCompleted, 5_000))
        state = applyEvent(state, event("e2", ChecklistEventType.ItemStarted, 1_000))

        assertEquals(ItemStatus.Completed, state.progressFor("proc_001").item("item_001").status)
    }

    @Test
    fun `reverting clears both the start and the completion`() {
        var state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.ItemStarted, 1_000))
        state = applyEvent(state, event("e2", ChecklistEventType.ItemCompleted, 2_000))
        state = applyEvent(state, event("e3", ChecklistEventType.ItemReverted, 3_000))

        val item = state.progressFor("proc_001").item("item_001")
        assertEquals(ItemStatus.Pending, item.status)
        assertEquals(null, item.completedAtEpochMillis)
        assertEquals(null, item.startedAtEpochMillis)
        assertEquals("", item.completedBy)
    }

    /**
     * A note arrives twice by two routes — as an event, and again inside a snapshot — so the event id
     * is not enough on its own.
     */
    @Test
    fun `the same note is not added twice under different event ids`() {
        var state = applyEvent(
            ChecklistState(),
            event("e1", ChecklistEventType.NoteAdded, 1_000, detail = "check again", referenceId = "note_9"),
        )
        state = applyEvent(
            state,
            event("e2", ChecklistEventType.NoteAdded, 1_000, detail = "check again", referenceId = "note_9"),
        )

        assertEquals(1, state.progressFor("proc_001").item("item_001").notes.size)
    }

    @Test
    fun `flagging and resolving move the flag and its reason together`() {
        var state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.ItemFlagged, 1_000, detail = "seized"))
        assertTrue(state.progressFor("proc_001").item("item_001").flagged)
        assertEquals("seized", state.progressFor("proc_001").item("item_001").flagReason)

        state = applyEvent(state, event("e2", ChecklistEventType.FlagResolved, 2_000, detail = "freed"))
        assertFalse(state.progressFor("proc_001").item("item_001").flagged)
        assertEquals("", state.progressFor("proc_001").item("item_001").flagReason)
    }

    @Test
    fun `the timeline resolves item titles against the procedure when it has one`() {
        val state = applyEvent(
            ChecklistState(),
            event("e1", ChecklistEventType.ItemCompleted, 1_000),
        ) { _, itemId -> if (itemId == "item_001") "Test VHF radio" else itemId }

        assertEquals("Test VHF radio", state.timeline.first().itemTitle)
    }

    /**
     * A snapshot that has taken in fewer events than local state **is still merged**, and loses
     * nothing by it.
     *
     * This test used to be `a snapshot older than local state is ignored`, and it pinned a guard —
     * `if (snapshot.eventCount < current.eventCount) return state` — that §7.2 now forbids outright.
     * The count is a scalar, so two sites that each applied a *different* twelve events both hold 12
     * and each rejects the other as stale; neither ever converges.
     *
     * What made the guard *look* necessary is the interesting part: the merge underneath it was a
     * whole-item replace, so an older snapshot genuinely could flip a completed item back to
     * pending. §7.2's rules remove the need for it — status is monotone and a completion is a
     * min-register — so the protection moved from "reject the message" to "merge it correctly",
     * which is the only version of it that converges.
     */
    @Test
    fun `a snapshot with a lower event count is merged and takes nothing away`() {
        var state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.ItemCompleted, 5_000))
        state = applyEvent(state, event("e2", ChecklistEventType.ItemCompleted, 6_000, itemId = "item_002"))

        val behind = ProcedureSnapshot(
            procedureId = "proc_001",
            eventCount = 1,
            items = mapOf("item_001" to ItemProgress(status = ItemStatus.Pending)),
        )

        val merged = applySnapshot(state, behind).progressFor("proc_001")
        assertEquals(ItemStatus.Completed, merged.item("item_001").status)
        assertEquals(ItemStatus.Completed, merged.item("item_002").status)
        // The hint does not go backwards either: `max`, so a peer's lower count cannot make local
        // state look less advanced than it is.
        assertEquals(2, merged.eventCount)
    }

    /** A snapshot only carries items somebody touched, so replacing the map would lose the rest. */
    @Test
    fun `a newer snapshot merges rather than replacing`() {
        val state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.ItemCompleted, 5_000))

        val merged = applySnapshot(
            state,
            ProcedureSnapshot(
                procedureId = "proc_001",
                eventCount = 9,
                items = mapOf("item_002" to ItemProgress(status = ItemStatus.Completed)),
            ),
        )

        val progress = merged.progressFor("proc_001")
        assertEquals(ItemStatus.Completed, progress.item("item_001").status)
        assertEquals(ItemStatus.Completed, progress.item("item_002").status)
        assertEquals(9, progress.eventCount)
    }

    @Test
    fun `an unknown event type is a no-op rather than a crash`() {
        val state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.Unknown, 1_000))

        assertEquals(ItemStatus.Pending, state.progressFor("proc_001").item("item_001").status)
        assertTrue(state.timeline.isEmpty())
    }
}
