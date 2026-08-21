package se.rise.logline.record

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import se.rise.logline.publish.RuntimeEstimate
import se.rise.logline.publish.RuntimeEstimator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "Recorder"

/**
 * Where recording stops: [openSession] refuses to open the next file below this.
 *
 * Shared rather than private because the front page's capacity estimate has to predict *this* moment
 * — the recorder giving up — and not a full volume. Two different floors would make the screen and
 * the recorder disagree about how much room is left.
 */
const val MIN_FREE_BYTES = 256L * 1024 * 1024

/**
 * The directory recordings are written to before they are published to Downloads.
 *
 * One definition, used by the recorder and by the front page's capacity estimate alike — two spellings
 * of the same path could end up measuring free space on a different volume than the one being written
 * to, and the screen would be confidently wrong.
 */
internal fun recordingsDir(context: Context): File =
    File(context.filesDir, "recordings").apply { mkdirs() }

/** Free space on that volume, for anything that wants to say how much room is left. */
fun recordingsFreeBytes(context: Context): Long = recordingsDir(context).usableSpace

/** What the UI shows about the current recording. */
data class RecordingStatus(
    val recording: Boolean = false,
    /** When this run's recording began, for the elapsed clock. 0 when nothing is recording. */
    val startedAtEpochMillis: Long = 0,
    /**
     * When it ended, so the elapsed clock stops there rather than running on after Stop.
     *
     * 0 while recording, which is what [startedAtEpochMillis] is read against — a card that took
     * `now` for a finished recording would go on counting a file nothing is writing to.
     */
    val stoppedAtEpochMillis: Long = 0,
    /**
     * The file being written, and its figures — **per file, not per run.**
     *
     * They restart at every 512 MB rotation, because they belong with `fileName` and answer "how is
     * this file doing". [filesCompleted] is the run-level number, and the two are read together.
     */
    val fileName: String? = null,
    val messagesWritten: Long = 0,
    val bytesWritten: Long = 0,
    /**
     * Files that reached Downloads/Logline, counted only once the copy succeeded.
     *
     * The last file counts too — it did not use to, so an ordinary run that never reached a rotation
     * ended with this at zero having written and saved a file perfectly well. A failed copy is
     * deliberately not counted: the file is still in app storage and recoverable, and a screen saying
     * it was saved is how somebody comes to wipe the phone with the run still on it.
     */
    val filesCompleted: Int = 0,
    /**
     * Samples the queue could not accept.
     *
     * Surfaced rather than swallowed on purpose: a recording with an unreported hole is worse than one
     * that admits to it, and this is the number that says whether the file is complete.
     */
    val dropped: Long = 0,
    val error: String? = null,
    /**
     * How long until the volume is too full to keep recording.
     *
     * The other half of "how much longer can this run go": a phone with four hours of battery and forty
     * minutes of free space has forty minutes. [RuntimeEstimate.Charging] never appears here — a disk
     * does not go on charge — so this is [RuntimeEstimate.Unknown] until the fill rate has been measured.
     */
    val spaceRuntime: RuntimeEstimate = RuntimeEstimate.Unknown,
)

/**
 * Writes published samples to MCAP files on disk.
 *
 * **Not gated on publish success.** A Zenoh `put` succeeds even when the router is gone — measured
 * here, ~9000 successful puts landed on an empty bus during a 26 s outage — so publish results say
 * nothing about whether the data survived. Recording independently is the whole point: the local file
 * is the complete log, the bus is best-effort.
 *
 * The publish path only offers to a bounded channel; a single coroutine on `Dispatchers.IO` drains it
 * and does all the file work, so ~217 samples/s of sensor traffic never waits on a write.
 */
class Recorder(private val appContext: Context) {

    private val _status = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    /**
     * **One channel per run, not one per Recorder.**
     *
     * `stop()` closes the channel to end the drain loop, and a closed channel can never be reopened —
     * so a single long-lived one meant the *second* run in a process recorded nothing at all: the drain
     * loop saw a closed queue, finished immediately, published a header-only file with no messages, and
     * every sample after that was counted as dropped. Silently, with the counters still climbing.
     * Volatile because collectors on `Dispatchers.Default` call [offer] while the main thread swaps it.
     */
    @Volatile
    private var queue: Channel<RecordSample>? = null

    @Volatile
    private var scope: CoroutineScope? = null
    private var drainJob: Job? = null

    /**
     * How far behind the writer is — see [QueueLoad].
     *
     * Long-lived and `reset()` per run rather than replaced with the channel, because the UI polls it
     * on a ticker and a field swapped underneath a reader would hand back a fresh zero mid-run. It is
     * deliberately **not** on [RecordingStatus]: the drain loop already updates that flow once per
     * written sample, and putting a depth there would add a second allocation at the same rate, on the
     * one coroutine whose falling behind is the thing being measured.
     */
    val queueLoad = QueueLoad(QUEUE_CAPACITY)

    private val recordingsDir: File get() = recordingsDir(appContext)

    /**
     * Offer a sample. Never blocks and never throws — it is called from the publish path, where a
     * throw would be swallowed into a subject failure and kill that collector.
     */
    fun offer(sample: RecordSample) {
        // The queue, not the scope, is what a sample actually needs: gating on the scope left a window
        // where a run had started but its channel had not been swapped in yet.
        val channel = queue ?: return
        val accepted = channel.trySend(sample).isSuccess
        if (accepted) queueLoad.enqueued() else _status.update { it.copy(dropped = it.dropped + 1) }
    }

    fun start(maxBytes: Long = DEFAULT_MAX_BYTES) {
        if (scope != null) return
        val descriptor = try {
            appContext.assets.open(DESCRIPTOR_ASSET).use { it.readBytes() }
        } catch (t: Throwable) {
            Log.e(TAG, "descriptor asset missing; recording without schemas", t)
            ByteArray(0)
        }

        val newQueue = Channel<RecordSample>(capacity = QUEUE_CAPACITY)
        // With the channel, so a run always starts from a clean depth and an empty high-water mark.
        queueLoad.reset()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        queue = newQueue
        scope = newScope
        _status.value = RecordingStatus(recording = true, startedAtEpochMillis = System.currentTimeMillis())

        // Held on its own, not reached for through the scope: `stop()` waits for *this* coroutine to
        // finish what is queued, and the scope's other children include a poll loop that only ends
        // once the run is marked stopped — which happens after that wait. Joining the scope would be
        // joining something waiting on the join.
        drainJob = newScope.launch {
            // Its own run's channel and its own run's scope, not whatever the fields point at by then:
            // a Stop immediately followed by a Start must not have this loop draining the new run's
            // samples into the old run's file, nor handing a rotation copy to the new run's scope.
            drain(newQueue, descriptor, maxBytes, newScope)
        }
        // Anything left behind by a crash goes to Downloads, so a killed process never strands a
        // recording somewhere the user cannot reach — but on its *own* coroutine. Doing it before the
        // drain meant a 212 MB orphan blocked the loop for the length of a copy, and every sample
        // offered meanwhile was counted as dropped: measured at 20 000 lost in the first half-minute
        // of a run, with the file still showing zero messages.
        newScope.launch { publishOrphans() }
        // Its own coroutine for the same reason: a `statvfs` is cheap but it is still I/O, and the
        // drain loop is what must never wait.
        newScope.launch { trackFreeSpace() }
    }

    /**
     * How fast the volume is filling, measured rather than derived.
     *
     * Free space, not `bytesWritten / elapsed`: [RecordingStatus.bytesWritten] restarts at every
     * rotation, so a rate taken from it is wrong for the minutes after each new file — and free space is
     * the number that actually ends the run, whether this app filled it or another one did. The fuel is
     * the space *above* [MIN_FREE_BYTES], because that floor is where [openSession] refuses to open the
     * next file, which is the moment being predicted.
     *
     * Rotation briefly dips free space by up to a file's worth, since [publish] copies to Downloads
     * before deleting the original. The estimator's least squares over a trailing window is what absorbs
     * that — the same reason the battery gauge uses it.
     */
    private suspend fun trackFreeSpace() {
        val estimator = RuntimeEstimator()
        // Gated on the recording rather than looping until cancelled: [openSession] gives up on its own
        // when the volume is already below the floor, and an estimate that kept ticking after that would
        // be predicting the end of something that had already ended.
        while (_status.value.recording) {
            val fuel = (recordingsDir.usableSpace - MIN_FREE_BYTES).coerceAtLeast(0L)
            val estimate = estimator.record(System.currentTimeMillis(), fuel.toDouble())
            _status.update { it.copy(spaceRuntime = estimate) }
            delay(FREE_SPACE_POLL_MILLIS)
        }
    }

    private suspend fun drain(
        queue: Channel<RecordSample>,
        descriptor: ByteArray,
        maxBytes: Long,
        runScope: CoroutineScope,
    ) {
        var session = openSession(descriptor, maxBytes) ?: return
        // When the file's figures were last put on the status flow. See [pushFileStatus].
        var lastPushNanos = 0L

        /**
         * Publish the current file's name, message count and size — **at most every
         * [STATUS_PUSH_NANOS]**, unless forced.
         *
         * It used to run on every written sample: a lambda, a `copy()` and a `MutableStateFlow` CAS
         * for each, at a rate measured up to 800 samples a second, on the one coroutine that must not
         * fall behind. Nothing reads it that fast — the start screen polls at 1 Hz and the live view at
         * 5 — so it was the same anti-pattern the live store exists to avoid, stated in this codebase
         * as "the live view pulls, it never gets pushed".
         *
         * `force` is not decoration. Throttling alone would leave the last fraction of a second of
         * writes unreported, so the count on screen would settle just short of the count in the file —
         * and this app's whole claim about recording is that those two numbers agree.
         */
        fun pushFileStatus(current: RecordingSession, force: Boolean = false) {
            val now = System.nanoTime()
            if (!force && now - lastPushNanos < STATUS_PUSH_NANOS) return
            lastPushNanos = now
            _status.update {
                it.copy(
                    fileName = current.path.name,
                    messagesWritten = current.messageCount,
                    bytesWritten = current.bytesWritten,
                )
            }
        }

        try {
            for (sample in queue) {
                // Instant.now() rather than currentTimeMillis()*1e6: the latter is millisecond-
                // granular, which at 55 Hz collapses several messages onto one log_time and can even
                // place a write marginally *before* the enclose it followed.
                val at = java.time.Instant.now()
                session.write(sample, at.epochSecond * 1_000_000_000L + at.nano)
                // After the write, not before it: the depth this reports is samples still owed a place
                // in the file, so a sample counts as drained only once it is in one.
                queueLoad.drained()
                pushFileStatus(session)
                if (session.shouldRotate()) {
                    session.close(activeTags)
                    // **After the close, not before.** `bytesWritten` reads through to the writer, and
                    // closing is what emits the summary section and the footer — pushed first, the
                    // figure on screen would be short by everything the close writes.
                    pushFileStatus(session, force = true)
                    // **Off the drain, the same shape `publishOrphans()` uses and for the same
                    // reason.** This copies up to 512 MB into Downloads, and called inline it stopped
                    // the loop for the length of that copy — the queue holds about 45 seconds of
                    // samples at the measured rate, and a large copy can plausibly outlast it, at
                    // which point every further sample is counted as dropped. That is exactly the
                    // failure `publishOrphans()` was moved off this coroutine for after it cost 20 000
                    // samples in half a minute; a rotation is the same copy at the same place.
                    //
                    // `session.path` is read now rather than inside the launch: `session` is
                    // reassigned on the very next line, and a lambda capturing the variable would
                    // publish whichever file it happened to name by the time it ran.
                    val finished = session.path
                    runScope.launch {
                        if (publish(finished)) {
                            _status.update { it.copy(filesCompleted = it.filesCompleted + 1) }
                        }
                    }
                    session = openSession(descriptor, maxBytes) ?: return
                }
            }
        } catch (c: CancellationException) {
            // Not a fault, and not the error field's business: the run was told to stop. Rethrown so
            // the coroutine ends cancelled, and the `finally` below still closes and publishes the
            // file. Catching it as an error put "Recording problem" on screen over a recording that
            // had just saved perfectly well — the same shape as the publishOrphans race.
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "recording stopped", t)
            _status.update { it.copy(error = t.message ?: t.javaClass.simpleName) }
        } finally {
            // The last file counts too. It used to not: `filesCompleted` was incremented at rotation
            // only, so an ordinary run — one that never reached 512 MB — ended having written and saved
            // a file while the screen said nothing had been saved at all.
            //
            // **This one stays inline, unlike the rotation copy above.** `stop()` waits for *this*
            // coroutine and then cancels the scope, so a final publish handed to that scope would be
            // racing the cancellation that follows its own join. There is nothing left to stall here
            // either — the queue is closed and empty by the time the `finally` runs, so a slow copy
            // costs no samples.
            runCatching { session.close(activeTags) }
            // **Anything written since the last throttled push**, or the figures on screen settle a
            // fraction of a second short of the file's own — and after the close, because that is what
            // emits the summary section and the footer. Measured before this moved: a 1.51 MB file
            // reported as 1.4 MB, which is the closing bytes missing.
            //
            // Guarded on the session having taken samples, for the same reason the block below only
            // touches the count: after a rotation the final session can be empty, and restating its
            // zeroes would wipe a real file's figures off the card.
            if (session.messageCount > 0) pushFileStatus(session, force = true)
            val saved = runCatching { publish(session.path) }.getOrDefault(false)
            if (saved) _status.update { it.copy(filesCompleted = it.filesCompleted + 1) }
            // Only the count. `fileName`, `messagesWritten` and `bytesWritten` describe the last file
            // that actually took a sample, and after a rotation the final session can be empty — so
            // restating them here would replace a real file's figures with an empty one's zeroes.
        }
    }

    private fun openSession(descriptor: ByteArray, maxBytes: Long): RecordingSession? {
        val free = recordingsDir.usableSpace
        if (free < MIN_FREE_BYTES) {
            val message = "only ${free / 1024 / 1024} MB free; not recording"
            Log.w(TAG, message)
            _status.update {
                it.copy(
                    recording = false,
                    stoppedAtEpochMillis = System.currentTimeMillis(),
                    error = message,
                )
            }
            return null
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HHmmss", Locale.US).format(Date())
        return RecordingSession(File(recordingsDir, "logline-$stamp.mcap"), descriptor, maxBytes)
    }

    /**
     * Identifies the run currently recording, for [stop]'s benefit. Null when nothing is.
     *
     * Exists because the stop has to happen *after* the publisher's collectors are cancelled — see
     * `SensorPublisher.stopInternal` — which means it happens on a coroutine, which means a fast Stop
     * then Start can have the new run already recording by the time the old stop lands. Handing the
     * token back is what lets that stop recognise it is stale and do nothing, rather than closing the
     * new run's file. That failure has a precedent here: a single long-lived channel once made the
     * second run in a process record nothing at all.
     */
    fun runToken(): Any? = scope

    /**
     * The tags to write into whatever file is open, and into every file this run opens after it.
     *
     * Pushed in as they change rather than read at stop, because a rotation closes a file without
     * anybody asking it to — so each file carries what was switched on as *it* closed.
     */
    fun setTags(tags: Set<String>) {
        activeTags = tags
    }

    /** What is switched on right now, read at each file's close. */
    @Volatile
    private var activeTags: Set<String> = emptySet()

    fun stop(token: Any? = null) {
        val runScope = scope ?: return
        if (token != null && token !== runScope) {
            Log.i(TAG, "stale stop ignored; a newer run is recording")
            return
        }
        scope = null
        // Cleared before closing, so a sample arriving mid-stop is ignored rather than counted as a
        // drop against a queue that is on its way out.
        val runQueue = queue
        queue = null
        // Closing the channel ends the for-loop, which runs the finally that closes and publishes the
        // file. Fire-and-forget, like SensorPublisher.stop(): waiting here would block a Service
        // callback on disk I/O.
        runQueue?.close()
        val finishing = drainJob
        drainJob = null
        CoroutineScope(Dispatchers.IO).launch {
            // **Wait for the drain to end on its own; do not cancel it.** Closing a channel does not
            // discard what is already buffered — the loop would write every one of those samples — but
            // cancellation beats it, because `receive()` is cancellable. Cancelling here therefore
            // threw away up to QUEUE_CAPACITY samples from the tail of every recording, and counted
            // none of them: measured, a run that published 47 381 samples wrote 47 360 messages.
            val finished = withTimeoutOrNull(DRAIN_GRACE_MILLIS) { finishing?.join() } != null
            if (!finished) {
                // The grace is generous for writing what is buffered, which is all this now has to
                // cover: the rotation copy moved off this coroutine, so the only file work left in the
                // drain is the final publish in its `finally`. If it ever expires, say how much it cost
                // rather than letting the file be quietly short — `dropped` is already on screen.
                var lost = 0
                while (runQueue?.tryReceive()?.isSuccess == true) lost++
                Log.w(TAG, "drain did not finish within ${DRAIN_GRACE_MILLIS}ms; $lost samples not written")
                if (lost > 0) _status.update { it.copy(dropped = it.dropped + lost) }
            }
            // Also what waits for a rotation copy still in flight. `publish` is blocking I/O and never
            // suspends, so cancellation cannot interrupt it part-way and leave a truncated file in
            // Downloads — `cancelAndJoin` simply waits for it to finish.
            runCatching { runScope.coroutineContext[Job]?.cancelAndJoin() }
            // The estimate goes with the run that measured it: a stopped recording is not filling
            // anything, and a stale "40 min of space left" would outlive the thing it described.
            _status.update {
                it.copy(
                    recording = false,
                    stoppedAtEpochMillis = System.currentTimeMillis(),
                    spaceRuntime = RuntimeEstimate.Unknown,
                )
            }
        }
    }

    /**
     * Copy a finished file into Downloads, where the user can actually get at it, then delete it.
     *
     * Returns whether it arrived — which is what [RecordingStatus.filesCompleted] counts, because that
     * number is read as the answer to "did it save?". Counting the copies that failed would be worse
     * than counting nothing: the file is still recoverable from app storage, and a screen claiming it
     * was saved is how somebody comes to wipe the phone with the run still on it.
     */
    private fun publish(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            saveToDownloads(appContext, file.name, "application/octet-stream") { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            file.delete()
            Log.i(TAG, "published ${file.name} to Downloads/Logline")
            true
        } catch (e: java.io.FileNotFoundException) {
            // The file went while this was reading it, which means the *other* half of a stop-start
            // already published it: `stop()` is fire-and-forget, so a run's closing publish can still
            // be in flight when the next run's orphan sweep finds the same file and races it to the
            // delete. Both are trying to save the same recording and one of them succeeded, so this
            // is not a failure — reporting it put "Recording problem" on screen for a run that had
            // just saved perfectly well, every time settings were saved mid-run.
            Log.i(TAG, "${file.name} was published by the other half of a restart", e)
            false
        } catch (t: Throwable) {
            // Keep the local file if publishing failed — it is still recoverable with adb, whereas
            // deleting it would lose the run outright.
            Log.e(TAG, "could not publish ${file.name} to Downloads; leaving it in app storage", t)
            _status.update { it.copy(error = "could not save to Downloads: ${t.message}") }
            false
        }
    }

    private fun publishOrphans() {
        recordingsDir.listFiles { f -> f.isFile && f.name.endsWith(".mcap") }?.forEach { orphan ->
            // Finalise before publishing: a killed process leaves a file with no footer, and readers
            // seek to the footer first — so every message is present and none of them is reachable.
            val trimmed = runCatching { McapRecovery.finalise(orphan) }
                .onFailure { Log.w(TAG, "could not finalise ${orphan.name}", it) }
                .getOrNull()
            Log.i(TAG, "publishing orphaned recording ${orphan.name} (trimmed ${trimmed ?: 0} bytes)")
            publish(orphan)
        }
    }

    companion object {
        /** ~512 MB, roughly six and a half hours at the default rates. */
        const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024

        /**
         * Generous: at 217 samples/s this is about 45 seconds of slack, so an I/O stall has to be
         * severe before anything is dropped — and if it is, [RecordingStatus.dropped] says so.
         */
        /**
         * How long Stop waits for the queue to finish writing before giving up on it.
         *
         * Ten thousand buffered samples are a fraction of a second of writing, so five seconds covers
         * it many times over. It deliberately does not have to cover a 512 MB copy into Downloads: the
         * rotation publish runs on its own coroutine, and the final one happens in the drain's
         * `finally` *after* the loop has ended — so it is `cancelAndJoin` below that waits for it,
         * without a timeout, rather than this.
         */
        private const val DRAIN_GRACE_MILLIS = 5_000L

        private const val QUEUE_CAPACITY = 10_000

        /**
         * How often the file's figures reach the status flow: four times a second.
         *
         * Comfortably faster than anything that reads them — the start screen polls at 1 Hz and the
         * live view at 5 — and it takes the work on the drain coroutine from up to 800 updates a second
         * to four. The exact figure matters far less than that it is a ceiling at all.
         */
        private const val STATUS_PUSH_NANOS = 250_000_000L



        /**
         * Thirty seconds. The estimator needs three minutes of span before it says anything, so this is
         * six points into its first answer, and a `statvfs` twice a minute costs nothing next to the
         * ~77 MB/h being written past it.
         */
        private const val FREE_SPACE_POLL_MILLIS = 30_000L
    }
}
