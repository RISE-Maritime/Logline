package se.rise.logline

import se.rise.logline.checklist.ChecklistState
import se.rise.logline.checklist.ItemEvidence
import se.rise.logline.checklist.ItemFlag
import se.rise.logline.checklist.ItemNote
import se.rise.logline.checklist.ItemProgress
import se.rise.logline.checklist.ItemStatus
import se.rise.logline.checklist.ProcedureItem
import se.rise.logline.checklist.ProcedureProgress
import se.rise.logline.checklist.ProcedureSnapshot
import se.rise.logline.checklist.RunStatus
import se.rise.logline.checklist.applySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot merge, one test per rule in protocol-specification.md §7.2.
 *
 * §7 is a **published specification with two independent implementations** — crowsnest's
 * `useChecklistSync.js` and this app — and it cites `ChecklistSync.kt` by name as one of them. A rule
 * broken here is no longer a local bug; it is a third implementation's worth of divergence on a
 * safety record. So each rule gets a test that names it, rather than a handful of cases that happen
 * to cover most of them.
 *
 * The rule the section opens with is the one none of these can test directly:
 *
 * > A receiver MUST NOT assign a received `ChecklistState` over local state.
 *
 * What stands in for it is [`merging two snapshots converges in either order`], because every rule
 * below is a min-register, a union or a fixed precedence, and assignment is the one thing that would
 * make the outcome depend on who arrived last.
 */
class ChecklistMergeTest {

    private fun held(vararg items: Pair<String, ItemProgress>, run: ProcedureProgress? = null) =
        ChecklistState(
            progress = mapOf(
                "proc_001" to (run ?: ProcedureProgress()).copy(items = items.toMap()),
            ),
        )

    private fun snapshot(
        vararg items: Pair<String, ItemProgress>,
        status: RunStatus = RunStatus.Unknown,
        at: Long? = null,
        title: String = "",
        itemsSnapshot: List<ProcedureItem> = emptyList(),
        createdBy: String = "",
        abandonReason: String = "",
    ) = ProcedureSnapshot(
        procedureId = "proc_001",
        eventCount = 0,
        items = items.toMap(),
        status = status,
        timestampEpochMillis = at,
        procedureTitle = title,
        itemsSnapshot = itemsSnapshot,
        createdBy = createdBy,
        abandonReason = abandonReason,
    )

    private fun ChecklistState.item(id: String = "item_001") = progressFor("proc_001").item(id)
    private fun ChecklistState.run() = progressFor("proc_001")

    // ── completion: a min-register over values ──────────────────────────────────────────────────

    /**
     * > **Earliest wins.** The incoming timestamp is compared against the held timestamp *as a
     * > value*, never against the receiver's own clock, so the outcome does not depend on who
     * > arrived first.
     *
     * The author travels with the instant, so a completion cannot end up attributed to one site at
     * a time recorded by another.
     */
    @Test
    fun `an earlier completion in a snapshot wins, and brings its author with it`() {
        val state = held(
            "item_001" to ItemProgress(
                status = ItemStatus.Completed,
                completedAtEpochMillis = 9_000,
                completedBy = "Ted",
                completedBySite = "ROC-A",
            ),
        )

        val merged = applySnapshot(
            state,
            snapshot(
                "item_001" to ItemProgress(
                    status = ItemStatus.Completed,
                    completedAtEpochMillis = 4_000,
                    completedBy = "Ana",
                    completedBySite = "ROC-B",
                ),
            ),
        ).item()

        assertEquals(4_000L, merged.completedAtEpochMillis)
        assertEquals("Ana", merged.completedBy)
        assertEquals("ROC-B", merged.completedBySite)
    }

    /** And the same comparison the other way: a later completion is not applied. */
    @Test
    fun `a later completion in a snapshot changes nothing`() {
        val state = held(
            "item_001" to ItemProgress(
                status = ItemStatus.Completed,
                completedAtEpochMillis = 4_000,
                completedBy = "Ana",
                completedBySite = "ROC-B",
            ),
        )

        val merged = applySnapshot(
            state,
            snapshot(
                "item_001" to ItemProgress(
                    status = ItemStatus.Completed,
                    completedAtEpochMillis = 9_000,
                    completedBy = "Ted",
                    completedBySite = "ROC-A",
                ),
            ),
        ).item()

        assertEquals(4_000L, merged.completedAtEpochMillis)
        assertEquals("Ana", merged.completedBy)
    }

    // ── status: monotone ────────────────────────────────────────────────────────────────────────

    /**
     * > Monotone: `PENDING → IN_PROGRESS → COMPLETED`. **An item already COMPLETED MUST NOT be moved
     * > back by a snapshot.** Reopening is a deliberate act carried by an event, never an inference
     * > from state.
     *
     * This is the failure the deleted `event_count` guard used to stand in for, and the reason it
     * could be deleted: the protection is in the merge now rather than in rejecting the message.
     */
    @Test
    fun `a snapshot cannot move a completed item back`() {
        val state = held("item_001" to ItemProgress(status = ItemStatus.Completed, completedAtEpochMillis = 1))

        assertEquals(
            ItemStatus.Completed,
            applySnapshot(state, snapshot("item_001" to ItemProgress(status = ItemStatus.Pending))).item().status,
        )
        assertEquals(
            ItemStatus.Completed,
            applySnapshot(state, snapshot("item_001" to ItemProgress(status = ItemStatus.InProgress))).item().status,
        )
    }

    /**
     * The join above is `ordinal >=`, so the *declaration order* of [ItemStatus] is load-bearing and
     * nothing else would notice it being reordered.
     */
    @Test
    fun `the item status ordinals are the progression`() {
        assertEquals(
            listOf(ItemStatus.Pending, ItemStatus.InProgress, ItemStatus.Completed),
            ItemStatus.entries.toList(),
        )
    }

    // ── unions by id ────────────────────────────────────────────────────────────────────────────

    /**
     * > Union by `note_id`. A station that never saw the note republishes an empty list, and a
     * > replacing merge erases it everywhere at once.
     *
     * The empty-incoming case is the one that matters: it is what every peer sends until it has seen
     * the event, so it is the common case rather than the edge one.
     */
    @Test
    fun `notes are unioned by id and an empty snapshot erases none`() {
        val note = ItemNote("note_9", "hi", 1_000, "Ted", "ROC-A")
        val state = held("item_001" to ItemProgress(notes = listOf(note)))

        assertEquals(
            listOf("note_9"),
            applySnapshot(state, snapshot("item_001" to ItemProgress())).item().notes.map { it.noteId },
        )
        assertEquals(
            listOf("note_9", "note_10"),
            applySnapshot(
                state,
                snapshot(
                    "item_001" to ItemProgress(
                        notes = listOf(note, ItemNote("note_10", "and", 2_000, "Ana", "ROC-B")),
                    ),
                ),
            ).item().notes.map { it.noteId },
        )
    }

    /**
     * > Union by `evidence_id`. **Never a replacement.** A station that never saw the attaching event
     * > republishes an empty list, and a replacing merge erases the photo everywhere at once.
     */
    @Test
    fun `evidence is unioned by id and an empty snapshot erases none`() {
        val photo = ItemEvidence("ev_1", caption = "quay", mediaType = "image/jpeg")
        val state = held("item_001" to ItemProgress(evidence = listOf(photo)))

        assertEquals(
            listOf("ev_1"),
            applySnapshot(state, snapshot("item_001" to ItemProgress())).item().evidence.map { it.evidenceId },
        )
    }

    /**
     * > Union by `flag_id`. Same argument again: a station that never saw the raising event
     * > republishes an empty list, and a replacing merge **clears a live safety condition everywhere
     * > at once**. Until this row existed the flag fields fell through to plain assignment, on the
     * > one field that carries a hazard.
     */
    @Test
    fun `flags are unioned by id and an empty snapshot does not clear a live one`() {
        val state = held(
            "item_001" to ItemProgress(
                flags = listOf(ItemFlag("flag_1", reason = "cracked", raisedAtEpochMillis = 1_000)),
            ).withFlagCache(),
        )

        val merged = applySnapshot(state, snapshot("item_001" to ItemProgress())).item()
        assertEquals(listOf("flag_1"), merged.flags.map { it.flagId })
        assertTrue(merged.flagged)
        assertEquals("cracked", merged.flagReason)
    }

    // ── flag resolution: absorbing, earliest wins ───────────────────────────────────────────────

    /**
     * > **Absorbing.** Once set they MUST NOT be unset by a peer that never saw the resolution.
     *
     * A stale peer republishing the flag as still open is exactly the case, and it must not reopen a
     * closed safety item.
     */
    @Test
    fun `a flag resolution is not withdrawn by a peer that never saw it`() {
        val state = held(
            "item_001" to ItemProgress(
                flags = listOf(
                    ItemFlag(
                        "flag_1",
                        reason = "cracked",
                        raisedAtEpochMillis = 1_000,
                        resolvedAtEpochMillis = 5_000,
                        resolvedBy = "Ana",
                        resolution = "welded",
                    ),
                ),
            ).withFlagCache(),
        )

        val merged = applySnapshot(
            state,
            snapshot(
                "item_001" to ItemProgress(
                    flags = listOf(ItemFlag("flag_1", reason = "cracked", raisedAtEpochMillis = 1_000)),
                ),
            ),
        ).item()

        assertEquals(5_000L, merged.flags.single().resolvedAtEpochMillis)
        assertEquals("welded", merged.flags.single().resolution)
        assertFalse("a closed flag must not reappear as open", merged.flagged)
    }

    /**
     * > Two resolutions are compared **as values** — the same min-register rule as `completed_at`,
     * > for the same reason. Two stations genuinely can clear one flag, and deciding that by whose
     * > snapshot landed last would let a stale peer reopen a closed item.
     */
    @Test
    fun `the earlier of two resolutions wins, with its author and text`() {
        val state = held(
            "item_001" to ItemProgress(
                flags = listOf(
                    ItemFlag("flag_1", resolvedAtEpochMillis = 9_000, resolvedBy = "Ted", resolution = "later"),
                ),
            ),
        )

        val merged = applySnapshot(
            state,
            snapshot(
                "item_001" to ItemProgress(
                    flags = listOf(
                        ItemFlag("flag_1", resolvedAtEpochMillis = 4_000, resolvedBy = "Ana", resolution = "earlier"),
                    ),
                ),
            ),
        ).item()

        assertEquals(4_000L, merged.flags.single().resolvedAtEpochMillis)
        assertEquals("Ana", merged.flags.single().resolvedBy)
        assertEquals("earlier", merged.flags.single().resolution)
    }

    /**
     * A resolve can arrive before the raise it closes — the two travel on the same subject with no
     * ordering guarantee — so the merge has to be commutative on a flag as well as on a list.
     */
    @Test
    fun `a resolution arriving before its raise converges to the same flag`() {
        val raise = ItemFlag("flag_1", reason = "cracked", raisedAtEpochMillis = 1_000, raisedBy = "Ted")
        val resolve = ItemFlag("flag_1", resolvedAtEpochMillis = 5_000, resolvedBy = "Ana", resolution = "welded")

        val raiseFirst = applySnapshot(
            applySnapshot(ChecklistState(), snapshot("item_001" to ItemProgress(flags = listOf(raise)))),
            snapshot("item_001" to ItemProgress(flags = listOf(resolve))),
        ).item()
        val resolveFirst = applySnapshot(
            applySnapshot(ChecklistState(), snapshot("item_001" to ItemProgress(flags = listOf(resolve)))),
            snapshot("item_001" to ItemProgress(flags = listOf(raise))),
        ).item()

        assertEquals(raiseFirst.flags, resolveFirst.flags)
        assertEquals("cracked", raiseFirst.flags.single().reason)
        assertEquals("welded", raiseFirst.flags.single().resolution)
    }

    // ── the scalars are a cache ─────────────────────────────────────────────────────────────────

    /**
     * > **Derived from `flags`, not merged.** A receiver holding a non-empty `flags` computes them
     * > from the open flag and **ignores whatever a snapshot says**.
     */
    @Test
    fun `the flag scalars are derived from the list and ignore what a snapshot claims`() {
        val state = held(
            "item_001" to ItemProgress(
                flags = listOf(
                    ItemFlag(
                        "flag_1",
                        reason = "cracked",
                        raisedAtEpochMillis = 1_000,
                        resolvedAtEpochMillis = 5_000,
                    ),
                ),
            ).withFlagCache(),
        )

        // A peer insisting, in the scalars, that the item is flagged for a different reason.
        val merged = applySnapshot(
            state,
            snapshot(
                "item_001" to ItemProgress(
                    flagged = true,
                    flagReason = "something else",
                    flaggedBy = "Ana",
                ),
            ),
        ).item()

        assertFalse(merged.flagged)
        assertEquals("", merged.flagReason)
    }

    /**
     * > They are authoritative **only** from a publisher that sends no list.
     *
     * The other direction of the same rule, and the half that keeps a pre-`flags` station working.
     */
    @Test
    fun `the flag scalars are authoritative from a publisher that sends no list`() {
        val merged = applySnapshot(
            ChecklistState(),
            snapshot(
                "item_001" to ItemProgress(flagged = true, flagReason = "cracked", flaggedBy = "Ana"),
            ),
        ).item()

        assertTrue(merged.flagged)
        assertEquals("cracked", merged.flagReason)
        assertTrue(merged.flags.isEmpty())
    }

    // ── run status ──────────────────────────────────────────────────────────────────────────────

    /**
     * > **Terminal beats non-terminal, regardless of timestamps.** Without this a station with a fast
     * > clock republishes `ACTIVE` over a signed-off run — a safety record that un-completes itself.
     */
    @Test
    fun `a terminal run is not moved back to active`() {
        val state = held(run = ProcedureProgress(status = RunStatus.Completed))

        assertEquals(
            RunStatus.Completed,
            applySnapshot(state, snapshot(status = RunStatus.Active, at = 9_999)).run().status,
        )
    }

    /**
     * > **`ABANDONED` wins.** Two sites genuinely can end one run differently — a supervisor signs it
     * > off while an operator stops it — and because both are absorbing, neither yields and the run
     * > never converges. `ABANDONED` is the side that under-claims.
     *
     * Both orders, because the precedence is fixed rather than timed: there is no `abandoned_at` to
     * compare, and a snapshot's timestamp is the publish tick rather than the moment of the act.
     */
    @Test
    fun `abandoned beats completed whichever arrives first`() {
        val completedHeld = held(run = ProcedureProgress(status = RunStatus.Completed))
        val abandonedHeld = held(run = ProcedureProgress(status = RunStatus.Abandoned))

        assertEquals(
            RunStatus.Abandoned,
            applySnapshot(completedHeld, snapshot(status = RunStatus.Abandoned, at = 1)).run().status,
        )
        assertEquals(
            RunStatus.Abandoned,
            applySnapshot(abandonedHeld, snapshot(status = RunStatus.Completed, at = 9_999)).run().status,
        )
    }

    // ── the archive ─────────────────────────────────────────────────────────────────────────────

    /**
     * > Written only on the terminal publish. **Once a receiver has seen it for a run, it MUST
     * > re-emit it on every subsequent publish of that run**, or the next peer to republish the key
     * > strips the archive.
     *
     * This is the merge half; `ChecklistWireTest` covers the publish half, which is the one a
     * merge-only implementation forgets.
     */
    @Test
    fun `an items snapshot once seen is kept when a later publisher omits it`() {
        val archive = listOf(ProcedureItem("item_001", 1, "Verify route plan"))
        val state = applySnapshot(ChecklistState(), snapshot(at = 1_000, itemsSnapshot = archive))

        assertEquals(archive, applySnapshot(state, snapshot(at = 2_000)).run().itemsSnapshot)
    }

    // ── the catch-all ───────────────────────────────────────────────────────────────────────────

    /**
     * The catch-all rule is "the value from the snapshot with the later timestamp" — but an *absence*
     * is not a value, and a publisher that predates a field sends nothing rather than a correction.
     */
    @Test
    fun `a later snapshot omitting a field does not blank it`() {
        val state = applySnapshot(
            ChecklistState(),
            snapshot(at = 1_000, title = "Departure", createdBy = "Ana", abandonReason = "fog"),
        )

        val merged = applySnapshot(state, snapshot(at = 2_000)).run()
        assertEquals("Departure", merged.title)
        assertEquals("Ana", merged.createdBy)
        assertEquals("fog", merged.abandonReason)
    }

    /**
     * `created_by` names **who created the run**, not who last republished the snapshot, and an
     * absence must render as unknown rather than falling back to the publisher.
     *
     * Crowsnest made exactly that substitution, and because it also decided which runs it republishes
     * from those fields, the publish duty silently migrated with the label. A wrong label is a
     * display bug; a wrong publisher is a convergence bug.
     */
    @Test
    fun `an unstated creator stays unknown rather than becoming the publisher`() {
        val merged = applySnapshot(
            ChecklistState(),
            ProcedureSnapshot(
                procedureId = "proc_001",
                eventCount = 0,
                items = emptyMap(),
                timestampEpochMillis = 1_000,
            ),
        ).run()

        assertEquals("", merged.createdBy)
        assertEquals("", merged.createdBySite)
        assertNull(merged.createdAtEpochMillis)
    }

    // ── the property the rest of the file exists to support ─────────────────────────────────────

    /**
     * **Order independence, which is §7.2's entire claim.**
     *
     * Every rule above is a min-register, a union or a fixed precedence, so applying the same set of
     * snapshots in any order must reach the same state. This is what stands in for the rule the
     * section opens with — assignment is precisely what would make the result depend on who arrived
     * last — and it is checked over a fixture that exercises every rule at once rather than one at a
     * time, because a merge can be right on each field and wrong on their interaction.
     */
    @Test
    fun `merging three snapshots converges in every order`() {
        val a = snapshot(
            "item_001" to ItemProgress(
                status = ItemStatus.Completed,
                completedAtEpochMillis = 9_000,
                completedBy = "Ted",
                notes = listOf(ItemNote("note_1", "first", 1_000, "Ted", "ROC-A")),
                flags = listOf(ItemFlag("flag_1", reason = "cracked", raisedAtEpochMillis = 1_000)),
            ),
            status = RunStatus.Active,
            at = 1_000,
            title = "Departure",
        )
        val b = snapshot(
            "item_001" to ItemProgress(
                status = ItemStatus.Completed,
                completedAtEpochMillis = 4_000,
                completedBy = "Ana",
                notes = listOf(ItemNote("note_2", "second", 2_000, "Ana", "ROC-B")),
                flags = listOf(ItemFlag("flag_1", resolvedAtEpochMillis = 6_000, resolution = "welded")),
                evidence = listOf(ItemEvidence("ev_1")),
            ),
            status = RunStatus.Completed,
            at = 2_000,
        )
        val c = snapshot(
            "item_002" to ItemProgress(status = ItemStatus.InProgress),
            status = RunStatus.Abandoned,
            at = 3_000,
            abandonReason = "fog",
        )

        val orders = listOf(
            listOf(a, b, c), listOf(a, c, b), listOf(b, a, c),
            listOf(b, c, a), listOf(c, a, b), listOf(c, b, a),
        )
        val results = orders.map { order ->
            order.fold(ChecklistState()) { state, s -> applySnapshot(state, s) }.run()
        }

        results.forEach { assertEquals(results.first(), it) }

        // And the converged value is the right one, not merely a consistent one.
        val converged = results.first()
        assertEquals(RunStatus.Abandoned, converged.status)
        assertEquals(4_000L, converged.item("item_001").completedAtEpochMillis)
        assertEquals("Ana", converged.item("item_001").completedBy)
        assertEquals(listOf("note_1", "note_2"), converged.item("item_001").notes.map { it.noteId })
        assertEquals(6_000L, converged.item("item_001").flags.single().resolvedAtEpochMillis)
        assertEquals("cracked", converged.item("item_001").flags.single().reason)
        assertFalse(converged.item("item_001").flagged)
        assertNotNull(converged.item("item_001").evidence.singleOrNull())
        assertEquals("Departure", converged.title)
        assertEquals("fog", converged.abandonReason)
    }
}
