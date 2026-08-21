package se.rise.logline.record

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "TrackCache"

/**
 * Tracks already read, so a recording is scanned once and never again.
 *
 * Extracting a track means decompressing a recording's whole data section — `McapWriter` writes no
 * chunk index, so there is nothing to seek to. Measured on this phone: about 116 MB/s, which is 18 ms
 * for the median 2.1 MB recording and **4.3 s** for the one 502 MB file. Cheap enough to do for a row
 * somebody is looking at, far too expensive to do twice.
 *
 * What is stored is the *thumbnail's* track — [MAX_POINTS] points — not the recording's. A 64dp square
 * cannot show more, and it keeps an entry near two kilobytes.
 */
object TrackCache {

    /**
     * Points kept per recording.
     *
     * The thumbnail is 64dp square, so about 170 pixels across on this phone: a point every pixel and a
     * bit is already more shape than can be seen. `McapTrack.downsample` keeps the first and last, so
     * the ends of the run survive the thinning.
     */
    const val MAX_POINTS = 128

    /**
     * The track for a recording, or null if it has not been read.
     *
     * **An empty list is an answer, not a miss.** A recording with no fixes is worth remembering as
     * having none, or every visit to the list rescans every IMU-only run to learn the same thing.
     */
    fun get(directory: File, id: Long, stamp: Stamp): List<TrackFix>? {
        memory[id]?.let { (cached, fixes) -> if (cached == stamp) return fixes }
        val file = File(directory, "$id$EXTENSION")
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readByte().toInt() != VERSION) return null
                val onDisk = Stamp(input.readLong(), input.readLong())
                // A file replaced under the same MediaStore id must not show the old shape.
                if (onDisk != stamp) return null
                val count = input.readInt()
                if (count < 0 || count > MAX_POINTS) return null
                val fixes = ArrayList<TrackFix>(count)
                repeat(count) { fixes.add(TrackFix(input.readDouble(), input.readDouble())) }
                fixes.also { memory[id] = stamp to it }
            }
        } catch (t: Throwable) {
            // A half-written entry is not worth reporting as a failure: it is re-read for the price of
            // the scan that produced it.
            Log.i(TAG, "unreadable track cache for $id", t)
            null
        }
    }

    /** Remember a track. Coordinates are doubles: a float's metre of error is wider than most of the
     *  stationary runs this is meant to distinguish. */
    fun put(directory: File, id: Long, stamp: Stamp, fixes: List<TrackFix>) {
        val kept = McapTrack.downsample(fixes, MAX_POINTS)
        memory[id] = stamp to kept
        try {
            directory.mkdirs()
            DataOutputStream(File(directory, "$id$EXTENSION").outputStream().buffered()).use { out ->
                out.writeByte(VERSION)
                out.writeLong(stamp.sizeBytes)
                out.writeLong(stamp.savedAtMillis)
                out.writeInt(kept.size)
                kept.forEach {
                    out.writeDouble(it.latitude)
                    out.writeDouble(it.longitude)
                }
            }
        } catch (t: Throwable) {
            // The memory copy still stands, so the session is unaffected; only the next launch pays.
            Log.i(TAG, "could not cache track for $id", t)
        }
    }

    /** Drop entries for recordings that are no longer listed, so a deleted run does not leave a file. */
    fun prune(directory: File, keep: Set<Long>) {
        memory.keys.retainAll(keep)
        val stale = directory.listFiles()?.filter { file ->
            file.name.endsWith(EXTENSION) &&
                file.name.removeSuffix(EXTENSION).toLongOrNull() !in keep
        }
        stale?.forEach { it.delete() }
    }

    /** What makes a cached track still the right one. */
    data class Stamp(val sizeBytes: Long, val savedAtMillis: Long)

    /**
     * Drop the in-memory copies, leaving what is on disk.
     *
     * For tests: [put] fills memory as well as disk, so a test that reads straight back would pass with
     * nothing written at all — which is the whole of what this cache is for on the second launch.
     */
    internal fun forgetInMemory() = memory.clear()

    private val memory = ConcurrentHashMap<Long, Pair<Stamp, List<TrackFix>>>()

    private const val EXTENSION = ".trk"
    private const val VERSION = 1
}
