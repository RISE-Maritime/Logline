package se.rise.logline.checklist

/** The event types the wire carries, mirroring `keelson.ChecklistEvent.EventType`. */
enum class ChecklistEventType {
    Unknown,
    ChecklistOpened,
    ItemStarted,
    ItemCompleted,
    ItemReverted,
    NoteAdded,
    ItemFlagged,
    FlagResolved,
    ProcedureStarted,
    ProcedureCompleted,
}

/** One decoded `keelson.ChecklistEvent`, so the reducer never sees a protobuf type. */
data class ChecklistEventRecord(
    val eventId: String,
    val atEpochMillis: Long,
    val type: ChecklistEventType,
    val operatorId: String,
    val username: String,
    val role: String,
    val rocSite: String,
    val procedureId: String,
    val itemId: String,
    val detail: String = "",
    val referenceId: String = "",
)

/** A decoded `keelson.ChecklistState` — one run's progress as some other site sees it. */
data class ProcedureSnapshot(
    val procedureId: String,
    val eventCount: Int,
    val items: Map<String, ItemProgress>,
    /**
     * The run this snapshot belongs to, and the title and status it carries — all three added upstream
     * and read here for **display only**.
     *
     * **Progress is still keyed on the procedure**, deliberately and with a known cost: two runs of one
     * procedure live at the same time collapse into a single row. Re-keying the map on the run would
     * touch the reducer, both existing screens, the reminder receiver, persistence and their tests —
     * and events would have to be re-keyed with it, or snapshots and events would write to different
     * keys in one map, which is worse than either choice. Filed rather than smuggled in here.
     *
     * The title is what lets a run render when its *procedure* is unknown to this phone: without it a
     * run the phone has never held a definition for would have nothing to call itself.
     */
    val runId: String = "",
    val procedureTitle: String = "",
    val status: RunStatus = RunStatus.Unknown,
)

/** A run's own state, as upstream models it. Unknown covers a publisher that predates the run model. */
enum class RunStatus { Unknown, Planned, Active, Completed, Abandoned }

/**
 * Everything the reducer owns: progress per procedure, a timeline to show, and what it has already
 * seen.
 *
 * [recentEventIds] is a list rather than a set because the thing that needs bounding is *which* ids to
 * forget, and that is an order. It is checked with `contains`, which is O(n) — at [EVENT_MEMORY]
 * entries against events produced by people pressing buttons, that is not a cost worth a second
 * structure to avoid.
 */
data class ChecklistState(
    val progress: Map<String, ProcedureProgress> = emptyMap(),
    /** Newest first, bounded. A record of the session, not a second copy of the audit log. */
    val timeline: List<TimelineEntry> = emptyList(),
    /** Newest first, bounded. */
    val recentEventIds: List<String> = emptyList(),
) {
    fun progressFor(procedureId: String): ProcedureProgress =
        progress[procedureId] ?: ProcedureProgress()
}

private const val TIMELINE_LIMIT = 200

/**
 * How many event ids are remembered for de-duplication.
 *
 * Sized against the thing that actually replays them: a snapshot bootstrap overlapping live events,
 * and a reconnect that re-delivers a publisher's cached samples. Both are bursts of tens, not
 * thousands; 2000 is slack, not a measurement.
 */
private const val EVENT_MEMORY = 2000

/**
 * Apply one event.
 *
 * Ported from crowsnest's `applyEvent` in `src/services/checklistSync.js`, keeping its conflict rules
 * so two implementations of the same protocol converge on the same answer:
 *
 * - **Earliest completion wins.** A second `ITEM_COMPLETED` that is not earlier does not move the
 *   completion time; it is logged as a confirmation. Two operators ticking the same item is a normal
 *   thing that happens, not a race to resolve.
 * - **An already-completed item cannot be un-started.** A late `ITEM_STARTED` is ignored, which is the
 *   only ordering defence there is: events carry no sequence number, and the timestamps come from
 *   different machines' clocks.
 *
 * Two things are added here that crowsnest's own assessment (`docs/checklist-assessment.md`, S3) says
 * it lacks: this **de-duplicates by event id**, so a snapshot bootstrap that overlaps live delivery
 * cannot apply the same completion twice; and it is a pure function of (state, event), so the same
 * stream in any grouping produces the same state.
 *
 * Returns the state unchanged for an event already seen, so the caller does not need to check first.
 *
 * @param itemTitle resolves an item id against the procedure definition, for the timeline. The
 *   definition may not have arrived yet — hand back the id and the row still reads honestly.
 */
fun applyEvent(
    state: ChecklistState,
    event: ChecklistEventRecord,
    itemTitle: (procedureId: String, itemId: String) -> String = { _, id -> id },
): ChecklistState {
    if (event.eventId.isNotEmpty() && event.eventId in state.recentEventIds) return state

    val procedure = state.progressFor(event.procedureId)
    val existing = procedure.item(event.itemId)
    var items = procedure.items
    var entry: TimelineEntry? = null

    fun put(progress: ItemProgress) {
        items = items + (event.itemId to progress)
    }

    fun mark(kind: TimelineKind, note: String = "") {
        entry = TimelineEntry(
            eventId = event.eventId,
            atEpochMillis = event.atEpochMillis,
            kind = kind,
            procedureId = event.procedureId,
            itemId = event.itemId,
            itemTitle = if (event.itemId.isEmpty()) "" else itemTitle(event.procedureId, event.itemId),
            operatorName = event.username,
            rocSite = event.rocSite,
            note = note,
        )
    }

    when (event.type) {
        ChecklistEventType.ItemStarted -> {
            // A completed item does not go back to in-progress because a straggling start arrived.
            if (existing.status != ItemStatus.Completed) {
                put(
                    existing.copy(
                        status = ItemStatus.InProgress,
                        startedAtEpochMillis = event.atEpochMillis,
                        startedBy = event.username,
                        startedByRocSite = event.rocSite,
                    )
                )
                mark(TimelineKind.Started)
            }
        }

        ChecklistEventType.ItemCompleted -> {
            val alreadyEarlier = existing.status == ItemStatus.Completed &&
                existing.completedAtEpochMillis != null &&
                existing.completedAtEpochMillis <= event.atEpochMillis
            if (alreadyEarlier) {
                mark(TimelineKind.Confirmed, "Confirmed by ${event.username} (${event.rocSite})")
            } else {
                put(
                    existing.copy(
                        status = ItemStatus.Completed,
                        completedAtEpochMillis = event.atEpochMillis,
                        completedBy = event.username,
                        completedByRocSite = event.rocSite,
                    )
                )
                mark(TimelineKind.Completed)
            }
        }

        ChecklistEventType.ItemReverted -> {
            put(
                existing.copy(
                    status = ItemStatus.Pending,
                    startedAtEpochMillis = null,
                    startedBy = "",
                    startedByRocSite = "",
                    completedAtEpochMillis = null,
                    completedBy = "",
                    completedByRocSite = "",
                )
            )
            mark(TimelineKind.Reverted)
        }

        ChecklistEventType.NoteAdded -> {
            val note = ItemNote(
                noteId = event.referenceId.ifEmpty { event.eventId },
                text = event.detail,
                atEpochMillis = event.atEpochMillis,
                author = event.username,
                authorRocSite = event.rocSite,
            )
            // Guarded by note id as well as event id: a snapshot carries notes without the events
            // that made them, so the same note can arrive by both routes.
            if (existing.notes.none { it.noteId == note.noteId }) {
                put(existing.copy(notes = existing.notes + note))
            }
            mark(TimelineKind.NoteAdded, event.detail)
        }

        ChecklistEventType.ItemFlagged -> {
            put(
                existing.copy(
                    flagged = true,
                    flagReason = event.detail,
                    flaggedBy = event.username,
                    flaggedByRocSite = event.rocSite,
                )
            )
            mark(TimelineKind.Flagged, event.detail)
        }

        ChecklistEventType.FlagResolved -> {
            put(
                existing.copy(
                    flagged = false,
                    flagReason = "",
                    flaggedBy = "",
                    flaggedByRocSite = "",
                )
            )
            mark(TimelineKind.FlagResolved, event.detail)
        }

        ChecklistEventType.ProcedureStarted, ChecklistEventType.ChecklistOpened ->
            mark(TimelineKind.ProcedureStarted)

        ChecklistEventType.ProcedureCompleted -> mark(TimelineKind.ProcedureCompleted)

        ChecklistEventType.Unknown -> Unit
    }

    return state.copy(
        progress = state.progress + (
            event.procedureId to procedure.copy(
                items = items,
                eventCount = procedure.eventCount + 1,
            )
            ),
        timeline = entry?.let { (listOf(it) + state.timeline).take(TIMELINE_LIMIT) } ?: state.timeline,
        recentEventIds = (listOf(event.eventId) + state.recentEventIds).take(EVENT_MEMORY),
    )
}

/**
 * Apply a snapshot from another site.
 *
 * Two rules, both learned the hard way in crowsnest (`docs/checklist-assessment.md`, S2):
 *
 * - **The version guard.** A snapshot whose `event_count` is below what we have already applied is
 *   older than local state; writing it would flip a just-completed item back to pending. Without this
 *   the `get` that bootstraps a procedure races the live events arriving while it is in flight.
 * - **Merge per item, never replace the procedure.** A snapshot only carries items somebody has
 *   touched. Replacing the map would discard progress this phone made offline and has not yet had
 *   acknowledged.
 */
fun applySnapshot(state: ChecklistState, snapshot: ProcedureSnapshot): ChecklistState {
    val current = state.progressFor(snapshot.procedureId)
    if (snapshot.eventCount < current.eventCount) return state
    return state.copy(
        progress = state.progress + (
            snapshot.procedureId to ProcedureProgress(
                items = current.items + snapshot.items,
                eventCount = snapshot.eventCount,
                // Kept from whatever last carried them: an older publisher sends neither, and losing a
                // title the phone already has because the next snapshot came from a pre-run-model
                // station would make the row anonymous for no reason.
                title = snapshot.procedureTitle.ifEmpty { current.title },
                status = if (snapshot.status == RunStatus.Unknown) current.status else snapshot.status,
            )
            ),
    )
}
