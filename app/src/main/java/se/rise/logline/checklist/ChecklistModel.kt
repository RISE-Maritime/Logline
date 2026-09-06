package se.rise.logline.checklist

/**
 * The checklist domain, as the app holds it. Pure Kotlin: no Android, no protobuf, no Zenoh — the
 * protos are converted at the edge in [ChecklistCodec] and the reducer works on these.
 *
 * The split matters because of what the wire does *not* carry. [Procedure] is a definition and
 * arrives from the router's storage; [ItemProgress] is state and arrives as events. An event names an
 * item by id and nothing else, so without the definition a consumer knows something was completed but
 * not what it said.
 */

/** One line of a checklist, as written. */
data class ProcedureItem(
    val itemId: String,
    /** Position as presented, 1-based, and carried explicitly rather than implied by list order. */
    val number: Int,
    val title: String,
    val description: String = "",
    /** A procedure is not complete while a required item is outstanding. */
    val required: Boolean = true,
)

data class Procedure(
    val procedureId: String,
    val title: String,
    val description: String = "",
    val category: String = "",
    val version: String = "",
    val estimatedMinutes: Int = 0,
    val items: List<ProcedureItem> = emptyList(),
)

enum class ItemStatus { Pending, InProgress, Completed }

data class ItemNote(
    val noteId: String,
    val text: String,
    val atEpochMillis: Long,
    val author: String,
    val authorSite: String,
)

/**
 * One flag on an item, from the moment it was raised to the moment it was cleared.
 *
 * **A record, not a current value**, and that is the whole point of it. The five flag scalars on
 * [ItemProgress] say that an item *is* flagged, why, by whom and when — and say nothing at all once
 * it is not, so the raise-to-resolve cycle survived only in `checklist_event`, which has no router
 * storage. A station bootstrapping from a snapshot could not learn that an issue had ever been
 * raised on an item, who cleared it, or on what grounds.
 *
 * Modelled on [ItemNote] deliberately — id, text, time, author, site — because §7.2 already knows
 * how to merge that shape and a third shape would need a third rule.
 *
 * **[resolvedAtEpochMillis] null means the flag is OPEN.** A consumer must read absence as open
 * rather than as unknown: an unresolved safety condition read as "no information" is the wrong way
 * to fail.
 */
data class ItemFlag(
    /**
     * Globally unique and a single token, like a note id. It is the union key §7.2 merges on, and
     * the union is only well defined because this exists.
     */
    val flagId: String,
    /** Why it was raised. */
    val reason: String = "",
    val raisedAtEpochMillis: Long? = null,
    val raisedBy: String = "",
    val raisedBySite: String = "",
    /**
     * Unset while the flag is open. Once set it is **absorbing** (§7.2): a peer that never saw the
     * resolution must not withdraw it, and two genuine resolutions are settled by the earlier time
     * *as a value*, never by whose snapshot landed last.
     */
    val resolvedAtEpochMillis: Long? = null,
    val resolvedBy: String = "",
    val resolvedBySite: String = "",
    /**
     * How it was cleared — the counterpart to [reason], and the half that used to be unrecoverable.
     * Closing an open safety item is the part an auditor asks about.
     */
    val resolution: String = "",
) {
    val open: Boolean get() = resolvedAtEpochMillis == null
}

/** Where a piece of evidence came from. Mirrors `ChecklistItemEvidence.Source`. */
enum class EvidenceSource { Unknown, File, Camera, Chart }

/**
 * A photograph attached to an item — **metadata only**.
 *
 * The bytes live on their own key, `checklist_evidence/{evidenceId}`, and are fetched one at a time.
 * They are deliberately not inlined here: a snapshot is republished every 30 s per active run into a
 * durable store, and a photo in it would be megabytes on the wire twice a minute to restate a
 * picture that has not changed since it was taken.
 *
 * [width] and [height] are what let a consumer lay out a tile for bytes it has not fetched.
 */
data class ItemEvidence(
    val evidenceId: String,
    val caption: String = "",
    val capturedAtEpochMillis: Long? = null,
    val author: String = "",
    val authorSite: String = "",
    /** The full media type, e.g. `image/jpeg` — echoed into the image's own `format`. */
    val mediaType: String = "",
    val byteSize: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val source: EvidenceSource = EvidenceSource.Unknown,
)

/** What has happened to one item. Every "who" is a person at a site, because two sites share this. */
data class ItemProgress(
    val status: ItemStatus = ItemStatus.Pending,
    val startedAtEpochMillis: Long? = null,
    val startedBy: String = "",
    val startedBySite: String = "",
    val completedAtEpochMillis: Long? = null,
    val completedBy: String = "",
    val completedBySite: String = "",
    val notes: List<ItemNote> = emptyList(),
    /**
     * Every flag this item has carried, open and closed. **The truth; the four scalars below are a
     * cache of it.**
     *
     * Two things were wrong before this list existed, and it fixes both. The scalars had *no merge
     * rule* — §7.2 named one for completion, status, notes and evidence, and the flag fell through
     * to "the value from the later snapshot", which is plain assignment on the one field carrying a
     * hazard, so a peer that never saw the flag could clear it on its 30 s tick. And resolving
     * cleared all of them, so a closed flag left no trace anywhere durable.
     *
     * The migration direction is what keeps the cache honest, and it is one-way: a receiver holding
     * a non-empty list derives the scalars from it and ignores what a snapshot says they are; the
     * scalars are authoritative **only** from a publisher that sends no list. [withFlagCache] is the
     * one place that derivation happens, and every write to this list goes through it.
     */
    val flags: List<ItemFlag> = emptyList(),
    /** Photographs attached to this item, metadata only. Unioned by [ItemEvidence.evidenceId]. */
    val evidence: List<ItemEvidence> = emptyList(),
    val flagged: Boolean = false,
    val flagReason: String = "",
    val flaggedBy: String = "",
    val flaggedBySite: String = "",
    /**
     * When the flag was **raised** — not when the snapshot carrying it was published.
     *
     * The scalars above say an item is flagged, why and by whom, but never when, so a receiver
     * bootstrapping from a snapshot could not date the flag at all. Stamping it from the snapshot's
     * own timestamp would be worse than leaving it empty: it would assert a time that is simply
     * wrong. Unset is also what an older publisher sends, so absence means "not known", never "just
     * now".
     */
    val flaggedAtEpochMillis: Long? = null,
) {
    /** The flag a person still has to deal with, or null. At most one is open at a time in practice. */
    fun openFlag(): ItemFlag? = flags.firstOrNull { it.open }

    /**
     * Recompute the four scalars from [flags], per §7.2's "derived from `flags`, not merged".
     *
     * An **empty** list leaves them untouched, which is the other half of the same rule: with no
     * list they are what a legacy publisher sent and are the only thing there is to go on.
     */
    fun withFlagCache(): ItemProgress = if (flags.isEmpty()) this else openFlag().let { open ->
        copy(
            flagged = open != null,
            flagReason = open?.reason.orEmpty(),
            flaggedBy = open?.raisedBy.orEmpty(),
            flaggedBySite = open?.raisedBySite.orEmpty(),
            flaggedAtEpochMillis = open?.raisedAtEpochMillis,
        )
    }
}

/**
 * One procedure's progress.
 *
 * [eventCount] is the version used when a snapshot arrives: it counts the events a state has taken in,
 * so a snapshot carrying fewer than we have already applied is stale and must not be written over
 * newer local work. It is a coarse guard — two sites counting independently can agree by accident —
 * but it is the one crowsnest publishes, and a shared guard beats a better private one.
 */
data class ProcedureProgress(
    val items: Map<String, ItemProgress> = emptyMap(),
    val eventCount: Int = 0,
    /**
     * The run this progress belongs to — the last token of `checklist_state`'s key, and what the map
     * is keyed on.
     *
     * Empty from a record written before the run model, which [runIdOf] resolves to the procedure id
     * rather than dropping.
     */
    val runId: String = "",
    /** Carried rather than inferred: a run outlives this phone's copy of the template it came from. */
    val procedureId: String = "",
    /**
     * What the run calls itself and how it is going, taken from the last snapshot that carried them.
     *
     * **This is what lets a run render when its procedure is unknown to this phone.** The item *text*
     * lives in `checklist_procedure`, which is storage-only and therefore out of reach without a query
     * — but the run's own title travels in the snapshot, so the row has a name even when its items can
     * only be listed by id.
     */
    val title: String = "",
    val status: RunStatus = RunStatus.Unknown,
    val procedureVersion: String = "",
    val startedAtEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    val scheduledForEpochMillis: Long? = null,
    /**
     * Why a run was stopped without completing.
     *
     * It used to travel only in the announcing event's `detail`, and `checklist_event` has no router
     * storage — so a station bootstrapping from a snapshot saw ABANDONED with no reason and no way
     * to ever recover one. Empty is a legitimate value (a client that collected none) and must not
     * be read as "no reason given" versus "reason lost".
     */
    val abandonReason: String = "",
    /**
     * Who created this run and where — as opposed to who last republished the snapshot.
     *
     * The obvious substitute is wrong in a way that is hard to see: the publisher fields name
     * whoever ticked the 30 s timer, so authorship would flip to the last republisher on every
     * snapshot. Crowsnest did exactly that, and because it also decided *which runs it republishes*
     * from those fields, the publish duty silently migrated with the label. A wrong label is a
     * display bug; a wrong publisher is a convergence bug.
     *
     * Empty from a publisher that predates them, and that must render as unknown rather than
     * falling back to the publisher — naming the wrong operator on a safety record is worse than
     * naming none.
     */
    val createdBy: String = "",
    val createdBySite: String = "",
    /**
     * When the run was created, which is **not** when it started. A planned run has no start by
     * definition, so a board with only `startedAt` has to reach for the snapshot's publish tick, and
     * a run queued last watch then sorts as if it were created moments ago.
     */
    val createdAtEpochMillis: Long? = null,
    /**
     * The item text this run was worked against, written only on the terminal publish.
     *
     * §7.2: **once a receiver has seen it for a run it must re-emit it on every subsequent publish**,
     * or the next peer to republish the key strips the archive.
     */
    val itemsSnapshot: List<ProcedureItem> = emptyList(),
    /**
     * The `ChecklistState.timestamp` of the newest snapshot merged in — the tiebreak for the fields
     * §7.2 leaves to "the value from the snapshot with the later timestamp", and nothing else.
     * Never compared against this phone's own clock.
     */
    val lastSnapshotAtEpochMillis: Long? = null,
) {
    fun item(itemId: String): ItemProgress = items[itemId] ?: ItemProgress()

    /** Terminal statuses are absorbing (§7.2), which is what makes them safe to short-circuit on. */
    fun isTerminal(): Boolean = status == RunStatus.Completed || status == RunStatus.Abandoned

    fun completedCount(): Int = items.values.count { it.status == ItemStatus.Completed }

    /** Items with an issue still open. Reads the list where there is one, the cache where there is not. */
    fun flaggedCount(): Int = items.values.count { if (it.flags.isEmpty()) it.flagged else it.openFlag() != null }

    fun evidenceCount(): Int = items.values.sumOf { it.evidence.size }
}

/** What a timeline row says happened. Mirrors the event types worth showing a person. */
enum class TimelineKind {
    Started,
    Completed,
    Confirmed,
    /** A completed item taken back out of done. `ITEM_REVERTED` and `ITEM_REOPENED` are one act. */
    Reverted,
    NoteAdded,
    Flagged,
    FlagResolved,
    EvidenceAttached,
    /** A timestamp corrected after the fact, recorded as its own kind so it can be audited. */
    TimeCorrected,
    RunPlanned,
    RunAbandoned,
    ProcedureStarted,
    ProcedureCompleted,
}

data class TimelineEntry(
    val eventId: String,
    val atEpochMillis: Long,
    val kind: TimelineKind,
    val procedureId: String,
    val itemId: String,
    /** Resolved against the procedure definition when known, and the raw id when it is not. */
    val itemTitle: String,
    val operatorName: String,
    val rocSite: String,
    val note: String = "",
)

/** Who this phone is on the bus. Distinct from the logger's entity id, which names the hardware. */
data class Operator(
    val operatorId: String,
    val username: String,
    val role: String,
    val rocSite: String,
)

enum class CursorState { Idle, Viewing, EditingNote }

/** Another operator's last heartbeat. [seenAtEpochMillis] is *arrival*, which is what staleness means. */
data class RemotePresence(
    val operatorId: String,
    val username: String,
    val role: String,
    val rocSite: String,
    val activeProcedureId: String,
    val activeItemId: String,
    val cursor: CursorState,
    val seenAtEpochMillis: Long,
    /** Which run they are looking at — a procedure id cannot say, once one has several runs. */
    val activeRunId: String = "",
    /** Every run they have open, not only the focused one. Empty from a publisher predating it. */
    val openRunIds: List<String> = emptyList(),
)
