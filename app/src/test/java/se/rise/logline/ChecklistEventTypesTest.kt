package se.rise.logline

import se.rise.logline.checklist.ChecklistEventRecord
import se.rise.logline.checklist.ChecklistEventType
import se.rise.logline.checklist.ChecklistState
import se.rise.logline.checklist.ItemEvidence
import se.rise.logline.checklist.ItemStatus
import se.rise.logline.checklist.RunStatus
import se.rise.logline.checklist.TimeField
import se.rise.logline.checklist.TimelineKind
import se.rise.logline.checklist.applyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The event types the run model added, and the two legacy shapes that must keep working alongside
 * them.
 *
 * The theme running through this file is that upstream added each of these *without* retiring what
 * it replaced — `ITEM_REVERTED` stays valid, the ISO-8601 string in `detail` stays readable, a
 * `FLAG_RESOLVED` with no `reference_id` still has to close something — because recordings and
 * durable snapshots already contain the old shapes and must keep decoding.
 */
class ChecklistEventTypesTest {

    private fun event(
        id: String,
        type: ChecklistEventType,
        at: Long,
        itemId: String = "item_001",
        detail: String = "",
        referenceId: String = "",
        evidence: ItemEvidence? = null,
        correctedTimeEpochMillis: Long? = null,
        correctedField: TimeField = TimeField.Unknown,
    ) = ChecklistEventRecord(
        eventId = id,
        atEpochMillis = at,
        type = type,
        operatorId = "op-1",
        username = "Ted",
        role = "",
        rocSite = "ROC-A",
        procedureId = "proc_001",
        itemId = itemId,
        detail = detail,
        referenceId = referenceId,
        evidence = evidence,
        correctedTimeEpochMillis = correctedTimeEpochMillis,
        correctedField = correctedField,
    )

    private fun completed(at: Long = 5_000) =
        applyEvent(ChecklistState(), event("e1", ChecklistEventType.ItemCompleted, at))

    private fun ChecklistState.item(id: String = "item_001") = progressFor("proc_001").item(id)
    private fun ChecklistState.run() = progressFor("proc_001")

    // ── the two spellings of one act ────────────────────────────────────────────────────────────

    /**
     * `EVENT_TYPE_ITEM_REVERTED` (4) and `EVENT_TYPE_ITEM_REOPENED` (15) are the same act.
     *
     * Upstream deliberately did **not** reserve 4 when it added 15: publishers had been spelling the
     * act as `ITEM_REVERTED` because adding an enum value costs a release cycle, the behaviour and
     * the audit trail were right, and only the name misled. Recordings already contain 4.
     */
    @Test
    fun `reverted and reopened produce identical state`() {
        val reverted = applyEvent(completed(), event("e2", ChecklistEventType.ItemReverted, 6_000, detail = "wrong item"))
        val reopened = applyEvent(completed(), event("e2", ChecklistEventType.ItemReopened, 6_000, detail = "wrong item"))

        assertEquals(reverted, reopened)
        assertEquals(ItemStatus.Pending, reopened.item().status)
        assertNull(reopened.item().completedAtEpochMillis)
        // The reason is what makes a reopen auditable rather than an erasure.
        assertEquals("wrong item", reopened.timeline.first().note)
    }

    // ── time corrections ────────────────────────────────────────────────────────────────────────

    /**
     * A `TIME_SET` **moves a completion later**, which `ITEM_COMPLETED` cannot — and that is the
     * entire reason the type exists. A backdated completion would hit the earliest-wins guard on
     * every station, log a "confirmed" row, and change nothing.
     */
    @Test
    fun `a time correction can move a completion later, unlike a second completion`() {
        val corrected = applyEvent(
            completed(at = 5_000),
            event(
                "e2", ChecklistEventType.TimeSet, 9_000,
                correctedTimeEpochMillis = 8_000,
                correctedField = TimeField.ItemCompletedAt,
            ),
        )
        assertEquals(8_000L, corrected.item().completedAtEpochMillis)

        val recompleted = applyEvent(completed(at = 5_000), event("e2", ChecklistEventType.ItemCompleted, 8_000))
        assertEquals(5_000L, recompleted.item().completedAtEpochMillis)
    }

    /** It patches **only** the timestamp: status and author are untouched. */
    @Test
    fun `a time correction leaves the status and the author alone`() {
        val before = completed().item()
        val after = applyEvent(
            completed(),
            event(
                "e2", ChecklistEventType.TimeSet, 9_000,
                correctedTimeEpochMillis = 8_000,
                correctedField = TimeField.ItemCompletedAt,
            ),
        ).item()

        assertEquals(before.status, after.status)
        assertEquals(before.completedBy, after.completedBy)
        assertEquals(before.completedBySite, after.completedBySite)
    }

    /**
     * The legacy shape: before the typed pair existed, the instant travelled as a UTC ISO-8601
     * string in `detail` and the field name as a string in `reference_id`. Recordings full of it
     * must keep replaying.
     */
    @Test
    fun `a legacy correction falls back to the string in detail`() {
        val corrected = applyEvent(
            completed(),
            event(
                "e2", ChecklistEventType.TimeSet, 9_000,
                detail = Instant.ofEpochMilli(8_000).toString(),
                referenceId = "completed_at",
            ),
        ).item()

        assertEquals(8_000L, corrected.completedAtEpochMillis)
    }

    /**
     * And the precedence between them is one-way: the typed field is read first, the string only
     * when the field is `UNKNOWN`. Never the other way round, so a publisher writing both — which
     * upstream asks for during the migration — cannot be misread.
     */
    @Test
    fun `the typed field wins over a disagreeing legacy string`() {
        val corrected = applyEvent(
            completed(),
            event(
                "e2", ChecklistEventType.TimeSet, 9_000,
                detail = Instant.ofEpochMilli(1_000).toString(),
                referenceId = "started_at",
                correctedTimeEpochMillis = 8_000,
                correctedField = TimeField.ItemCompletedAt,
            ),
        ).item()

        assertEquals(8_000L, corrected.completedAtEpochMillis)
        assertNull(corrected.startedAtEpochMillis)
    }

    /**
     * An unrecognised field patches nothing — **dropped, not guessed**, because a correction applied
     * to the wrong field is worse than one not applied at all. The row still appears, so the attempt
     * is on the record rather than silently gone.
     */
    @Test
    fun `an uninterpretable correction patches nothing and still leaves a row`() {
        val state = applyEvent(
            completed(),
            event("e2", ChecklistEventType.TimeSet, 9_000, detail = "yesterday afternoon"),
        )

        assertEquals(5_000L, state.item().completedAtEpochMillis)
        assertEquals(TimelineKind.TimeCorrected, state.timeline.first().kind)
    }

    // ── flags as a record ───────────────────────────────────────────────────────────────────────

    /**
     * Flagging then resolving by `reference_id` closes **the same flag** rather than opening a
     * second, and the closed one stays on the record. That last part is the change: the five scalars
     * carry no history, so resolving used to leave no trace that an issue had ever been raised.
     */
    @Test
    fun `resolving by reference id closes the flag it names and keeps it`() {
        var state = applyEvent(
            ChecklistState(),
            event("e1", ChecklistEventType.ItemFlagged, 1_000, detail = "cracked", referenceId = "flag_1"),
        )
        state = applyEvent(
            state,
            event("e2", ChecklistEventType.FlagResolved, 5_000, detail = "welded", referenceId = "flag_1"),
        )

        val flag = state.item().flags.single()
        assertEquals("cracked", flag.reason)
        assertEquals("welded", flag.resolution)
        assertEquals(5_000L, flag.resolvedAtEpochMillis)
        assertFalse(state.item().flagged)
    }

    /**
     * A `FLAG_RESOLVED` with no `reference_id` closes the one open flag.
     *
     * That is not a hypothetical legacy publisher — it is **this app until now**, which published
     * flag events with an empty reference. Without the fallback a resolve can only append, so it
     * would open a second flag instead of closing the first: the item would report itself flagged
     * immediately after somebody cleared it.
     */
    @Test
    fun `resolving with no reference id closes the one open flag`() {
        var state = applyEvent(
            ChecklistState(),
            event("e1", ChecklistEventType.ItemFlagged, 1_000, detail = "cracked"),
        )
        state = applyEvent(state, event("e2", ChecklistEventType.FlagResolved, 5_000, detail = "welded"))

        assertEquals(1, state.item().flags.size)
        assertFalse(state.item().flagged)
    }

    /** The scalars stay in step, because every write to the list goes through the cache. */
    @Test
    fun `flagging fills the scalar cache from the list`() {
        val state = applyEvent(
            ChecklistState(),
            event("e1", ChecklistEventType.ItemFlagged, 1_000, detail = "cracked", referenceId = "flag_1"),
        )

        assertTrue(state.item().flagged)
        assertEquals("cracked", state.item().flagReason)
        assertEquals("Ted", state.item().flaggedBy)
        assertEquals(1_000L, state.item().flaggedAtEpochMillis)
    }

    // ── evidence ────────────────────────────────────────────────────────────────────────────────

    /** Union by evidence id here too: the same photo arrives as an event and inside a snapshot. */
    @Test
    fun `evidence is attached once however many times the event arrives`() {
        val photo = ItemEvidence("ev_1", caption = "quay", mediaType = "image/jpeg")
        var state = applyEvent(
            ChecklistState(),
            event("e1", ChecklistEventType.EvidenceAttached, 1_000, evidence = photo),
        )
        // A different event id carrying the same photo — a re-publish, not a second photograph.
        state = applyEvent(
            state,
            event("e2", ChecklistEventType.EvidenceAttached, 2_000, evidence = photo),
        )

        assertEquals(listOf("ev_1"), state.item().evidence.map { it.evidenceId })
    }

    // ── run boundaries ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `planning and abandoning move the run's own status`() {
        var state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.RunPlanned, 1_000, itemId = ""))
        assertEquals(RunStatus.Planned, state.run().status)

        state = applyEvent(state, event("e2", ChecklistEventType.ProcedureStarted, 2_000, itemId = ""))
        assertEquals(RunStatus.Active, state.run().status)
        assertEquals(2_000L, state.run().startedAtEpochMillis)

        state = applyEvent(state, event("e3", ChecklistEventType.RunAbandoned, 3_000, itemId = "", detail = "fog"))
        assertEquals(RunStatus.Abandoned, state.run().status)
        assertEquals("fog", state.run().abandonReason)
    }

    /**
     * A run's status is joined rather than assigned even on the event path, so a `PROCEDURE_STARTED`
     * replayed out of a publisher's cache after the run ended cannot un-abandon it.
     */
    @Test
    fun `a replayed start does not revive a terminal run`() {
        var state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.RunAbandoned, 3_000, itemId = ""))
        state = applyEvent(state, event("e2", ChecklistEventType.ProcedureStarted, 4_000, itemId = ""))

        assertEquals(RunStatus.Abandoned, state.run().status)
    }

    /**
     * An event type this build cannot act on does not advance `event_count`.
     *
     * The count is a staleness hint a peer reads, and counting events that incorporated nothing
     * overstates what this state has taken in — which matters more now that the count is a hint
     * rather than a guard, because a hint is only worth having if it is honest.
     */
    @Test
    fun `an unknown event type does not advance the event count`() {
        val state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.Unknown, 1_000))

        assertEquals(0, state.run().eventCount)
    }

    /** An event from a publisher predating the run model is adopted, never dropped. */
    @Test
    fun `an event with no run id is filed under the procedure id`() {
        val state = applyEvent(ChecklistState(), event("e1", ChecklistEventType.ItemCompleted, 1_000))

        assertEquals(ItemStatus.Completed, state.progressFor("proc_001").item("item_001").status)
    }
}
