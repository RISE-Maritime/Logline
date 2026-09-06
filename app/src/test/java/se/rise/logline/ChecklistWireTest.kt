package se.rise.logline

import core.EnvelopeOuterClass.Envelope
import se.rise.logline.checklist.ChecklistCodec
import se.rise.logline.checklist.ChecklistEventRecord
import se.rise.logline.checklist.ChecklistEventType
import se.rise.logline.checklist.CursorState
import se.rise.logline.checklist.EvidenceSource
import se.rise.logline.checklist.ItemEvidence
import se.rise.logline.checklist.ItemFlag
import se.rise.logline.checklist.ItemNote
import se.rise.logline.checklist.ItemProgress
import se.rise.logline.checklist.ItemStatus
import se.rise.logline.checklist.Operator
import se.rise.logline.checklist.Procedure
import se.rise.logline.checklist.ProcedureItem
import se.rise.logline.checklist.ProcedureProgress
import se.rise.logline.checklist.RunStatus
import se.rise.logline.checklist.TimeField
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The wire format, pinned against bytes produced by the *other* implementation.
 *
 * `keelson.Checklist*` has no upstream test to lean on, and the protos it is built from were
 * reconstructed from crowsnest's generated encoders — so the golden payloads below were produced by
 * running those encoders (`keelson/sdks/js/dist/payloads/Checklist*.js`) over known values. If a field
 * number ever moves, this fails; without it the symptom is a message that decodes cleanly into the
 * wrong fields, which nothing else would catch.
 *
 * The bytes are the **payload**, not the envelope: everything on this bus is wrapped, so each check
 * unwraps first — which also pins that the app wraps at all.
 */
class ChecklistWireTest {

    private val at = Instant.ofEpochMilli(1_755_000_000_000L)

    private val event = ChecklistEventRecord(
        eventId = "evt_1",
        atEpochMillis = at.toEpochMilli(),
        type = ChecklistEventType.ItemCompleted,
        operatorId = "op-7",
        username = "Ted",
        role = "master",
        rocSite = "ROC-B",
        procedureId = "proc_001",
        itemId = "item_003",
        detail = "looks fine",
        referenceId = "note_9",
    )

    @Test
    fun `an event encodes to the bytes crowsnest produces`() {
        assertArrayEquals(GOLDEN_EVENT, ChecklistCodec.encodeEvent(event).payload())
    }

    @Test
    fun `an event crowsnest encoded decodes field for field`() {
        val decoded = ChecklistCodec.decodeEvent(wrap(GOLDEN_EVENT))

        assertEquals(event, decoded)
    }

    /**
     * **Do not "improve" these fixtures by filling in the fields the run model added.**
     *
     * `encodeSnapshot` now writes `run_id`, `status`, `created_by`, `items_snapshot` and the rest,
     * and this assertion still passes only because proto3 omits empty strings, zero enums and empty
     * repeated fields — so a fixture left at its defaults encodes to exactly the bytes it did before
     * any of them existed. That is what makes the golden a live check rather than a historical one.
     *
     * Setting `runId = "run_1"` here to make the fixture look realistic changes the encoding, and
     * the tempting fix — regenerating the constant — would re-pin it to *this* app's output and
     * destroy the only oracle in this file produced by the other implementation. Round trips for the
     * new fields belong below and in `ChecklistRunFieldsTest`, not here.
     */
    @Test
    fun `a snapshot encodes to the bytes crowsnest produces`() {
        val progress = ProcedureProgress(
            eventCount = 42,
            items = mapOf(
                "item_003" to ItemProgress(
                    status = ItemStatus.Completed,
                    completedAtEpochMillis = 1_755_000_000_500L,
                    completedBy = "Ted",
                    completedBySite = "ROC-B",
                    notes = listOf(
                        ItemNote("note_9", "hi", 1_755_000_000_400L, "Ted", "ROC-B"),
                    ),
                    flagged = true,
                    flagReason = "why",
                    flaggedBy = "Ted",
                    flaggedBySite = "ROC-B",
                )
            ),
        )
        val bytes = ChecklistCodec.encodeSnapshot(
            procedureId = "proc_001",
            progress = progress,
            operator = Operator("op-7", "Ted", "master", "ROC-B"),
            now = Instant.ofEpochMilli(1_755_000_001_000L),
        )

        assertArrayEquals(GOLDEN_STATE, bytes.payload())
    }

    @Test
    fun `a snapshot crowsnest encoded decodes field for field`() {
        val snapshot = ChecklistCodec.decodeSnapshot(wrap(GOLDEN_STATE))!!

        assertEquals("proc_001", snapshot.procedureId)
        assertEquals(42, snapshot.eventCount)
        val item = snapshot.items.getValue("item_003")
        assertEquals(ItemStatus.Completed, item.status)
        assertEquals(1_755_000_000_500L, item.completedAtEpochMillis)
        assertEquals("ROC-B", item.completedBySite)
        assertEquals(listOf("note_9"), item.notes.map { it.noteId })
        assertTrue(item.flagged)
        assertEquals("why", item.flagReason)
    }

    /**
     * proto3 cannot tell an absent timestamp from the epoch, so an untouched item must not come back
     * claiming it was started in 1970 — the reason [ItemProgress] carries nullable instants.
     */
    @Test
    fun `an item that was never started decodes with no start time`() {
        val bytes = ChecklistCodec.encodeSnapshot(
            procedureId = "proc_001",
            progress = ProcedureProgress(items = mapOf("item_001" to ItemProgress())),
            operator = Operator("op-7", "Ted", "", "ROC-B"),
            now = at,
        )

        val item = ChecklistCodec.decodeSnapshot(bytes)!!.items.getValue("item_001")
        assertNull(item.startedAtEpochMillis)
        assertNull(item.completedAtEpochMillis)
    }

    @Test
    fun `presence encodes to the bytes crowsnest produces`() {
        val bytes = ChecklistCodec.encodePresence(
            operator = Operator("op-7", "Ted", "master", "ROC-B"),
            activeProcedureId = "proc_001",
            activeItemId = "item_003",
            cursor = CursorState.EditingNote,
            now = Instant.ofEpochMilli(1_755_000_002_000L),
        )

        assertArrayEquals(GOLDEN_PRESENCE, bytes.payload())
    }

    @Test
    fun `presence crowsnest encoded decodes field for field`() {
        val seen = ChecklistCodec.decodePresence(wrap(GOLDEN_PRESENCE), seenAtEpochMillis = 99)!!

        assertEquals("op-7", seen.operatorId)
        assertEquals("Ted", seen.username)
        assertEquals("ROC-B", seen.rocSite)
        assertEquals("proc_001", seen.activeProcedureId)
        assertEquals(CursorState.EditingNote, seen.cursor)
        // Arrival, not the payload's own stamp: staleness means "we have not heard from them".
        assertEquals(99L, seen.seenAtEpochMillis)
    }

    /** No golden bytes for this one — `ChecklistProcedure` is new, so a round trip is the pin. */
    @Test
    fun `a procedure round-trips`() {
        val procedure = Procedure(
            procedureId = "proc_001",
            title = "Open Sea Navigation Checks",
            description = "Pre-departure verification",
            category = "navigation",
            version = "2.4.1",
            estimatedMinutes = 15,
            items = listOf(
                ProcedureItem("item_001", 1, "Verify route plan", "Check ECDIS", true),
                ProcedureItem("item_002", 2, "Test VHF radio", "Channel 16", false),
            ),
        )

        assertEquals(procedure, ChecklistCodec.decodeProcedure(ChecklistCodec.encodeProcedure(procedure, "op-7")))
    }

    /** Items come back in `number` order however the storage happened to hold them. */
    @Test
    fun `procedure items are ordered by their number`() {
        val procedure = Procedure(
            procedureId = "proc_001",
            title = "Out of order",
            items = listOf(
                ProcedureItem("item_c", 3, "third"),
                ProcedureItem("item_a", 1, "first"),
                ProcedureItem("item_b", 2, "second"),
            ),
        )

        val decoded = ChecklistCodec.decodeProcedure(ChecklistCodec.encodeProcedure(procedure, ""))!!
        assertEquals(listOf("item_a", "item_b", "item_c"), decoded.items.map { it.itemId })
    }

    /**
     * The run model's own fields, round-tripped rather than pinned against golden bytes — there are
     * none for them, since crowsnest's encoders were captured before they existed.
     */
    @Test
    fun `an event round-trips the run id and a time correction`() {
        val corrected = ChecklistEventRecord(
            eventId = "evt_2",
            atEpochMillis = at.toEpochMilli(),
            type = ChecklistEventType.TimeSet,
            operatorId = "op-7",
            username = "Ted",
            role = "master",
            rocSite = "ROC-B",
            procedureId = "proc_001",
            itemId = "item_003",
            runId = "run_mt2zqy4l",
            correctedTimeEpochMillis = 1_754_000_000_000L,
            correctedField = TimeField.ItemCompletedAt,
        )

        val decoded = ChecklistCodec.decodeEvent(ChecklistCodec.encodeEvent(corrected))!!
        assertEquals("run_mt2zqy4l", decoded.runId)
        assertEquals(1_754_000_000_000L, decoded.correctedTimeEpochMillis)
        assertEquals(TimeField.ItemCompletedAt, decoded.correctedField)
    }

    /**
     * A `TIME_SET` writes the typed pair **and** the legacy ISO-8601 string, which is what upstream
     * asks publishers to do until every consumer reads the typed pair. A consumer that only knows
     * the old shape still applies the correction.
     */
    @Test
    fun `a time correction also writes the legacy string a older consumer reads`() {
        val bytes = ChecklistCodec.encodeEvent(
            ChecklistEventRecord(
                eventId = "evt_3",
                atEpochMillis = at.toEpochMilli(),
                type = ChecklistEventType.TimeSet,
                operatorId = "op-7",
                username = "Ted",
                role = "",
                rocSite = "ROC-B",
                procedureId = "proc_001",
                itemId = "item_003",
                correctedTimeEpochMillis = 1_754_000_000_000L,
                correctedField = TimeField.ItemStartedAt,
            ),
        )

        assertEquals(
            Instant.ofEpochMilli(1_754_000_000_000L).toString(),
            ChecklistCodec.decodeEvent(bytes)!!.detail,
        )
    }

    /** An event carrying evidence metadata — the bytes travel on their own key, this does not. */
    @Test
    fun `an event round-trips evidence metadata`() {
        val photo = ItemEvidence(
            evidenceId = "ev_1",
            caption = "quay side",
            capturedAtEpochMillis = 1_755_000_000_100L,
            author = "Ted",
            authorSite = "ROC-B",
            mediaType = "image/jpeg",
            byteSize = 118_204,
            width = 1280,
            height = 964,
            source = EvidenceSource.Camera,
        )

        val decoded = ChecklistCodec.decodeEvent(
            ChecklistCodec.encodeEvent(
                ChecklistEventRecord(
                    eventId = "evt_4",
                    atEpochMillis = at.toEpochMilli(),
                    type = ChecklistEventType.EvidenceAttached,
                    operatorId = "op-7",
                    username = "Ted",
                    role = "",
                    rocSite = "ROC-B",
                    procedureId = "proc_001",
                    itemId = "item_003",
                    evidence = photo,
                ),
            ),
        )!!

        assertEquals(photo, decoded.evidence)
    }

    /**
     * A snapshot carries **both** flag representations, and they agree.
     *
     * The list is the truth and the scalars are its cache; a publisher writes both until the release
     * that stops writing the scalars, because consumers — including this app until now, and
     * crowsnest today — still read `flagged`.
     */
    @Test
    fun `a snapshot writes the flag list and the scalar cache, agreeing`() {
        val progress = ProcedureProgress(
            runId = "run_1",
            items = mapOf(
                "item_003" to ItemProgress(
                    flags = listOf(
                        ItemFlag("flag_1", reason = "cracked", raisedAtEpochMillis = 1_000, raisedBy = "Ted"),
                    ),
                ).withFlagCache(),
            ),
        )

        val item = ChecklistCodec.decodeSnapshot(
            ChecklistCodec.encodeSnapshot("proc_001", progress, Operator("op-7", "Ted", "", "ROC-B"), at),
        )!!.items.getValue("item_003")

        assertEquals("cracked", item.flags.single().reason)
        assertTrue(item.flagged)
        assertEquals("cracked", item.flagReason)
        assertEquals(1_000L, item.flaggedAtEpochMillis)
    }

    /**
     * The archive is re-emitted on publish, which is the half of §7.2's `items_snapshot` rule a
     * merge-only implementation forgets — and forgetting it means the next peer to republish the key
     * strips the wording a finished run was worked against.
     */
    @Test
    fun `a snapshot re-emits the run's item archive`() {
        val archive = listOf(ProcedureItem("item_003", 1, "Verify route plan", "Check ECDIS", true))
        val progress = ProcedureProgress(runId = "run_1", itemsSnapshot = archive)

        val decoded = ChecklistCodec.decodeSnapshot(
            ChecklistCodec.encodeSnapshot("proc_001", progress, Operator("op-7", "Ted", "", "ROC-B"), at),
        )!!

        assertEquals(archive, decoded.itemsSnapshot)
    }

    /** Presence round-trips the run fields, and its own golden bytes still match without them. */
    @Test
    fun `a snapshot round-trips the run's authorship and reason`() {
        val progress = ProcedureProgress(
            runId = "run_1",
            status = RunStatus.Abandoned,
            abandonReason = "fog",
            createdBy = "Ana",
            createdBySite = "ROC-C",
            createdAtEpochMillis = 1_754_000_000_000L,
            scheduledForEpochMillis = 1_754_500_000_000L,
        )

        val decoded = ChecklistCodec.decodeSnapshot(
            ChecklistCodec.encodeSnapshot("proc_001", progress, Operator("op-7", "Ted", "", "ROC-B"), at),
        )!!

        assertEquals(RunStatus.Abandoned, decoded.status)
        assertEquals("fog", decoded.abandonReason)
        // Deliberately NOT the publisher, who is op-7 at ROC-B.
        assertEquals("Ana", decoded.createdBy)
        assertEquals("ROC-C", decoded.createdBySite)
        assertEquals(1_754_000_000_000L, decoded.createdAtEpochMillis)
        assertEquals(1_754_500_000_000L, decoded.scheduledForEpochMillis)
        assertEquals(at.toEpochMilli(), decoded.timestampEpochMillis)
    }

    /**
     * A shared bus carries other people's software. One message this build cannot read must cost one
     * message, not the screen.
     */
    @Test
    fun `garbage decodes to null rather than throwing`() {
        assertNull(ChecklistCodec.decodeEvent(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0x07)))
        assertNull(ChecklistCodec.decodeSnapshot(byteArrayOf(0x08, 0x96.toByte(), 0x01, 0xff.toByte())))
    }

    /** A raw payload put on the key without the envelope is a bug; this pins that we never do it. */
    @Test
    fun `every message goes out wrapped in an envelope`() {
        val envelope = Envelope.parseFrom(ChecklistCodec.encodeEvent(event))

        assertEquals(at.epochSecond, envelope.enclosedAt.seconds)
        assertTrue(envelope.payload.size() > 0)
    }

    private fun ByteArray.payload(): ByteArray = Envelope.parseFrom(this).payload.toByteArray()

    private fun wrap(payload: ByteArray): ByteArray =
        se.rise.logline.keelson.enclose(payload, at)

    private companion object {

        /**
         * Produced by crowsnest's own bindings, which are the reference implementation:
         *
         * ```
         * node -e 'const {ChecklistEvent} = require("./dist/payloads/ChecklistEvent.js"); …'
         * ```
         *
         * run in `keelson/sdks/js`.
         */
        val GOLDEN_EVENT = hex(
            "0a0608c0d9ecc40612056576745f31180322046f702d372a0354656432066d6173746572" +
                "3a05524f432d42420870726f635f3030314a086974656d5f303033520a6c6f6f6b732066" +
                "696e655a066e6f74655f39"
        )

        val GOLDEN_STATE = hex(
            "0a0608c1d9ecc406120870726f635f3030311a046f702d372205524f432d42282a32610a" +
                "086974656d5f3030331002320c08c0d9ecc4061080cab5ee013a035465644205524f432d" +
                "424a260a066e6f74655f39120268691a0c08c0d9ecc406108088debe0122035465642a05" +
                "524f432d4250015a0377687962035465646a05524f432d42"
        )

        val GOLDEN_PRESENCE = hex(
            "0a0608c2d9ecc40612046f702d371a0354656422066d61737465722a05524f432d423208" +
                "70726f635f3030313a086974656d5f3030334002"
        )

        fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
