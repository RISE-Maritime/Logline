package se.rise.logline.record

import android.content.Context
import android.util.Log
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
import se.rise.logline.publish.RuntimeEstimate
import se.rise.logline.publish.RuntimeEstimator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "Recorder"

/** What the UI shows about the current recording. */
data class RecordingStatus(
    val recording: Boolean = false,
    /** When this run's recording began, for the elapsed clock. 0 when nothing is recording. */
    val startedAtEpochMillis: Long = 0,
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

    private val recordingsDir: File get() = File(appContext.filesDir, "recordings").apply { mkdirs() }

    /**
     * Offer a sample. Never blocks and never throws — it is called from the publish path, where a
     * throw would be swallowed into a subject failure and kill that collector.
     */
    fun offer(sample: RecordSample) {
        // The queue, not the scope, is what a sample actually needs: gating on the scope left a window
        // where a run had started but its channel had not been swapped in yet.
        val channel = queue ?: return
        val accepted = channel.trySend(sample).isSuccess
        if (!accepted) _status.update { it.copy(dropped = it.dropped + 1) }
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
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        queue = newQueue
        scope = newScope
        _status.value = RecordingStatus(recording = true, startedAtEpochMillis = System.currentTimeMillis())

        newScope.launch {
            // Its own run's channel, not whatever the field points at by then: a Stop immediately
            // followed by a Start must not have this loop draining the new run's samples into the old
            // run's file.
            drain(newQueue, descriptor, maxBytes)
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

    private suspend fun drain(queue: Channel<RecordSample>, descriptor: ByteArray, maxBytes: Long) {
        var session = openSession(descriptor, maxBytes) ?: return
        try {
            for (sample in queue) {
                // Instant.now() rather than currentTimeMillis()*1e6: the latter is millisecond-
                // granular, which at 55 Hz collapses several messages onto one log_time and can even
                // place a write marginally *before* the enclose it followed.
                val at = java.time.Instant.now()
                session.write(sample, at.epochSecond * 1_000_000_000L + at.nano)
                _status.update {
                    it.copy(
                        fileName = session.path.name,
                        messagesWritten = session.messageCount,
                        bytesWritten = session.bytesWritten,
                    )
                }
                if (session.shouldRotate()) {
                    session.close()
                    if (publish(session.path)) {
                        _status.update { it.copy(filesCompleted = it.filesCompleted + 1) }
                    }
                    session = openSession(descriptor, maxBytes) ?: return
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recording stopped", t)
            _status.update { it.copy(error = t.message ?: t.javaClass.simpleName) }
        } finally {
            // The last file counts too. It used to not: `filesCompleted` was incremented at rotation
            // only, so an ordinary run — one that never reached 512 MB — ended having written and saved
            // a file while the screen said nothing had been saved at all.
            runCatching { session.close() }
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
            _status.update { it.copy(recording = false, error = message) }
            return null
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HHmmss", Locale.US).format(Date())
        return RecordingSession(File(recordingsDir, "logline-$stamp.mcap"), descriptor, maxBytes)
    }

    fun stop() {
        val runScope = scope ?: return
        scope = null
        // Cleared before closing, so a sample arriving mid-stop is ignored rather than counted as a
        // drop against a queue that is on its way out.
        val runQueue = queue
        queue = null
        // Closing the channel ends the for-loop, which runs the finally that closes and publishes the
        // file. Joining would block a Service callback on disk I/O, so this is fire-and-forget like
        // SensorPublisher.stop().
        runQueue?.close()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { runScope.coroutineContext[Job]?.cancelAndJoin() }
            // The estimate goes with the run that measured it: a stopped recording is not filling
            // anything, and a stale "40 min of space left" would outlive the thing it described.
            _status.update { it.copy(recording = false, spaceRuntime = RuntimeEstimate.Unknown) }
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
        private const val QUEUE_CAPACITY = 10_000

        private const val MIN_FREE_BYTES = 256L * 1024 * 1024

        /**
         * Thirty seconds. The estimator needs three minutes of span before it says anything, so this is
         * six points into its first answer, and a `statvfs` twice a minute costs nothing next to the
         * ~77 MB/h being written past it.
         */
        private const val FREE_SPACE_POLL_MILLIS = 30_000L
    }
}
