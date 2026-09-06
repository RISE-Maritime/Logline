package se.rise.logline

import keelson.ChecklistStateOuterClass.ChecklistState as ChecklistStateMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.checklist.ChecklistCodec
import se.rise.logline.checklist.ChecklistState
import se.rise.logline.checklist.ItemProgress
import se.rise.logline.checklist.ItemStatus
import se.rise.logline.checklist.RunStatus
import se.rise.logline.checklist.ProcedureSnapshot
import se.rise.logline.checklist.applySnapshot
import se.rise.logline.keelson.enclose
import java.time.Instant

/**
 * The run fields the re-vendored `ChecklistState` brought, and what the view does without them.
 *
 * These exist because the phone stopped asking the router's storage for anything — the query's reply
 * aborts the process — and reads run snapshots off pubsub instead. A snapshot is therefore the *only*
 * thing that can tell the phone what a run is called, which matters most for exactly the runs whose
 * procedure this phone has never held.
 */
class ChecklistRunFieldsTest {

    private val at: Instant = Instant.parse("2026-08-24T12:00:00Z")

    private fun snapshotBytes(
        procedureId: String,
        runId: String = "",
        title: String = "",
        status: ChecklistStateMessage.RunStatus = ChecklistStateMessage.RunStatus.RUN_STATUS_UNKNOWN,
        completed: List<String> = emptyList(),
        pending: List<String> = emptyList(),
    ): ByteArray {
        val message = ChecklistStateMessage.newBuilder()
            .setProcedureId(procedureId)
            .setRunId(runId)
            .setProcedureTitle(title)
            .setStatus(status)
            .setEventCount(completed.size)
        completed.forEach {
            message.addItems(
                ChecklistStateMessage.ItemState.newBuilder()
                    .setItemId(it)
                    .setStatus(ChecklistStateMessage.ItemState.ItemStatus.ITEM_STATUS_COMPLETED),
            )
        }
        pending.forEach {
            message.addItems(ChecklistStateMessage.ItemState.newBuilder().setItemId(it))
        }
        return enclose(message.build().toByteArray(), at)
    }

    /** The three fields, read straight off the wire. */
    @Test
    fun `a snapshot carries its run id, title and status`() {
        val decoded = ChecklistCodec.decodeSnapshot(
            snapshotBytes(
                procedureId = "proc-departure",
                runId = "run_mt2zqy4l_5_nr0chp",
                title = "Departure checks",
                status = ChecklistStateMessage.RunStatus.RUN_STATUS_ACTIVE,
                completed = listOf("item_001"),
                pending = listOf("item_002"),
            ),
        )

        assertEquals("run_mt2zqy4l_5_nr0chp", decoded!!.runId)
        assertEquals("Departure checks", decoded.procedureTitle)
        assertEquals(RunStatus.Active, decoded.status)
        assertEquals(2, decoded.items.size)
    }

    /**
     * **A publisher that predates the run model falls back to the procedure id**, which is the same
     * tolerance crowsnest's own `runIdFor` extends to this app's events. Without it a snapshot from an
     * older station would have an empty run id, which is not a name anything can be filed under.
     */
    @Test
    fun `a snapshot with no run id falls back to the procedure id`() {
        val decoded = ChecklistCodec.decodeSnapshot(snapshotBytes(procedureId = "proc-engine"))

        assertEquals("proc-engine", decoded!!.runId)
        assertEquals(RunStatus.Unknown, decoded.status)
    }

    /**
     * **A title already held is not lost to a snapshot that omits one.**
     *
     * The case is mixed publishers on one bus: a station on the run model sends the title, an older one
     * republishing the same procedure sends none, and taking the second literally would blank the row's
     * only name. Same argument the platform library makes about never taking absence for a value.
     */
    @Test
    fun `a later snapshot without a title keeps the one already known`() {
        val withTitle = ChecklistCodec.decodeSnapshot(
            snapshotBytes(
                procedureId = "proc-departure",
                title = "Departure checks",
                status = ChecklistStateMessage.RunStatus.RUN_STATUS_ACTIVE,
                completed = listOf("item_001"),
            ),
        )!!
        val withoutTitle = ChecklistCodec.decodeSnapshot(
            snapshotBytes(procedureId = "proc-departure", completed = listOf("item_001")),
        )!!

        val afterFirst = applySnapshot(ChecklistState(), withTitle)
        val afterSecond = applySnapshot(afterFirst, withoutTitle)

        assertEquals("Departure checks", afterSecond.run("proc-departure").title)
        assertEquals(RunStatus.Active, afterSecond.run("proc-departure").status)
    }

    /**
     * **Progress is counted from ids, so it is right even when the wording is missing.** That is the
     * whole of what makes "show it by id" honest rather than a shrug: the figures on the row do not
     * depend on this phone holding the procedure.
     */
    @Test
    fun `progress counts are accurate without any procedure definition`() {
        val decoded = ChecklistCodec.decodeSnapshot(
            snapshotBytes(
                procedureId = "proc-unknown-here",
                completed = listOf("item_001", "item_002"),
                pending = listOf("item_003", "item_004", "item_005"),
            ),
        )!!
        val state = applySnapshot(ChecklistState(), decoded)
        val progress = state.run("proc-unknown-here")

        assertEquals(2, progress.completedCount())
        assertEquals(5, progress.items.size)
        assertEquals(ItemStatus.Completed, progress.item("item_001").status)
        assertTrue(progress.item("item_003").status != ItemStatus.Completed)
        // And nothing invented a title for it — the screen says why instead.
        assertEquals("", progress.title)
    }

    /** Unaffected by the new fields: an item this phone has no wording for is named by its id. */
    @Test
    fun `an unknown item is named by its id`() {
        val state = se.rise.logline.checklist.ChecklistUiState(
            state = applySnapshot(
                ChecklistState(),
                ChecklistCodec.decodeSnapshot(
                    snapshotBytes(procedureId = "proc-x", pending = listOf("item_042")),
                )!!,
            ),
        )

        assertEquals("item_042", state.itemTitle("proc-x", "item_042"))
        assertEquals(ItemProgress(), state.state.run("proc-x").item("item_999"))
    }

    /**
     * **Two runs of one procedure are two rows.** This is what the re-key bought.
     *
     * Keyed on the procedure they collapsed into a single row — one run's ticks landing on the
     * other's — and that was not hypothetical: the live bus had five concurrent runs while this was
     * being looked at. §7.3 makes `checklist_state/{run_id}` one key per run for the same reason.
     */
    @Test
    fun `two runs of one procedure no longer collapse into one`() {
        val first = ProcedureSnapshot(
            procedureId = "proc-departure",
            runId = "run_a",
            eventCount = 0,
            items = mapOf("item_001" to ItemProgress(status = ItemStatus.Completed, completedAtEpochMillis = 1)),
        )
        val second = ProcedureSnapshot(
            procedureId = "proc-departure",
            runId = "run_b",
            eventCount = 0,
            items = mapOf("item_001" to ItemProgress(status = ItemStatus.Pending)),
        )

        val state = applySnapshot(applySnapshot(ChecklistState(), first), second)

        assertEquals(2, state.progress.size)
        assertEquals(ItemStatus.Completed, state.run("run_a").item("item_001").status)
        assertEquals(ItemStatus.Pending, state.run("run_b").item("item_001").status)
        assertEquals(setOf("run_a", "run_b"), state.runsOf("proc-departure").map { it.runId }.toSet())
    }

    /**
     * A record with no run id is filed under the procedure id rather than dropped — which is both
     * what upstream instructs for a publisher predating the run model, and what makes this phone's
     * own records from before the re-key keep working with no migration to run.
     */
    @Test
    fun `a run-less snapshot is adopted under the procedure id`() {
        val state = applySnapshot(
            ChecklistState(),
            ProcedureSnapshot(procedureId = "proc-departure", eventCount = 0, items = emptyMap()),
        )

        assertEquals(listOf("proc-departure"), state.progress.keys.toList())
        assertEquals("proc-departure", state.run("proc-departure").procedureId)
    }
}
