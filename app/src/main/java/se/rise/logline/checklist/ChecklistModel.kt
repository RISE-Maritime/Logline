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
    val flagged: Boolean = false,
    val flagReason: String = "",
    val flaggedBy: String = "",
    val flaggedBySite: String = "",
)

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
     * What the run calls itself and how it is going, taken from the last snapshot that carried them.
     *
     * **This is what lets a run render when its procedure is unknown to this phone.** The item *text*
     * lives in `checklist_procedure`, which is storage-only and therefore out of reach without a query
     * — but the run's own title travels in the snapshot, so the row has a name even when its items can
     * only be listed by id.
     */
    val title: String = "",
    val status: RunStatus = RunStatus.Unknown,
) {
    fun item(itemId: String): ItemProgress = items[itemId] ?: ItemProgress()

    fun completedCount(): Int = items.values.count { it.status == ItemStatus.Completed }

    fun flaggedCount(): Int = items.values.count { it.flagged }
}

/** What a timeline row says happened. Mirrors the event types worth showing a person. */
enum class TimelineKind { Started, Completed, Confirmed, Reverted, NoteAdded, Flagged, FlagResolved, ProcedureStarted, ProcedureCompleted }

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
)
