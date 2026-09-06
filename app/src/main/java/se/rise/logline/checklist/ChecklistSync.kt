package se.rise.logline.checklist

import android.content.Context
import android.util.Log
import io.zenoh.pubsub.AdvancedPublisher
import io.zenoh.pubsub.Subscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.rise.logline.config.TlsCredentialStore
import se.rise.logline.keelson.KeelsonSession
import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.qosForSubject
import java.util.UUID

/** Everything the sync needs that comes from settings. */
data class ChecklistConfig(
    val endpoints: List<String>,
    val realm: String,
    val entityId: String,
    val operator: Operator,
)

/**
 * The phone as a checklist peer: subscribes to what every site does, publishes what this one does.
 *
 * **It owns its own [KeelsonSession], separate from `SensorPublisher`'s.** A checklist has to work
 * with logging stopped — most of the time somebody is working through a pre-departure list, nothing is
 * being recorded — and the publisher's session lives and dies with a run. Two sessions in one process
 * are fine; `initZenohLogOnce()` already guards the one thing that may only happen once.
 *
 * Actions apply **locally first and publish second**. The person tapping is entitled to see the tick
 * immediately, and whether it reached a router is a separate question — one the link state answers
 * honestly, rather than by leaving the tap ambiguous. When the link is down the event is queued and
 * replayed on reconnect, so an offline stretch does not silently swallow a completed procedure.
 *
 * Nothing here holds a `Context` beyond the application one, and nothing here is created by Compose:
 * the session outlives any screen, so [LoglineApp][se.rise.logline.LoglineApp] owns it.
 */
class ChecklistSync(private val appContext: Context, private val repository: ChecklistRepository) {

    private val store = ChecklistStore()
    val state: StateFlow<ChecklistUiState> get() = store.state

    /** This phone's own copy of the photographs it has attached. See [ChecklistEvidenceStore]. */
    val evidenceStore = ChecklistEvidenceStore(appContext)

    private var scope: CoroutineScope? = null
    private var session: KeelsonSession? = null
    private var keys: ChecklistKeys? = null
    private var operator: Operator? = null

    private var eventPublisher: AdvancedPublisher? = null
    private var presencePublisher: AdvancedPublisher? = null
    private var subscribers: List<Subscriber<Unit>> = emptyList()

    /**
     * Events that have not reached a router.
     *
     * Bounded, because an unbounded one on a phone that has been in a tunnel for an hour is a memory
     * leak wearing a useful hat. A checklist produces events at the rate a person can press buttons,
     * so the cap is generous by construction — and if it is ever hit, the *oldest* go, because the
     * newest state is the one the other sites need most.
     */
    private val pending = ArrayDeque<ChecklistEventRecord>()

    /** Which procedure the person is looking at, for presence and for what to snapshot. */
    @Volatile
    private var activeProcedureId: String = ""

    @Volatile
    private var activeItemId: String = ""

    /**
     * The run the person is looking at, and every run they have open.
     *
     * Distinct facts, and presence carries both: `active_run_id` cannot identify a focus once two
     * runs of one procedure can be open at once, and a single scalar makes every other open run
     * invisible to the rest of the fleet.
     *
     * Note what upstream warns against doing instead — do **not** solve this by extending the
     * presence source id to `{roc_site}/{operator_id}/{run_id}`. Every consumer keys presence by
     * operator, so the extra keys overwrite each other and the heartbeat stops meaning what it says.
     */
    @Volatile
    private var activeRunId: String = ""

    @Volatile
    private var openRunIds: Set<String> = emptySet()

    /** Outlives a session, because tearing one down cannot be hosted by the scope being cancelled. */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Load whatever the phone already knows, without needing a network. Safe to call repeatedly. */
    fun loadLocal() {
        closeScope.launch {
            val stored = repository.stored.first()
            store.mergeProcedures(stored.procedures)
            // Through the snapshot path, not a straight assignment: this runs again whenever the
            // Activity is recreated, and by then the store may hold events newer than the last write
            // to disk. `applySnapshot`'s version guard is exactly the rule for that.
            stored.progress.forEach { (procedureId, progress) ->
                store.apply(ProcedureSnapshot(procedureId, progress.eventCount, progress.items))
            }
            store.setReminders(stored.reminders)
            if (activeProcedureId.isEmpty()) activeProcedureId = stored.activeProcedureId
        }
    }

    /**
     * Open a session and start syncing. Idempotent — a second call while running does nothing, so a
     * screen may call it on every entry without tracking whether it is already up.
     */
    fun start(config: ChecklistConfig) {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        operator = config.operator
        val checklistKeys = ChecklistKeys(config.realm, config.entityId)
        keys = checklistKeys
        store.setLink(ChecklistLink.Opening)

        newScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    KeelsonSession.openClient(
                        config.endpoints,
                        TlsCredentialStore(appContext).paths(),
                    )
                }
                session = opened
                eventPublisher = opened.declarePublisher(
                    checklistKeys.event(config.operator.rocSite),
                    qosForSubject(Subjects.CHECKLIST_EVENT),
                )
                presencePublisher = opened.declarePublisher(
                    checklistKeys.presence(config.operator.rocSite, config.operator.operatorId),
                    qosForSubject(Subjects.CHECKLIST_PRESENCE),
                )

                subscribeEvents(opened, checklistKeys)
                subscribePresence(opened, checklistKeys)
                subscribeState(opened, checklistKeys)
                subscribeProcedures(opened, checklistKeys)
                // **Nothing here issues a Zenoh query, and that is the design rather than an
                // omission.** A query's *reply* aborts the process — see `CHECKLISTS_AVAILABLE` — so
                // the bootstrap `get` this used to run is gone and everything arrives by
                // subscription: crowsnest republishes `checklist_state` periodically, and the item
                // text comes from this phone's own store, which `ChecklistRepository` persists and
                // `STARTER_PROCEDURES` seeds with crowsnest's own ids.
                //
                // The cost is a procedure this phone has never held and nobody republishes while it
                // listens: that run renders by item id, and the screen says so.
                store.setBootstrapped(true)

                launch { watchConnection(opened) }
                launch { heartbeat() }
                launch { snapshotRuns() }
                // The only signal that this worked. Everything else in this class logs a *failure*,
                // so a session that opened correctly used to be indistinguishable from one that was
                // never asked for — which is why the route-scoping this hangs off went unverified on
                // a device for as long as it did. Same shape as `SensorPublisher`'s session logging.
                Log.i(TAG, "session open on ${config.realm}/${config.entityId}")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // A missing TLS credential and an unparseable endpoint both land here, and both are
                // setup failures the person can act on — so the reason is kept, not just the state.
                Log.w(TAG, "checklist sync failed to start", t)
                store.setLink(ChecklistLink.Failed, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Fire-and-forget, like `SensorPublisher.stop()`: the fields are cleared and the state flipped
     * synchronously, and the session is closed off the cancelled scope. Local state stays — closing
     * the link is not the same as forgetting what was ticked.
     */
    fun stop() {
        val runScope = scope ?: return
        val open = session
        val openSubscribers = subscribers
        scope = null
        session = null
        eventPublisher = null
        presencePublisher = null
        subscribers = emptyList()
        store.setLink(ChecklistLink.Off)
        store.setBootstrapped(false)
        runScope.cancel()
        closeScope.launch {
            runCatching { openSubscribers.forEach { it.close() } }
            runCatching { open?.close() }
            // *After* the close, not beside the call: this is fire-and-forget on a scope that outlives
            // the cancelled one, so logging at call time would report a teardown that had not happened
            // yet — the same ordering trap `Recorder.stop()` documents.
            Log.i(TAG, "session closed")
        }
    }

    // ── what a person does ──────────────────────────────────────────────────────────────────────

    /**
     * Every one of these takes an optional `runId`, defaulting to empty.
     *
     * Empty is not a placeholder — [runIdOf] resolves it to the procedure id, which is exactly what
     * a publisher predating the run model sends and exactly where this phone's own records already
     * live. So a caller that still names only a procedure keeps behaving as it did, and a run-shaped
     * caller gets a run, without two families of method to keep in step.
     */
    fun openProcedure(procedureId: String, runId: String = "") {
        activeProcedureId = procedureId
        activeRunId = runIdOf(runId, procedureId)
        openRunIds = openRunIds + activeRunId
        closeScope.launch { repository.setActiveProcedure(procedureId) }
        emit(ChecklistEventType.ChecklistOpened, procedureId, itemId = "", runId = runId)
    }

    fun focusItem(itemId: String) {
        activeItemId = itemId
    }

    fun startItem(procedureId: String, itemId: String, runId: String = "") =
        emit(ChecklistEventType.ItemStarted, procedureId, itemId, runId = runId)

    fun completeItem(procedureId: String, itemId: String, runId: String = "") =
        emit(ChecklistEventType.ItemCompleted, procedureId, itemId, runId = runId)

    /**
     * Take an item back out of "done", with the reason that makes it auditable.
     *
     * Publishes [ChecklistEventType.ItemReopened] (15) rather than the older `ITEM_REVERTED` (4).
     * They are the same act and both still decode; 15 is the spelling upstream asks new publishers
     * to use, because "reverted" reads as undoing a mistake where reopening is a deliberate,
     * reasoned act performed on a record that already stands.
     */
    fun revertItem(procedureId: String, itemId: String, reason: String = "", runId: String = "") =
        emit(REOPEN_EVENT_TYPE, procedureId, itemId, detail = reason, runId = runId)

    fun addNote(procedureId: String, itemId: String, text: String, runId: String = "") =
        emit(
            ChecklistEventType.NoteAdded,
            procedureId,
            itemId,
            detail = text,
            referenceId = checklistId("note"),
            runId = runId,
        )

    /**
     * Raise an issue on an item.
     *
     * The `reference_id` **is** the flag id, and it is generated here rather than left empty. That is
     * the fix for a bug this app shipped: an empty reference means a receiver can only append, so a
     * later resolve opened a second flag instead of closing the first, and the item reported itself
     * flagged the instant somebody cleared it.
     */
    fun flagItem(procedureId: String, itemId: String, reason: String, runId: String = "") =
        emit(
            ChecklistEventType.ItemFlagged,
            procedureId,
            itemId,
            detail = reason,
            referenceId = checklistId("flag"),
            runId = runId,
        )

    /**
     * Clear an issue, naming the flag it clears.
     *
     * The id is looked up rather than passed in, because every caller has an item in hand and not a
     * flag. A resolve with nothing open still publishes — the receiving side falls back to the one
     * open flag, and a peer may hold one this phone has not seen.
     */
    fun resolveFlag(procedureId: String, itemId: String, resolution: String, runId: String = "") {
        val run = runIdOf(runId, procedureId)
        val flagId = store.state.value.state.run(run).item(itemId).openFlag()?.flagId.orEmpty()
        emit(
            ChecklistEventType.FlagResolved,
            procedureId,
            itemId,
            detail = resolution,
            referenceId = flagId,
            runId = runId,
        )
    }

    fun completeProcedure(procedureId: String, runId: String = "") =
        emit(ChecklistEventType.ProcedureCompleted, procedureId, itemId = "", runId = runId)

    /** Queue a run for later. */
    fun planRun(procedureId: String, runId: String = "") =
        emit(ChecklistEventType.RunPlanned, procedureId, itemId = "", runId = runId)

    /** Move a run to active. */
    fun startRun(procedureId: String, runId: String = "") =
        emit(ChecklistEventType.ProcedureStarted, procedureId, itemId = "", runId = runId)

    /**
     * Stop a run without completing it. **The reason is required**, and it is required because
     * `checklist_event` has no router storage: without it in the snapshot, a station bootstrapping
     * later sees ABANDONED with no reason and no way to ever recover one.
     */
    fun abandonRun(procedureId: String, reason: String, runId: String = "") =
        emit(ChecklistEventType.RunAbandoned, procedureId, itemId = "", detail = reason, runId = runId)

    /**
     * Correct a timestamp after the fact.
     *
     * Its own event type rather than a backdated completion, because completion resolves
     * earliest-wins: a completion moved *later* hits that guard on every station, logs a "confirmed"
     * row and changes nothing. Publishes the typed pair and the legacy string both — see
     * `ChecklistCodec.encodeEvent`.
     */
    fun correctTime(
        procedureId: String,
        itemId: String,
        field: TimeField,
        atEpochMillis: Long,
        runId: String = "",
    ) = emit(
        ChecklistEventType.TimeSet,
        procedureId,
        itemId,
        runId = runId,
        correctedTimeEpochMillis = atEpochMillis,
        correctedField = field,
    )

    /**
     * Attach a photograph to an item.
     *
     * **Bytes first, then the event**, and that ordering is the point: the event advertises a key,
     * so publishing it before the bytes have been attempted would have every station render a tile
     * for a photo that was never sent. It is not a guarantee — see below — but it is the difference
     * between a failure that could happen and one that is built in.
     *
     * The bytes go to `checklist_evidence/{evidence_id}` as an enveloped `foxglove.CompressedImage`,
     * one key per photo and immutable once written; the *metadata* rides on the event and, from
     * then on, inside `ItemState.evidence` in every snapshot. They are deliberately not one message:
     * a snapshot is republished every 30 s per active run into a durable store, and a photo in it
     * would be megabytes on the wire twice a minute to restate a picture nobody has changed.
     *
     * Note `format` carries the **full media type** — `image/jpeg`, not the camera path's `"jpeg"` —
     * because upstream requires it echoed there so a blob recovered on its own is self-describing.
     * The two encoders in this app differ on that on purpose.
     *
     * **A lost evidence publish is unsolved, and this cannot detect one.** Every QoS profile is
     * `DROP`, so a publish shed on a full egress queue is gone with no ack to notice it by and
     * nothing ever republishes it — while the snapshot happily goes on rendering a tile for bytes
     * that never landed. §7.4 records that as an accepted gap rather than a covered case, and the
     * UI must not imply otherwise.
     */
    fun attachEvidence(
        procedureId: String,
        itemId: String,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        caption: String = "",
        source: EvidenceSource = EvidenceSource.File,
        runId: String = "",
    ) {
        val who = operator ?: return
        val evidenceId = checklistId("ev")
        val now = System.currentTimeMillis()
        val metadata = ItemEvidence(
            evidenceId = evidenceId,
            caption = caption,
            capturedAtEpochMillis = now,
            author = who.username,
            authorSite = who.rocSite,
            mediaType = "image/jpeg",
            byteSize = jpeg.size,
            width = width,
            height = height,
            source = source,
        )
        // Kept locally whatever the bus does, so the person who took it can still see it.
        evidenceStore.save(evidenceId, jpeg)

        val open = session
        val checklistKeys = keys
        val runScope = scope
        if (open != null && checklistKeys != null && runScope != null) {
            runScope.launch {
                open.put(
                    checklistKeys.evidence(evidenceId),
                    encodeEvidenceImage(jpeg, now),
                    qosForSubject(Subjects.CHECKLIST_EVIDENCE),
                )
                emit(
                    ChecklistEventType.EvidenceAttached,
                    procedureId,
                    itemId,
                    referenceId = evidenceId,
                    runId = runId,
                    evidence = metadata,
                )
            }
        } else {
            // No session: the event queues like any other and the bytes are on disk. The photo is
            // not lost, but its key was never written — see the note above about what nothing here
            // can detect.
            emit(
                ChecklistEventType.EvidenceAttached,
                procedureId,
                itemId,
                referenceId = evidenceId,
                runId = runId,
                evidence = metadata,
            )
        }
    }

    fun setReminders(reminders: List<ChecklistReminder>) {
        store.setReminders(reminders)
        closeScope.launch { repository.saveReminders(reminders) }
    }

    /**
     * Publish the starter library so this bus has one.
     *
     * Only reachable when the query came back empty — see [STARTER_PROCEDURES] for why that state
     * exists at all. Publishing is all it takes: the key is storage-backed, so the router keeps the
     * last value and every later client reads it without this phone being present.
     */
    fun publishStarterProcedures() {
        val open = session ?: return
        val checklistKeys = keys ?: return
        val who = operator ?: return
        val runScope = scope ?: return
        runScope.launch {
            STARTER_PROCEDURES.forEach { procedure ->
                open.put(
                    checklistKeys.procedure(procedure.procedureId),
                    ChecklistCodec.encodeProcedure(procedure, who.operatorId),
                    qosForSubject(Subjects.CHECKLIST_PROCEDURE),
                )
            }
            store.mergeProcedures(STARTER_PROCEDURES)
            repository.saveProcedures(STARTER_PROCEDURES)
        }
    }

    // ── the machinery ───────────────────────────────────────────────────────────────────────────

    /**
     * Build the event, apply it here, then try to put it on the bus.
     *
     * The instant is taken synchronously, before any dispatch: the tap is the observation, and
     * stamping it whenever `Dispatchers.Default` got round to the coroutine would put two operators'
     * events in the wrong order under exactly the conflict rule that depends on their order.
     */
    private fun emit(
        type: ChecklistEventType,
        procedureId: String,
        itemId: String,
        detail: String = "",
        referenceId: String = "",
        runId: String = "",
        evidence: ItemEvidence? = null,
        correctedTimeEpochMillis: Long? = null,
        correctedField: TimeField = TimeField.Unknown,
    ) {
        val who = operator ?: return
        val event = ChecklistEventRecord(
            eventId = UUID.randomUUID().toString(),
            atEpochMillis = System.currentTimeMillis(),
            type = type,
            operatorId = who.operatorId,
            username = who.username,
            role = who.role,
            rocSite = who.rocSite,
            procedureId = procedureId,
            itemId = itemId,
            detail = detail,
            referenceId = referenceId,
            runId = runId,
            evidence = evidence,
            correctedTimeEpochMillis = correctedTimeEpochMillis,
            correctedField = correctedField,
        )
        store.apply(event)
        persistProgress()

        val publisher = eventPublisher
        val open = session
        val runScope = scope
        if (publisher == null || open == null || runScope == null) {
            queue(event)
            return
        }
        runScope.launch {
            val sent = open.publish(publisher, ChecklistCodec.encodeEvent(event)).isSuccess
            // A put that *fails* is the only unambiguous signal available: a put on a session whose
            // router has gone still succeeds and the sample lands nowhere. That is what the queue is
            // for on the reconnect side, and why this alone is not enough.
            if (!sent) queue(event)
        }
    }

    private fun queue(event: ChecklistEventRecord) = synchronized(pending) {
        pending.addLast(event)
        while (pending.size > PENDING_LIMIT) pending.removeFirst()
    }

    private fun persistProgress() {
        val who = operator ?: return
        closeScope.launch {
            runCatching { repository.saveProgress(store.state.value.state.progress, who) }
        }
    }

    private fun subscribeEvents(open: KeelsonSession, checklistKeys: ChecklistKeys) {
        val runScope = scope ?: return
        // The Zenoh callback runs on Zenoh's own thread: it hands the bytes over and returns. All the
        // decoding and every touch of the store happens on this coroutine.
        val inbox = Channel<ByteArray>(Channel.BUFFERED)
        val subscriber = open.declareSubscriber(checklistKeys.eventSubscription()) { _, payload ->
            inbox.trySend(payload)
        }
        subscribers = subscribers + subscriber
        runScope.launch {
            for (payload in inbox) {
                val event = ChecklistCodec.decodeEvent(payload) ?: continue
                // Own events are not skipped by sender id, they are skipped by *event id* in the
                // reducer. Skipping by sender would also skip this phone's own events replayed out of
                // the pending queue, which is the one case where re-applying is harmless and
                // filtering is not.
                store.apply(event)
                persistProgress()
            }
        }
    }

    private fun subscribePresence(open: KeelsonSession, checklistKeys: ChecklistKeys) {
        val runScope = scope ?: return
        val who = operator
        val inbox = Channel<ByteArray>(Channel.BUFFERED)
        val subscriber = open.declareSubscriber(checklistKeys.presenceSubscription()) { _, payload ->
            inbox.trySend(payload)
        }
        subscribers = subscribers + subscriber
        runScope.launch {
            for (payload in inbox) {
                val now = System.currentTimeMillis()
                val seen = ChecklistCodec.decodePresence(payload, now) ?: continue
                // This phone's own heartbeat comes back on the wildcard subscription; showing
                // yourself in the list of other operators is just confusing.
                if (seen.operatorId == who?.operatorId) continue
                store.presence(seen, now - PRESENCE_STALE_MILLIS)
            }
        }
    }

    /**
     * Run snapshots, from every site — the half that replaces the bootstrap query.
     *
     * Same key expression the `get` used; a different thing answers it. Crowsnest republishes each
     * active run periodically, so a late joiner is caught up within one interval rather than by asking.
     * The reducer decides whether a snapshot is worth applying, exactly as it did for the query's
     * replies, so nothing downstream knows the difference.
     */
    private fun subscribeState(open: KeelsonSession, checklistKeys: ChecklistKeys) {
        val runScope = scope ?: return
        val inbox = Channel<ByteArray>(Channel.BUFFERED)
        val subscriber = open.declareSubscriber(checklistKeys.stateQuery()) { _, payload ->
            inbox.trySend(payload)
        }
        subscribers = subscribers + subscriber
        runScope.launch {
            for (payload in inbox) {
                val snapshot = ChecklistCodec.decodeSnapshot(payload) ?: continue
                store.apply(snapshot)
                persistProgress()
            }
        }
    }

    /**
     * Procedure definitions published while this phone is listening.
     *
     * Best-effort by nature — see `ChecklistKeys.procedureQuery`. What arrives here is merged into the
     * store and persisted, so a procedure seen once is held for every session after.
     */
    private fun subscribeProcedures(open: KeelsonSession, checklistKeys: ChecklistKeys) {
        val runScope = scope ?: return
        val inbox = Channel<ByteArray>(Channel.BUFFERED)
        val subscriber = open.declareSubscriber(checklistKeys.procedureQuery()) { _, payload ->
            inbox.trySend(payload)
        }
        subscribers = subscribers + subscriber
        runScope.launch {
            for (payload in inbox) {
                val procedure = ChecklistCodec.decodeProcedure(payload) ?: continue
                store.mergeProcedures(listOf(procedure))
                runCatching { repository.saveProcedures(store.state.value.procedures) }
            }
        }
    }

    private suspend fun watchConnection(open: KeelsonSession) {
        var wasConnected = false
        while (true) {
            val connected = open.isConnectedToRouter()
            store.setLink(if (connected) ChecklistLink.Connected else ChecklistLink.Disconnected)
            if (connected && !wasConnected) flushPending(open)
            wasConnected = connected
            delay(CONNECTION_POLL_MILLIS)
        }
    }

    /**
     * Replay whatever never got out. Paced, for the reason the sensor outbox is paced: every profile
     * is `DROP` and a `put` reports success regardless, so a burst that overruns the egress queue is
     * discarded with no signal at all. There are never many of these, which is exactly why waiting a
     * few milliseconds between them costs nothing.
     */
    private suspend fun flushPending(open: KeelsonSession) {
        val publisher = eventPublisher ?: return
        val drained = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        drained.forEach { event ->
            if (!open.publish(publisher, ChecklistCodec.encodeEvent(event)).isSuccess) queue(event)
            delay(REPLAY_SPACING_MILLIS)
        }
    }

    /**
     * Republish a snapshot of every run **this phone created**, on a 30 s tick.
     *
     * The app used to be a pure consumer of `checklist_state`. It cannot stay one now that it can
     * create a run: `checklist_state` exists **only** so a late joiner can bootstrap (§7.3), so a
     * run this phone started and never snapshotted is invisible to every station that was not
     * listening at the time, and unrecoverable afterwards. Nothing else will ever write that key.
     *
     * **Only runs this phone created**, and that restriction is the whole of what makes it safe.
     * §7.4 is blunt that storage does not merge — it keeps the last value — so a late joiner reads
     * one arbitrary writer's snapshot, and a station whose view was a strict subset of another's
     * would silently hand that joiner the subset. Partitioning the writer set by creator is the
     * closest thing to upstream issue #204's one-key-per-writer that needs no payload change, and it
     * is the same partition crowsnest uses. What makes *this* writer trustworthy for its own runs is
     * everything in `applySnapshot`: a §7.2-compliant merger's state is the join of everything it
     * has seen, so its snapshot is a superset of every snapshot it merged rather than a subset.
     *
     * A `put` rather than a declared publisher, because the key set is unbounded — one per run,
     * forever — and a publisher per run would leak declarations for the life of the session.
     */
    private suspend fun snapshotRuns() {
        val who = operator ?: return
        while (true) {
            delay(SNAPSHOT_MILLIS)
            val open = session ?: continue
            val checklistKeys = keys ?: continue
            store.state.value.state.progress.values
                // By **operator**, not by site. The site is shared — several operators can sit at
                // one ROC, and a phone's site id defaults to its entity id but is free text somebody
                // may well set to match a station's — so filtering on it would have this phone
                // republish runs it did not create, which is precisely the subset-writer hazard §7.4
                // warns about. The operator id is generated once per install and never changes.
                .filter { it.runId.isNotEmpty() && it.createdBy == who.operatorId }
                .forEach { run ->
                    open.put(
                        checklistKeys.state(run.runId),
                        ChecklistCodec.encodeSnapshot(run.procedureId, run, who),
                        qosForSubject(Subjects.CHECKLIST_STATE),
                    )
                }
        }
    }

    private suspend fun heartbeat() {
        val who = operator ?: return
        while (true) {
            val publisher = presencePublisher
            val open = session
            if (publisher != null && open != null) {
                open.publish(
                    publisher,
                    ChecklistCodec.encodePresence(
                        who,
                        activeProcedureId,
                        activeItemId,
                        if (activeProcedureId.isEmpty()) CursorState.Idle else CursorState.Viewing,
                        activeRunId,
                        openRunIds,
                    ),
                )
            }
            // Pruned here as well as on arrival, so an operator who simply stopped publishing drops
            // off the list rather than sitting there forever.
            store.prunePresence(System.currentTimeMillis() - PRESENCE_STALE_MILLIS)
            delay(HEARTBEAT_MILLIS)
        }
    }

    private companion object {
        const val TAG = "ChecklistSync"

        /** Crowsnest's numbers, matched so the two sites agree on when somebody has gone quiet. */
        const val HEARTBEAT_MILLIS = 5_000L

        /** §7.1's own figure: every station holding an active run republishes it every 30 s. */
        const val SNAPSHOT_MILLIS = 30_000L
        const val PRESENCE_STALE_MILLIS = 15_000L

        const val CONNECTION_POLL_MILLIS = 2_000L
        const val REPLAY_SPACING_MILLIS = 20L
        const val PENDING_LIMIT = 500
    }
}
