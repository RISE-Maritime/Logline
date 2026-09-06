package se.rise.logline.checklist

/** The event types the wire carries, mirroring `keelson.ChecklistEvent.EventType`. */
enum class ChecklistEventType {
    Unknown,
    ChecklistOpened,
    ItemStarted,
    ItemCompleted,

    /**
     * An item taken back out of "done". **The same act as [ItemReopened]**, and kept because
     * upstream deliberately did not reserve its number: recordings and durable snapshots already
     * contain `4`, and they must keep decoding. Only the name misled — "reverted" reads as undoing a
     * mistake, where reopening is a deliberate, reasoned act performed on a record that stands.
     */
    ItemReverted,
    NoteAdded,
    ItemFlagged,
    FlagResolved,
    ProcedureStarted,
    ProcedureCompleted,

    /** A run queued for later, with or without a due time. */
    RunPlanned,

    /** A run stopped without completing. The reason is required and travels in `detail`. */
    RunAbandoned,

    /** A photo attached to an item. Metadata rides in `evidence`; the bytes go on their own key. */
    EvidenceAttached,

    /**
     * A timestamp corrected after the fact — "I finished at 14:45, I am only ticking it now".
     *
     * A type of its own rather than a backdated [ItemCompleted], because completion resolves
     * earliest-wins: a completion moved *later* hits that guard on every station, logs a
     * "confirmed" row and changes nothing. The guard is right for genuine concurrent completion and
     * must not be relaxed for this.
     */
    TimeSet,

    /** See [ItemReverted]. Preferred for anything published from now on. */
    ItemReopened,
}

/**
 * Which timestamp a [ChecklistEventType.TimeSet] corrects.
 *
 * An enum rather than the field's name as a string, because a string target is matched by every
 * consumer against its own spelling: a typo is not an error, it is a silent no-op that logs an
 * audited correction and patches nothing.
 */
enum class TimeField {
    Unknown,
    ItemStartedAt,
    ItemCompletedAt,

    /** The run's own start. Ignores the item id. */
    RunStartedAt,
}

/**
 * Which spelling of "take this back out of done" this app puts on the wire.
 *
 * 15 from now on; 4 still decodes and means the same thing.
 */
internal val REOPEN_EVENT_TYPE = ChecklistEventType.ItemReopened

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
    /**
     * Which additive entry this event names: a note id, an evidence id, or a **flag id**.
     *
     * Each is the union key its list merges on in §7.2, so an event that omits this can only append
     * — it cannot say which existing entry it acts on. That is why resolving a flag without one used
     * to open a second flag rather than closing the first.
     *
     * Unrelated legacy meaning: on a [ChecklistEventType.TimeSet] from a publisher predating
     * [correctedField], this carries the *name* of the timestamp being corrected — one of
     * `started_at`, `completed_at`, `run_started_at`.
     */
    val referenceId: String = "",
    /**
     * Which run of [procedureId] this event belongs to.
     *
     * Empty means a publisher that predates the run model; such an event is adopted under a legacy
     * run keyed by the procedure id rather than dropped. See [runIdOf].
     */
    val runId: String = "",
    /** Set on [ChecklistEventType.EvidenceAttached]. Metadata only — the bytes are on their own key. */
    val evidence: ItemEvidence? = null,
    /** The corrected instant, on [ChecklistEventType.TimeSet]. */
    val correctedTimeEpochMillis: Long? = null,
    /** Which timestamp [correctedTimeEpochMillis] replaces. Set together with it, or neither. */
    val correctedField: TimeField = TimeField.Unknown,
)

/**
 * The run an event or a snapshot belongs to.
 *
 * `run_id` empty means a publisher that predates the run model, and upstream's own instruction is to
 * adopt it under a legacy run keyed by the procedure id rather than drop it. Crowsnest's `runIdFor`
 * does the same, which is what makes this phone's pre-run-model records keep working after the
 * re-key: they decode with an empty run id and land where they always were.
 */
internal fun runIdOf(runId: String, procedureId: String): String = runId.ifEmpty { procedureId }

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
    val procedureVersion: String = "",
    /**
     * The snapshot's own `ChecklistState.timestamp` — the **publish tick**, not the moment of any
     * act, which is exactly why §7.2 uses it only for the fields it leaves to "the later timestamp"
     * and never to settle a completion, a resolution or a run's status.
     *
     * It was not decoded at all before; the catch-all rule has nothing to compare without it.
     */
    val timestampEpochMillis: Long? = null,
    val startedAtEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    val scheduledForEpochMillis: Long? = null,
    val abandonReason: String = "",
    val createdBy: String = "",
    val createdBySite: String = "",
    val createdAtEpochMillis: Long? = null,
    val itemsSnapshot: List<ProcedureItem> = emptyList(),
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
    // The run-level fields a run-boundary event moves. Held separately from `items` because these
    // events name no item at all — `EVENT_TYPE_RUN_ABANDONED` carries a reason and nothing else.
    var status = procedure.status
    var startedAt = procedure.startedAtEpochMillis
    var completedAt = procedure.completedAtEpochMillis
    var abandonReason = procedure.abandonReason

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
                        startedBySite = event.rocSite,
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
                        completedBySite = event.rocSite,
                    )
                )
                mark(TimelineKind.Completed)
            }
        }

        // One act, two spellings. Upstream keeps 4 valid and deliberately does not reserve it,
        // because recordings and durable snapshots already contain it; 15 is what this app publishes
        // from now on. Sharing the branch is the only way the two cannot drift apart.
        ChecklistEventType.ItemReverted, ChecklistEventType.ItemReopened -> {
            put(
                existing.copy(
                    status = ItemStatus.Pending,
                    startedAtEpochMillis = null,
                    startedBy = "",
                    startedBySite = "",
                    completedAtEpochMillis = null,
                    completedBy = "",
                    completedBySite = "",
                )
            )
            // The reason travels, and it is the thing that distinguishes a reopen from a mistake.
            mark(TimelineKind.Reverted, event.detail)
        }

        ChecklistEventType.NoteAdded -> {
            val note = ItemNote(
                noteId = event.referenceId.ifEmpty { event.eventId },
                text = event.detail,
                atEpochMillis = event.atEpochMillis,
                author = event.username,
                authorSite = event.rocSite,
            )
            // Guarded by note id as well as event id: a snapshot carries notes without the events
            // that made them, so the same note can arrive by both routes.
            if (existing.notes.none { it.noteId == note.noteId }) {
                put(existing.copy(notes = existing.notes + note))
            }
            mark(TimelineKind.NoteAdded, event.detail)
        }

        // Both flag events fold into `flags` and let `withFlagCache()` recompute the scalars, rather
        // than setting and clearing them by hand. That is what makes the resolve survive: the
        // scalars carry no history, so clearing them used to leave no trace that an issue had ever
        // been raised. Idempotent by flag id through `mergeFlags`, exactly as a note is by note id.
        ChecklistEventType.ItemFlagged -> {
            val flagId = event.referenceId.ifEmpty { event.eventId }
            put(
                existing.copy(
                    flags = mergeFlags(
                        existing.flags,
                        listOf(
                            ItemFlag(
                                flagId = flagId,
                                reason = event.detail,
                                raisedAtEpochMillis = event.atEpochMillis,
                                raisedBy = event.username,
                                raisedBySite = event.rocSite,
                            ),
                        ),
                    ),
                ).withFlagCache()
            )
            mark(TimelineKind.Flagged, event.detail)
        }

        ChecklistEventType.FlagResolved -> {
            // `reference_id` names the flag being closed. Empty is a publisher predating that rule —
            // including this app until now — so fall back to the one open flag rather than appending
            // a second, which is the hazard upstream describes: without a match a receiver can only
            // append, so resolving would add a second open flag instead of closing the first.
            val flagId = event.referenceId.ifEmpty { existing.openFlag()?.flagId }.orEmpty()
                .ifEmpty { event.eventId }
            put(
                existing.copy(
                    flags = mergeFlags(
                        existing.flags,
                        listOf(
                            ItemFlag(
                                flagId = flagId,
                                resolvedAtEpochMillis = event.atEpochMillis,
                                resolvedBy = event.username,
                                resolvedBySite = event.rocSite,
                                resolution = event.detail,
                            ),
                        ),
                    ),
                ).withFlagCache()
            )
            mark(TimelineKind.FlagResolved, event.detail)
        }

        ChecklistEventType.EvidenceAttached -> {
            // Union by evidence id, the same rule the snapshot merge uses — the event and a snapshot
            // both deliver this metadata, so the same photo arrives twice by different routes.
            event.evidence?.let { attached ->
                if (existing.evidence.none { it.evidenceId == attached.evidenceId }) {
                    put(existing.copy(evidence = existing.evidence + attached))
                }
            }
            mark(TimelineKind.EvidenceAttached, event.evidence?.caption.orEmpty())
        }

        ChecklistEventType.RunPlanned -> {
            status = joinRunStatus(status, RunStatus.Planned)
            mark(TimelineKind.RunPlanned, event.detail)
        }

        ChecklistEventType.RunAbandoned -> {
            status = joinRunStatus(status, RunStatus.Abandoned)
            // `""` is a legitimate value — a client that collected no reason — so a receiver must not
            // read empty as "no reason given" versus "reason lost". Never blank one already held.
            abandonReason = abandonReason.ifEmpty { event.detail }
            mark(TimelineKind.RunAbandoned, event.detail)
        }

        // The one event that may move a timestamp *later*, which is the whole reason it exists: the
        // earliest-wins guard on `ItemCompleted` is right for concurrent completion and would turn a
        // deliberate correction into a "confirmed" row that changes nothing. It patches ONLY the
        // timestamp — status and author are untouched — and it is recorded as its own kind, because
        // in a safety record a corrected time that renders identically to an original one is a claim
        // nobody can audit.
        ChecklistEventType.TimeSet -> {
            // The typed pair is read first and the legacy string only when the field is unknown —
            // never the other way round, so a publisher writing both cannot be misread.
            val field = if (event.correctedField != TimeField.Unknown) {
                event.correctedField
            } else {
                when (event.referenceId) {
                    "started_at" -> TimeField.ItemStartedAt
                    "completed_at" -> TimeField.ItemCompletedAt
                    "run_started_at" -> TimeField.RunStartedAt
                    else -> TimeField.Unknown
                }
            }
            val at = event.correctedTimeEpochMillis ?: parseIsoInstantOrNull(event.detail)
            when {
                // Dropped, not guessed: a correction applied to the wrong field is worse than one
                // not applied at all. The row still appears, so the attempt is on the record.
                at == null || field == TimeField.Unknown ->
                    mark(TimelineKind.TimeCorrected, "correction received but not applied")
                field == TimeField.ItemStartedAt -> {
                    put(existing.copy(startedAtEpochMillis = at))
                    mark(TimelineKind.TimeCorrected, event.detail)
                }
                field == TimeField.ItemCompletedAt -> {
                    put(existing.copy(completedAtEpochMillis = at))
                    mark(TimelineKind.TimeCorrected, event.detail)
                }
                else -> {
                    startedAt = at
                    mark(TimelineKind.TimeCorrected, event.detail)
                }
            }
        }

        ChecklistEventType.ProcedureStarted -> {
            status = joinRunStatus(status, RunStatus.Active)
            startedAt = earliestOf(startedAt, event.atEpochMillis)
            mark(TimelineKind.ProcedureStarted)
        }

        // Opening a checklist is not starting a run — somebody looked at it. Timeline only.
        ChecklistEventType.ChecklistOpened -> mark(TimelineKind.ProcedureStarted)

        ChecklistEventType.ProcedureCompleted -> {
            status = joinRunStatus(status, RunStatus.Completed)
            completedAt = earliestOf(completedAt, event.atEpochMillis)
            mark(TimelineKind.ProcedureCompleted)
        }

        ChecklistEventType.Unknown -> Unit
    }

    return state.copy(
        progress = state.progress + (
            event.procedureId to procedure.copy(
                items = items,
                status = status,
                startedAtEpochMillis = startedAt,
                completedAtEpochMillis = completedAt,
                abandonReason = abandonReason,
                // Not bumped for an event this build could not act on. `event_count` is a staleness
                // hint a peer reads, and counting events that incorporated nothing overstates what
                // this state has taken in.
                eventCount = procedure.eventCount +
                    if (event.type == ChecklistEventType.Unknown) 0 else 1,
            )
            ),
        timeline = entry?.let { (listOf(it) + state.timeline).take(TIMELINE_LIMIT) } ?: state.timeline,
        recentEventIds = (listOf(event.eventId) + state.recentEventIds).take(EVENT_MEMORY),
    )
}

/**
 * Apply a snapshot from another site, field by field, per protocol-specification.md §7.2.
 *
 * > **A receiver MUST NOT assign a received `ChecklistState` over local state.**
 *
 * That is the rule the section opens with and the single failure mode it exists to prevent: a
 * station that never saw an event publishes a snapshot without its effects, and a peer that assigns
 * from it erases work that was never in conflict. This function is a total function of
 * `(held, incoming)` and every field on the right-hand side goes through one of the combinators
 * below. **If a line here ever reads `x = snapshot.x`, it is a bug.**
 *
 * Convergence comes from each rule being a min-register, a set union, a lattice join, or a
 * precedence fixed in the specification — never a plain assignment, and never a comparison against
 * the receiver's own clock. So the result does not depend on arrival order, which is the property
 * that makes the rest of this safe.
 *
 * **There is deliberately no `event_count` guard**, and its removal is the point rather than an
 * oversight. This used to open with `if (snapshot.eventCount < current.eventCount) return state`,
 * on the argument that a lower count meant a stale snapshot. §7.2 and §7.4 now forbid exactly that:
 * the count is a scalar, so two sites that each applied a *different* twelve events both hold 12,
 * each discards the other as stale, and neither ever converges. It orders snapshots from one
 * publisher and nothing more. What made the guard survivable before was that it was hiding a merge
 * that *was* plain assignment; with the rules below there is nothing for it to protect against.
 */
fun applySnapshot(state: ChecklistState, snapshot: ProcedureSnapshot): ChecklistState {
    val held = state.progressFor(snapshot.procedureId)
    val incomingAt = snapshot.timestampEpochMillis
    val heldAt = held.lastSnapshotAtEpochMillis
    val newer = incomingAt != null && (heldAt == null || incomingAt >= heldAt)

    /** The catch-all rule: "the value from the snapshot with the later timestamp" — but never a blank. */
    fun pick(heldValue: String, incoming: String): String =
        if (newer) incoming.ifEmpty { heldValue } else heldValue.ifEmpty { incoming }

    return state.copy(
        progress = state.progress + (
            snapshot.procedureId to held.copy(
                runId = held.runId.ifEmpty { snapshot.runId },
                procedureId = held.procedureId.ifEmpty { snapshot.procedureId },
                items = (held.items.keys + snapshot.items.keys).associateWith { id ->
                    mergeItem(held.items[id] ?: ItemProgress(), snapshot.items[id] ?: ItemProgress())
                },
                // A hint now, not arbitration: `max` so a peer's lower count cannot make local state
                // look less advanced than it is. Nothing branches on it.
                eventCount = maxOf(held.eventCount, snapshot.eventCount),
                title = pick(held.title, snapshot.procedureTitle),
                status = joinRunStatus(held.status, snapshot.status),
                procedureVersion = pick(held.procedureVersion, snapshot.procedureVersion),
                // Earliest-wins rather than the catch-all. §7.2 does not name these, but a min-register
                // is order-independent and cannot lose a start, where the catch-all would let one
                // fast clock erase it.
                startedAtEpochMillis = earliestOf(held.startedAtEpochMillis, snapshot.startedAtEpochMillis),
                completedAtEpochMillis = earliestOf(held.completedAtEpochMillis, snapshot.completedAtEpochMillis),
                scheduledForEpochMillis = held.scheduledForEpochMillis ?: snapshot.scheduledForEpochMillis,
                abandonReason = pick(held.abandonReason, snapshot.abandonReason),
                // Never falls back to the publisher fields — see `ProcedureProgress.createdBy`.
                createdBy = pick(held.createdBy, snapshot.createdBy),
                createdBySite = pick(held.createdBySite, snapshot.createdBySite),
                createdAtEpochMillis = held.createdAtEpochMillis ?: snapshot.createdAtEpochMillis,
                // Once seen, kept — and `ChecklistCodec.encodeSnapshot` re-emits it, which is the
                // half §7.2 actually names. A merge-only implementation strips the archive on its
                // next publish.
                itemsSnapshot = held.itemsSnapshot.ifEmpty { snapshot.itemsSnapshot },
                lastSnapshotAtEpochMillis = maxOfNullable(heldAt, incomingAt),
            )
            ),
    )
}

/**
 * One item, merged by §7.2's per-field rules.
 *
 * The old implementation was `current.items + snapshot.items` — a whole-`ItemProgress` replace per
 * item id. A snapshot from a station that never saw a note this phone made replaced the item
 * outright and the note was gone; one from a station that never saw a completion moved a completed
 * item back. Both are the assignment §7.2 forbids, reached by way of a map operator.
 */
internal fun mergeItem(held: ItemProgress, incoming: ItemProgress): ItemProgress {
    // The completion triple moves together, so the instant and the author can never come from two
    // different peers. Earliest wins, comparing the two timestamps *as values* — never against this
    // receiver's clock, which is what makes the outcome independent of who arrived first.
    val completion = earlierCompletion(held, incoming)
    val start = earlierStart(held, incoming)
    return ItemProgress(
        // Monotone: Pending -> InProgress -> Completed. An item already completed is never moved
        // back by a snapshot; reopening is a deliberate act carried by an event, never an inference.
        status = if (held.status.ordinal >= incoming.status.ordinal) held.status else incoming.status,
        startedAtEpochMillis = start.startedAtEpochMillis,
        startedBy = start.startedBy,
        startedBySite = start.startedBySite,
        completedAtEpochMillis = completion.completedAtEpochMillis,
        completedBy = completion.completedBy,
        completedBySite = completion.completedBySite,
        // Union by note id. A station that never saw the note republishes an empty list, and a
        // replacing merge erases it everywhere at once. A re-sent copy is ignored: a note is
        // immutable once written.
        //
        // **Sorted, and that is part of the rule rather than a nicety.** A union is a set, and a set
        // has no order — so keeping arrival order made the merged *list* depend on which snapshot
        // landed first, and two operators looking at the same item saw its notes in different
        // orders. Ordering by the note's own instant makes the join genuinely commutative, and reads
        // chronologically, which is what a list of notes is for. The id breaks ties so the order is
        // total rather than merely stable.
        notes = (held.notes + incoming.notes.filterNot { n -> held.notes.any { it.noteId == n.noteId } })
            .sortedWith(compareBy({ it.atEpochMillis }, { it.noteId })),
        // Union by flag id, same argument and the same ordering, on the field that carries a hazard.
        flags = mergeFlags(held.flags, incoming.flags),
        // Union by evidence id. The empty-incoming case falls out for free.
        evidence = (
            held.evidence +
                incoming.evidence.filterNot { e -> held.evidence.any { it.evidenceId == e.evidenceId } }
            ).sortedWith(
            compareBy<ItemEvidence, Long?>(nullsLast()) { it.capturedAtEpochMillis }
                .thenBy { it.evidenceId },
        ),
        // The scalars, for as long as a publisher may send no list. `||` and `ifEmpty` rather than
        // assignment, so a legacy publisher sending `flagged = false` cannot clear a flag a
        // *different* legacy publisher raised. `withFlagCache()` then overrides all of it the moment
        // either side sends a list — which is the one-way migration, and its deliberate consequence
        // is that once one station is on `flags`, a legacy peer can no longer clear a flag at all.
        flagged = held.flagged || incoming.flagged,
        flagReason = held.flagReason.ifEmpty { incoming.flagReason },
        flaggedBy = held.flaggedBy.ifEmpty { incoming.flaggedBy },
        flaggedBySite = held.flaggedBySite.ifEmpty { incoming.flaggedBySite },
        flaggedAtEpochMillis = held.flaggedAtEpochMillis ?: incoming.flaggedAtEpochMillis,
    ).withFlagCache()
}

/**
 * Union by `flag_id`, with the resolution absorbing and settled earliest-wins.
 *
 * Commutative, which matters more than it looks: a `FLAG_RESOLVED` can arrive before the
 * `ITEM_FLAGGED` that raised it. The resolve creates a resolution-only flag and the later raise
 * fills the other half without disturbing it, so both orders end at the same flag.
 */
internal fun mergeFlags(held: List<ItemFlag>, incoming: List<ItemFlag>): List<ItemFlag> {
    val byId = LinkedHashMap<String, ItemFlag>()
    held.forEach { byId[it.flagId] = it }
    incoming.forEach { f -> byId[f.flagId] = byId[f.flagId]?.let { mergeFlag(it, f) } ?: f }
    // Ordered by when each was raised, for the reason the notes union is ordered: a set has no
    // order, so arrival order would otherwise leak into what two stations show for one item. A flag
    // whose raise has not arrived yet has no instant, and sorts last rather than first — it is the
    // half of a flag we know least about.
    return byId.values.sortedWith(
        compareBy<ItemFlag, Long?>(nullsLast()) { it.raisedAtEpochMillis }.thenBy { it.flagId },
    )
}

private fun mergeFlag(held: ItemFlag, incoming: ItemFlag): ItemFlag {
    // Absorbing falls out of the null checks: a peer that never saw the resolution sends null and
    // loses. Two real resolutions are settled by the earlier time as a value — the same min-register
    // as a completion, and for the same reason: deciding by whose snapshot landed last would let a
    // stale peer reopen a closed item. The triple moves together so the time, the author and the
    // resolution text can never come from two different peers.
    val resolution = when {
        held.resolvedAtEpochMillis == null -> incoming
        incoming.resolvedAtEpochMillis == null -> held
        incoming.resolvedAtEpochMillis < held.resolvedAtEpochMillis -> incoming
        else -> held
    }
    return ItemFlag(
        flagId = held.flagId,
        // The raise half is write-once, so absence never overwrites a value.
        reason = held.reason.ifEmpty { incoming.reason },
        raisedAtEpochMillis = held.raisedAtEpochMillis ?: incoming.raisedAtEpochMillis,
        raisedBy = held.raisedBy.ifEmpty { incoming.raisedBy },
        raisedBySite = held.raisedBySite.ifEmpty { incoming.raisedBySite },
        resolvedAtEpochMillis = resolution.resolvedAtEpochMillis,
        resolvedBy = resolution.resolvedBy,
        resolvedBySite = resolution.resolvedBySite,
        resolution = resolution.resolution,
    )
}

/**
 * A lattice join over run status: **terminal beats non-terminal regardless of timestamps**, and where
 * both sides are terminal, **`Abandoned` wins**.
 *
 * The order of the branches *is* the rule. No timestamp appears in it, deliberately: there is no
 * `abandoned_at` to compare against `completedAt`, and a snapshot's own timestamp is the publish
 * tick rather than the moment of the act, so a clock rule here would settle a human decision by
 * whichever station republished last.
 *
 * Why `Abandoned` rather than `Completed` is the side that stands: two sites genuinely can end one
 * run differently — a supervisor signs it off while an operator stops it — and since both are
 * absorbing, neither yields and the run never converges without a fixed precedence. `Abandoned`
 * under-claims. A completed run recorded as abandoned loses a sign-off, which is visible and
 * recoverable; a run somebody deliberately stopped recorded as complete asserts that a procedure was
 * carried out when it was not, which is neither. A safety record fails toward the smaller claim.
 */
internal fun joinRunStatus(held: RunStatus, incoming: RunStatus): RunStatus = when {
    held == RunStatus.Abandoned || incoming == RunStatus.Abandoned -> RunStatus.Abandoned
    held == RunStatus.Completed || incoming == RunStatus.Completed -> RunStatus.Completed
    incoming == RunStatus.Unknown -> held
    held == RunStatus.Unknown -> incoming
    held == RunStatus.Active || incoming == RunStatus.Active -> RunStatus.Active
    else -> incoming
}

/** Earliest wins, treating null as "no claim". A min-register over values, never over clocks. */
internal fun earliestOf(held: Long?, incoming: Long?): Long? = when {
    held == null -> incoming
    incoming == null -> held
    else -> minOf(held, incoming)
}

private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}

/** The whole completion triple, from whichever side completed earlier. Ties keep the held side. */
private fun earlierCompletion(held: ItemProgress, incoming: ItemProgress): ItemProgress = when {
    held.completedAtEpochMillis == null -> incoming
    incoming.completedAtEpochMillis == null -> held
    incoming.completedAtEpochMillis < held.completedAtEpochMillis -> incoming
    else -> held
}

private fun earlierStart(held: ItemProgress, incoming: ItemProgress): ItemProgress = when {
    held.startedAtEpochMillis == null -> incoming
    incoming.startedAtEpochMillis == null -> held
    incoming.startedAtEpochMillis < held.startedAtEpochMillis -> incoming
    else -> held
}

/**
 * The legacy `EVENT_TYPE_TIME_SET` payload: a UTC ISO-8601 instant in `detail`.
 *
 * Kept because recordings full of the string form must keep replaying. Total rather than throwing,
 * like every other decode on this path — a correction nobody can parse is dropped, not guessed.
 */
internal fun parseIsoInstantOrNull(text: String): Long? =
    runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
