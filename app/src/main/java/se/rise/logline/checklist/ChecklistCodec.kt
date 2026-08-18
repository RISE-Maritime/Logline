package se.rise.logline.checklist

import com.google.protobuf.Timestamp
import core.EnvelopeOuterClass.Envelope
import keelson.ChecklistEventOuterClass.ChecklistEvent
import keelson.ChecklistPresenceOuterClass.ChecklistPresence
import keelson.ChecklistProcedureOuterClass.ChecklistProcedure
import keelson.ChecklistStateOuterClass.ChecklistState as ChecklistStateMessage
import se.rise.logline.keelson.enclose
import se.rise.logline.keelson.protoTimestamp
import java.time.Instant

/**
 * The one place protobuf meets the checklist model.
 *
 * Everything on the wire is wrapped — payload → [enclose] → `core.Envelope` → `put` — so every decode
 * here starts by unwrapping. A checklist message read straight off the key without that step parses
 * as garbage rather than failing, which is the sort of bug that only shows up as fields that are
 * quietly empty.
 *
 * Decoding is written to be **total**: a malformed or unknown payload comes back as `null` rather than
 * throwing. This is a subscriber on a shared bus with at least one other implementation on it, and one
 * bad message must not take the screen down.
 */
object ChecklistCodec {

    // ── outgoing ────────────────────────────────────────────────────────────────────────────────

    fun encodeEvent(event: ChecklistEventRecord): ByteArray {
        val at = Instant.ofEpochMilli(event.atEpochMillis)
        val message = ChecklistEvent.newBuilder()
            .setTimestamp(protoTimestamp(at))
            .setEventId(event.eventId)
            .setEventType(event.type.toProto())
            .setOperatorId(event.operatorId)
            .setUsername(event.username)
            .setRole(event.role)
            .setRocSite(event.rocSite)
            .setProcedureId(event.procedureId)
            .setItemId(event.itemId)
            .setDetail(event.detail)
            .setReferenceId(event.referenceId)
            .build()
        return enclose(message.toByteArray(), at)
    }

    fun encodeSnapshot(
        procedureId: String,
        progress: ProcedureProgress,
        operator: Operator,
        now: Instant = Instant.now(),
    ): ByteArray {
        val message = ChecklistStateMessage.newBuilder()
            .setTimestamp(protoTimestamp(now))
            .setProcedureId(procedureId)
            .setPublishedByOperator(operator.operatorId)
            .setPublishedByRocSite(operator.rocSite)
            .setEventCount(progress.eventCount)
            .addAllItems(progress.items.map { (itemId, item) -> item.toProto(itemId) })
            .build()
        return enclose(message.toByteArray(), now)
    }

    fun encodePresence(
        operator: Operator,
        activeProcedureId: String,
        activeItemId: String,
        cursor: CursorState,
        now: Instant = Instant.now(),
    ): ByteArray {
        val message = ChecklistPresence.newBuilder()
            .setTimestamp(protoTimestamp(now))
            .setOperatorId(operator.operatorId)
            .setUsername(operator.username)
            .setRole(operator.role)
            .setRocSite(operator.rocSite)
            .setActiveProcedureId(activeProcedureId)
            .setActiveItemId(activeItemId)
            .setCursor(cursor.toProto())
            .build()
        return enclose(message.toByteArray(), now)
    }

    fun encodeProcedure(procedure: Procedure, updatedBy: String, now: Instant = Instant.now()): ByteArray {
        val message = ChecklistProcedure.newBuilder()
            .setProcedureId(procedure.procedureId)
            .setTitle(procedure.title)
            .setDescription(procedure.description)
            .setCategory(procedure.category)
            .setVersion(procedure.version)
            .setEstimatedMinutes(procedure.estimatedMinutes)
            .addAllItems(
                procedure.items.map { item ->
                    ChecklistProcedure.Item.newBuilder()
                        .setItemId(item.itemId)
                        .setNumber(item.number)
                        .setTitle(item.title)
                        .setDescription(item.description)
                        .setIsRequired(item.required)
                        .build()
                }
            )
            .setUpdatedAt(protoTimestamp(now))
            .setUpdatedBy(updatedBy)
            .build()
        return enclose(message.toByteArray(), now)
    }

    // ── incoming ────────────────────────────────────────────────────────────────────────────────

    fun decodeEvent(envelopeBytes: ByteArray): ChecklistEventRecord? = decode(envelopeBytes) { payload ->
        val m = ChecklistEvent.parseFrom(payload)
        ChecklistEventRecord(
            eventId = m.eventId,
            atEpochMillis = m.timestamp.epochMillis(),
            type = m.eventType.toModel(),
            operatorId = m.operatorId,
            username = m.username,
            role = m.role,
            rocSite = m.rocSite,
            procedureId = m.procedureId,
            itemId = m.itemId,
            detail = m.detail,
            referenceId = m.referenceId,
        )
    }

    fun decodeSnapshot(envelopeBytes: ByteArray): ProcedureSnapshot? = decode(envelopeBytes) { payload ->
        val m = ChecklistStateMessage.parseFrom(payload)
        ProcedureSnapshot(
            procedureId = m.procedureId,
            eventCount = m.eventCount,
            items = m.itemsList.associate { it.itemId to it.toModel() },
        )
    }

    fun decodePresence(envelopeBytes: ByteArray, seenAtEpochMillis: Long): RemotePresence? =
        decode(envelopeBytes) { payload ->
            val m = ChecklistPresence.parseFrom(payload)
            RemotePresence(
                operatorId = m.operatorId,
                username = m.username,
                role = m.role,
                rocSite = m.rocSite,
                activeProcedureId = m.activeProcedureId,
                activeItemId = m.activeItemId,
                cursor = m.cursor.toModel(),
                // Arrival, not the stamp in the payload: staleness is "we have not heard from them",
                // and a heartbeat from a phone whose clock is off must not read as stale on arrival.
                seenAtEpochMillis = seenAtEpochMillis,
            )
        }

    fun decodeProcedure(envelopeBytes: ByteArray): Procedure? = decode(envelopeBytes) { payload ->
        val m = ChecklistProcedure.parseFrom(payload)
        Procedure(
            procedureId = m.procedureId,
            title = m.title,
            description = m.description,
            category = m.category,
            version = m.version,
            estimatedMinutes = m.estimatedMinutes,
            items = m.itemsList
                .map { ProcedureItem(it.itemId, it.number, it.title, it.description, it.isRequired) }
                .sortedBy { it.number },
        )
    }

    private inline fun <T> decode(envelopeBytes: ByteArray, body: (ByteArray) -> T): T? = try {
        body(Envelope.parseFrom(envelopeBytes).payload.toByteArray())
    } catch (t: Throwable) {
        // Includes InvalidProtocolBufferException, and the IndexOutOfBounds a truncated frame can
        // produce. A neighbour publishing something else on this key is a reason to skip a message,
        // never a reason to stop reading the bus.
        null
    }
}

/** Zero seconds and zero nanos is protobuf's "absent", which for a completion time means null. */
private fun Timestamp.epochMillisOrNull(): Long? =
    if (seconds == 0L && nanos == 0) null else epochMillis()

private fun Timestamp.epochMillis(): Long = seconds * 1_000L + nanos / 1_000_000L

private fun ItemProgress.toProto(itemId: String): ChecklistStateMessage.ItemState {
    val builder = ChecklistStateMessage.ItemState.newBuilder()
        .setItemId(itemId)
        .setStatus(
            when (status) {
                ItemStatus.Pending -> ChecklistStateMessage.ItemState.ItemStatus.ITEM_STATUS_PENDING
                ItemStatus.InProgress -> ChecklistStateMessage.ItemState.ItemStatus.ITEM_STATUS_IN_PROGRESS
                ItemStatus.Completed -> ChecklistStateMessage.ItemState.ItemStatus.ITEM_STATUS_COMPLETED
            }
        )
        .setStartedBy(startedBy)
        .setStartedByRocSite(startedByRocSite)
        .setCompletedBy(completedBy)
        .setCompletedByRocSite(completedByRocSite)
        .setFlagged(flagged)
        .setFlagReason(flagReason)
        .setFlaggedBy(flaggedBy)
        .setFlaggedByRocSite(flaggedByRocSite)
        .addAllNotes(
            notes.map {
                ChecklistStateMessage.ItemNote.newBuilder()
                    .setNoteId(it.noteId)
                    .setText(it.text)
                    .setCreatedAt(protoTimestamp(Instant.ofEpochMilli(it.atEpochMillis)))
                    .setAuthor(it.author)
                    .setAuthorRocSite(it.authorRocSite)
                    .build()
            }
        )
    // Left unset rather than stamped with zero: proto3 cannot tell an absent timestamp from the
    // epoch, and "completed at 1970" is worse than "completed, time unknown".
    startedAtEpochMillis?.let { builder.startedAt = protoTimestamp(Instant.ofEpochMilli(it)) }
    completedAtEpochMillis?.let { builder.completedAt = protoTimestamp(Instant.ofEpochMilli(it)) }
    return builder.build()
}

private fun ChecklistStateMessage.ItemState.toModel(): ItemProgress = ItemProgress(
    status = when (status) {
        ChecklistStateMessage.ItemState.ItemStatus.ITEM_STATUS_IN_PROGRESS -> ItemStatus.InProgress
        ChecklistStateMessage.ItemState.ItemStatus.ITEM_STATUS_COMPLETED -> ItemStatus.Completed
        else -> ItemStatus.Pending
    },
    startedAtEpochMillis = startedAt.epochMillisOrNull(),
    startedBy = startedBy,
    startedByRocSite = startedByRocSite,
    completedAtEpochMillis = completedAt.epochMillisOrNull(),
    completedBy = completedBy,
    completedByRocSite = completedByRocSite,
    notes = notesList.map {
        ItemNote(it.noteId, it.text, it.createdAt.epochMillis(), it.author, it.authorRocSite)
    },
    flagged = flagged,
    flagReason = flagReason,
    flaggedBy = flaggedBy,
    flaggedByRocSite = flaggedByRocSite,
)

private fun ChecklistEventType.toProto(): ChecklistEvent.EventType = when (this) {
    ChecklistEventType.Unknown -> ChecklistEvent.EventType.EVENT_TYPE_UNKNOWN
    ChecklistEventType.ChecklistOpened -> ChecklistEvent.EventType.EVENT_TYPE_CHECKLIST_OPENED
    ChecklistEventType.ItemStarted -> ChecklistEvent.EventType.EVENT_TYPE_ITEM_STARTED
    ChecklistEventType.ItemCompleted -> ChecklistEvent.EventType.EVENT_TYPE_ITEM_COMPLETED
    ChecklistEventType.ItemReverted -> ChecklistEvent.EventType.EVENT_TYPE_ITEM_REVERTED
    ChecklistEventType.NoteAdded -> ChecklistEvent.EventType.EVENT_TYPE_NOTE_ADDED
    ChecklistEventType.ItemFlagged -> ChecklistEvent.EventType.EVENT_TYPE_ITEM_FLAGGED
    ChecklistEventType.FlagResolved -> ChecklistEvent.EventType.EVENT_TYPE_FLAG_RESOLVED
    ChecklistEventType.ProcedureStarted -> ChecklistEvent.EventType.EVENT_TYPE_PROCEDURE_STARTED
    ChecklistEventType.ProcedureCompleted -> ChecklistEvent.EventType.EVENT_TYPE_PROCEDURE_COMPLETED
}

/**
 * An event type this build does not know about decodes as [ChecklistEventType.Unknown] and is applied
 * as a no-op, rather than throwing. `UNRECOGNIZED` is what protobuf-lite hands back for a value added
 * upstream after this app was built, and the bus is shared with software that ships on its own clock.
 */
private fun ChecklistEvent.EventType.toModel(): ChecklistEventType = when (this) {
    ChecklistEvent.EventType.EVENT_TYPE_CHECKLIST_OPENED -> ChecklistEventType.ChecklistOpened
    ChecklistEvent.EventType.EVENT_TYPE_ITEM_STARTED -> ChecklistEventType.ItemStarted
    ChecklistEvent.EventType.EVENT_TYPE_ITEM_COMPLETED -> ChecklistEventType.ItemCompleted
    ChecklistEvent.EventType.EVENT_TYPE_ITEM_REVERTED -> ChecklistEventType.ItemReverted
    ChecklistEvent.EventType.EVENT_TYPE_NOTE_ADDED -> ChecklistEventType.NoteAdded
    ChecklistEvent.EventType.EVENT_TYPE_ITEM_FLAGGED -> ChecklistEventType.ItemFlagged
    ChecklistEvent.EventType.EVENT_TYPE_FLAG_RESOLVED -> ChecklistEventType.FlagResolved
    ChecklistEvent.EventType.EVENT_TYPE_PROCEDURE_STARTED -> ChecklistEventType.ProcedureStarted
    ChecklistEvent.EventType.EVENT_TYPE_PROCEDURE_COMPLETED -> ChecklistEventType.ProcedureCompleted
    else -> ChecklistEventType.Unknown
}

private fun CursorState.toProto(): ChecklistPresence.CursorState = when (this) {
    CursorState.Idle -> ChecklistPresence.CursorState.CURSOR_IDLE
    CursorState.Viewing -> ChecklistPresence.CursorState.CURSOR_VIEWING
    CursorState.EditingNote -> ChecklistPresence.CursorState.CURSOR_EDITING_NOTE
}

private fun ChecklistPresence.CursorState.toModel(): CursorState = when (this) {
    ChecklistPresence.CursorState.CURSOR_VIEWING -> CursorState.Viewing
    ChecklistPresence.CursorState.CURSOR_EDITING_NOTE -> CursorState.EditingNote
    else -> CursorState.Idle
}
