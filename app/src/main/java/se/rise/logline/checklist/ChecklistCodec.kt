package se.rise.logline.checklist

import com.google.protobuf.Timestamp
import core.EnvelopeOuterClass.Envelope
import keelson.ChecklistEventOuterClass.ChecklistEvent
import keelson.ChecklistEvidence.ChecklistItemEvidence
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
            .setRunId(event.runId)
        event.evidence?.let { message.evidence = it.toProto() }
        if (event.type == ChecklistEventType.TimeSet) {
            event.correctedTimeEpochMillis?.let {
                val corrected = Instant.ofEpochMilli(it)
                message.correctedTime = protoTimestamp(corrected)
                message.correctedField = event.correctedField.toProto()
                // And the legacy string, which upstream asks publishers to keep writing until every
                // consumer reads the typed pair. It costs a few bytes on an event nobody sends often,
                // and a consumer that only knows the old shape still applies the correction.
                message.detail = corrected.toString()
            }
        }
        return enclose(message.build().toByteArray(), at)
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
            .setPublishedBySite(operator.rocSite)
            .setEventCount(progress.eventCount)
            .addAllItems(progress.items.map { (itemId, item) -> item.toProto(itemId) })
            .setRunId(progress.runId)
            .setStatus(progress.status.toProto())
            .setProcedureTitle(progress.title)
            .setProcedureVersion(progress.procedureVersion)
            .setAbandonReason(progress.abandonReason)
            .setCreatedBy(progress.createdBy)
            .setCreatedBySite(progress.createdBySite)
            // §7.2: once a receiver has seen the archive for a run it must re-emit it on every
            // subsequent publish, or the next peer to republish the key strips it. This line is the
            // half of that rule a merge-only implementation forgets.
            .addAllItemsSnapshot(progress.itemsSnapshot.map { it.toProto() })
        progress.startedAtEpochMillis?.let { message.startedAt = protoTimestamp(Instant.ofEpochMilli(it)) }
        progress.completedAtEpochMillis?.let { message.completedAt = protoTimestamp(Instant.ofEpochMilli(it)) }
        progress.scheduledForEpochMillis?.let { message.scheduledFor = protoTimestamp(Instant.ofEpochMilli(it)) }
        progress.createdAtEpochMillis?.let { message.createdAt = protoTimestamp(Instant.ofEpochMilli(it)) }
        return enclose(message.build().toByteArray(), now)
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
            .addAllItems(procedure.items.map { it.toProto() })
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
            runId = m.runId,
            evidence = if (m.hasEvidence()) m.evidence.toModel() else null,
            correctedTimeEpochMillis = m.correctedTime.epochMillisOrNull(),
            correctedField = m.correctedField.toModel(),
        )
    }

    fun decodeSnapshot(envelopeBytes: ByteArray): ProcedureSnapshot? = decode(envelopeBytes) { payload ->
        val m = ChecklistStateMessage.parseFrom(payload)
        ProcedureSnapshot(
            procedureId = m.procedureId,
            eventCount = m.eventCount,
            items = m.itemsList.associate { it.itemId to it.toModel() },
            // Empty from a publisher that predates the run model, which is the same fallback
            // crowsnest's own `runIdFor` makes for this app's events.
            runId = runIdOf(m.runId, m.procedureId),
            procedureTitle = m.procedureTitle,
            status = m.status.toModel(),
            procedureVersion = m.procedureVersion,
            timestampEpochMillis = m.timestamp.epochMillisOrNull(),
            startedAtEpochMillis = m.startedAt.epochMillisOrNull(),
            completedAtEpochMillis = m.completedAt.epochMillisOrNull(),
            scheduledForEpochMillis = m.scheduledFor.epochMillisOrNull(),
            abandonReason = m.abandonReason,
            createdBy = m.createdBy,
            createdBySite = m.createdBySite,
            createdAtEpochMillis = m.createdAt.epochMillisOrNull(),
            itemsSnapshot = m.itemsSnapshotList.map { it.toModel() },
        )
    }

    private fun ChecklistStateMessage.RunStatus.toModel(): RunStatus = when (this) {
        ChecklistStateMessage.RunStatus.RUN_STATUS_PLANNED -> RunStatus.Planned
        ChecklistStateMessage.RunStatus.RUN_STATUS_ACTIVE -> RunStatus.Active
        ChecklistStateMessage.RunStatus.RUN_STATUS_COMPLETED -> RunStatus.Completed
        ChecklistStateMessage.RunStatus.RUN_STATUS_ABANDONED -> RunStatus.Abandoned
        // Includes UNRECOGNIZED: a status this build does not know is not a status to guess at.
        else -> RunStatus.Unknown
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
            items = m.itemsList.map { it.toModel() }.sortedBy { it.number },
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
        .setStartedBySite(startedBySite)
        .setCompletedBy(completedBy)
        .setCompletedBySite(completedBySite)
        .setFlagged(flagged)
        .setFlagReason(flagReason)
        .setFlaggedBy(flaggedBy)
        .setFlaggedBySite(flaggedBySite)
        .addAllNotes(
            notes.map {
                ChecklistStateMessage.ItemNote.newBuilder()
                    .setNoteId(it.noteId)
                    .setText(it.text)
                    .setCreatedAt(protoTimestamp(Instant.ofEpochMilli(it.atEpochMillis)))
                    .setAuthor(it.author)
                    .setAuthorSite(it.authorSite)
                    .build()
            }
        )
        // Both representations, which is what upstream asks for during the migration: `flags` is the
        // truth and the four scalars above are its cache, and a publisher writes both until the
        // release that stops writing them. They cannot disagree, because every write to `flags` goes
        // through `withFlagCache()` before it reaches here.
        .addAllFlags(flags.map { it.toProto() })
        .addAllEvidence(evidence.map { it.toProto() })
    // Left unset rather than stamped with zero: proto3 cannot tell an absent timestamp from the
    // epoch, and "completed at 1970" is worse than "completed, time unknown".
    startedAtEpochMillis?.let { builder.startedAt = protoTimestamp(Instant.ofEpochMilli(it)) }
    completedAtEpochMillis?.let { builder.completedAt = protoTimestamp(Instant.ofEpochMilli(it)) }
    // When the flag was RAISED, not when this snapshot was published. Stamping it from the publish
    // tick would be worse than leaving it empty: it would assert a time that is simply wrong.
    flaggedAtEpochMillis?.let { builder.flaggedAt = protoTimestamp(Instant.ofEpochMilli(it)) }
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
    startedBySite = startedBySite,
    completedAtEpochMillis = completedAt.epochMillisOrNull(),
    completedBy = completedBy,
    completedBySite = completedBySite,
    notes = notesList.map {
        ItemNote(it.noteId, it.text, it.createdAt.epochMillis(), it.author, it.authorSite)
    },
    flags = flagsList.map { it.toModel() },
    evidence = evidenceList.map { it.toModel() },
    flagged = flagged,
    flagReason = flagReason,
    flaggedBy = flaggedBy,
    flaggedBySite = flaggedBySite,
    flaggedAtEpochMillis = flaggedAt.epochMillisOrNull(),
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
    ChecklistEventType.RunPlanned -> ChecklistEvent.EventType.EVENT_TYPE_RUN_PLANNED
    ChecklistEventType.RunAbandoned -> ChecklistEvent.EventType.EVENT_TYPE_RUN_ABANDONED
    ChecklistEventType.EvidenceAttached -> ChecklistEvent.EventType.EVENT_TYPE_EVIDENCE_ATTACHED
    ChecklistEventType.TimeSet -> ChecklistEvent.EventType.EVENT_TYPE_TIME_SET
    ChecklistEventType.ItemReopened -> ChecklistEvent.EventType.EVENT_TYPE_ITEM_REOPENED
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
    ChecklistEvent.EventType.EVENT_TYPE_RUN_PLANNED -> ChecklistEventType.RunPlanned
    ChecklistEvent.EventType.EVENT_TYPE_RUN_ABANDONED -> ChecklistEventType.RunAbandoned
    ChecklistEvent.EventType.EVENT_TYPE_EVIDENCE_ATTACHED -> ChecklistEventType.EvidenceAttached
    ChecklistEvent.EventType.EVENT_TYPE_TIME_SET -> ChecklistEventType.TimeSet
    // 4 and 15 are the same act, and both must keep decoding — see `ChecklistEventType.ItemReverted`.
    ChecklistEvent.EventType.EVENT_TYPE_ITEM_REOPENED -> ChecklistEventType.ItemReopened
    // Includes the reserved 14, which is claimed for evidence retraction and not implemented
    // anywhere: events are append-only, so a retraction needs design rather than a number.
    else -> ChecklistEventType.Unknown
}

private fun TimeField.toProto(): ChecklistEvent.TimeField = when (this) {
    TimeField.ItemStartedAt -> ChecklistEvent.TimeField.TIME_FIELD_ITEM_STARTED_AT
    TimeField.ItemCompletedAt -> ChecklistEvent.TimeField.TIME_FIELD_ITEM_COMPLETED_AT
    TimeField.RunStartedAt -> ChecklistEvent.TimeField.TIME_FIELD_RUN_STARTED_AT
    TimeField.Unknown -> ChecklistEvent.TimeField.TIME_FIELD_UNKNOWN
}

private fun ChecklistEvent.TimeField.toModel(): TimeField = when (this) {
    ChecklistEvent.TimeField.TIME_FIELD_ITEM_STARTED_AT -> TimeField.ItemStartedAt
    ChecklistEvent.TimeField.TIME_FIELD_ITEM_COMPLETED_AT -> TimeField.ItemCompletedAt
    ChecklistEvent.TimeField.TIME_FIELD_RUN_STARTED_AT -> TimeField.RunStartedAt
    else -> TimeField.Unknown
}

private fun EvidenceSource.toProto(): ChecklistItemEvidence.Source = when (this) {
    EvidenceSource.File -> ChecklistItemEvidence.Source.SOURCE_FILE
    EvidenceSource.Camera -> ChecklistItemEvidence.Source.SOURCE_CAMERA
    EvidenceSource.Chart -> ChecklistItemEvidence.Source.SOURCE_CHART
    EvidenceSource.Unknown -> ChecklistItemEvidence.Source.SOURCE_UNKNOWN
}

private fun ChecklistItemEvidence.Source.toModel(): EvidenceSource = when (this) {
    ChecklistItemEvidence.Source.SOURCE_FILE -> EvidenceSource.File
    ChecklistItemEvidence.Source.SOURCE_CAMERA -> EvidenceSource.Camera
    ChecklistItemEvidence.Source.SOURCE_CHART -> EvidenceSource.Chart
    else -> EvidenceSource.Unknown
}

private fun ItemEvidence.toProto(): ChecklistItemEvidence {
    val builder = ChecklistItemEvidence.newBuilder()
        .setEvidenceId(evidenceId)
        .setCaption(caption)
        .setAuthor(author)
        .setAuthorSite(authorSite)
        .setMediaType(mediaType)
        .setByteSize(byteSize)
        .setWidth(width)
        .setHeight(height)
        .setSource(source.toProto())
    capturedAtEpochMillis?.let { builder.capturedAt = protoTimestamp(Instant.ofEpochMilli(it)) }
    return builder.build()
}

private fun ChecklistItemEvidence.toModel(): ItemEvidence = ItemEvidence(
    evidenceId = evidenceId,
    caption = caption,
    capturedAtEpochMillis = capturedAt.epochMillisOrNull(),
    author = author,
    authorSite = authorSite,
    mediaType = mediaType,
    byteSize = byteSize,
    width = width,
    height = height,
    source = source.toModel(),
)

private fun ItemFlag.toProto(): ChecklistStateMessage.ItemFlag {
    val builder = ChecklistStateMessage.ItemFlag.newBuilder()
        .setFlagId(flagId)
        .setReason(reason)
        .setRaisedBy(raisedBy)
        .setRaisedBySite(raisedBySite)
        .setResolvedBy(resolvedBy)
        .setResolvedBySite(resolvedBySite)
        .setResolution(resolution)
    raisedAtEpochMillis?.let { builder.raisedAt = protoTimestamp(Instant.ofEpochMilli(it)) }
    // Left unset while the flag is open, which is what "open" *is* on the wire — a consumer reads
    // absence as open, never as unknown.
    resolvedAtEpochMillis?.let { builder.resolvedAt = protoTimestamp(Instant.ofEpochMilli(it)) }
    return builder.build()
}

private fun ChecklistStateMessage.ItemFlag.toModel(): ItemFlag = ItemFlag(
    flagId = flagId,
    reason = reason,
    raisedAtEpochMillis = raisedAt.epochMillisOrNull(),
    raisedBy = raisedBy,
    raisedBySite = raisedBySite,
    resolvedAtEpochMillis = resolvedAt.epochMillisOrNull(),
    resolvedBy = resolvedBy,
    resolvedBySite = resolvedBySite,
    resolution = resolution,
)

/**
 * One procedure item, shared by the definition and by `items_snapshot`.
 *
 * The same shape travels on two subjects for different reasons — `checklist_procedure` carries the
 * living template, `ChecklistState.items_snapshot` the archive of what a finished run was actually
 * worked against — and one converter is what stops the two drifting.
 */
private fun ProcedureItem.toProto(): ChecklistProcedure.Item = ChecklistProcedure.Item.newBuilder()
    .setItemId(itemId)
    .setNumber(number)
    .setTitle(title)
    .setDescription(description)
    .setIsRequired(required)
    .build()

private fun ChecklistProcedure.Item.toModel(): ProcedureItem =
    ProcedureItem(itemId, number, title, description, isRequired)

private fun RunStatus.toProto(): ChecklistStateMessage.RunStatus = when (this) {
    RunStatus.Planned -> ChecklistStateMessage.RunStatus.RUN_STATUS_PLANNED
    RunStatus.Active -> ChecklistStateMessage.RunStatus.RUN_STATUS_ACTIVE
    RunStatus.Completed -> ChecklistStateMessage.RunStatus.RUN_STATUS_COMPLETED
    RunStatus.Abandoned -> ChecklistStateMessage.RunStatus.RUN_STATUS_ABANDONED
    RunStatus.Unknown -> ChecklistStateMessage.RunStatus.RUN_STATUS_UNKNOWN
}

private fun CursorState.toProto(): ChecklistPresence.CursorState = when (this) {
    CursorState.Idle -> ChecklistPresence.CursorState.CURSOR_STATE_IDLE
    CursorState.Viewing -> ChecklistPresence.CursorState.CURSOR_STATE_VIEWING
    CursorState.EditingNote -> ChecklistPresence.CursorState.CURSOR_STATE_EDITING_NOTE
}

private fun ChecklistPresence.CursorState.toModel(): CursorState = when (this) {
    ChecklistPresence.CursorState.CURSOR_STATE_VIEWING -> CursorState.Viewing
    ChecklistPresence.CursorState.CURSOR_STATE_EDITING_NOTE -> CursorState.EditingNote
    else -> CursorState.Idle
}
