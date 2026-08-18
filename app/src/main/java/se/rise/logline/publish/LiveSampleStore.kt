package se.rise.logline.publish

import se.rise.logline.keelson.PublishedSubject

/** An immutable view of a subject's recent samples, safe to read from Compose. */
data class SampleWindow(
    /** Observation times, oldest first, epoch millis. */
    val timesMillis: LongArray = LongArray(0),
    /** Values, aligned with [timesMillis]. Vector subjects are stored as magnitude. */
    val values: FloatArray = FloatArray(0),
) {
    val size: Int get() = values.size
    val isEmpty: Boolean get() = values.isEmpty()
    val latest: Float? get() = values.lastOrNull()
    val min: Float? get() = values.minOrNull()
    val max: Float? get() = values.maxOrNull()

    // Data classes compare arrays by reference; these exist so equality is by content, which is what
    // Compose needs to decide whether a sparkline actually changed.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SampleWindow) return false
        return timesMillis.contentEquals(other.timesMillis) && values.contentEquals(other.values)
    }

    override fun hashCode(): Int = 31 * timesMillis.contentHashCode() + values.contentHashCode()
}

/** One GNSS fix, kept as the platform reported it rather than as it went on the wire. */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal 1σ radius in metres, or null when the platform did not report one. */
    val accuracyMetres: Float?,
    /**
     * Degrees true, and whether the fix actually carried one.
     *
     * The publisher sends `0.0` for an absent bearing on purpose — a wire concession, documented in the
     * README. A map that trusted that would draw a heading arrow due north every time the phone sat
     * still, which on a stationary Pixel 6 was 34 fixes out of 36. So the truth is kept here.
     */
    val bearingDegrees: Float?,
    val timeMillis: Long,
)

/**
 * The newest camera frame, downscaled for the screen.
 *
 * JPEG bytes rather than a `Bitmap`, deliberately: this class is covered by a JVM unit test and holds
 * no Android types, and the decode belongs on the UI side where it can be cached against the bytes.
 * The thumbnail is a few kB — the published frame is ~150 kB and is not what the live view needs.
 */
data class FramePreview(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    /** Arrival time, as for every other live sample. */
    val timeMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FramePreview) return false
        return timeMillis == other.timeMillis &&
            width == other.width &&
            height == other.height &&
            jpeg.contentEquals(other.jpeg)
    }

    override fun hashCode(): Int =
        ((jpeg.contentHashCode() * 31 + timeMillis.hashCode()) * 31 + width) * 31 + height
}

/**
 * A fixed-capacity ring of (time, value) pairs, with one writer and one reader.
 *
 * Flat primitive arrays rather than a list of objects: at 217 samples/s a boxed sample per reading
 * would add that many short-lived allocations per second on top of what the status store already makes,
 * and the whole point of this class is to stay invisible to the publish path.
 *
 * **Capacity is in samples, not seconds.** `SensorRate.Max` is a legal setting and the gyroscope was
 * measured at ~442 Hz, so a window sized in seconds is not bounded in practice.
 */
internal class SampleRing(private val capacity: Int) {

    private val times = LongArray(capacity)
    private val values = FloatArray(capacity)
    private var count = 0
    private var next = 0

    /**
     * Append one sample.
     *
     * `synchronized` rather than something cleverer: each subject has exactly one writing collector, so
     * this lock is uncontended, and an uncontended lock costs nanoseconds against the ~5 ms budget of a
     * 217 Hz publish path. What it buys is the happens-before edge the Compose reader needs — the
     * collectors run on `Dispatchers.Default` and can migrate threads, so a plain array write would not
     * be reliably visible on Main.
     */
    fun append(timeMillis: Long, value: Float) = synchronized(this) {
        times[next] = timeMillis
        values[next] = value
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /**
     * Just the newest value, without copying the ring.
     *
     * The main screen wants one number per subject once a second; [snapshot] would hand it 8192 floats
     * per subject to get at the last one. Same lock, so the same visibility guarantee.
     */
    fun latest(): Float? = synchronized(this) {
        if (count == 0) null else values[((next - 1) % capacity + capacity) % capacity]
    }

    /** A copy, oldest first. Isolated from any concurrent [append]. */
    fun snapshot(): SampleWindow = synchronized(this) {
        if (count == 0) return SampleWindow()
        val outTimes = LongArray(count)
        val outValues = FloatArray(count)
        // `next` is one past the newest, so the oldest is `next - count` modulo capacity.
        val start = ((next - count) % capacity + capacity) % capacity
        for (i in 0 until count) {
            val src = (start + i) % capacity
            outTimes[i] = times[src]
            outValues[i] = values[src]
        }
        SampleWindow(outTimes, outValues)
    }

    fun clear() = synchronized(this) {
        count = 0
        next = 0
    }
}

/** As [SampleRing], but for the GNSS track, which needs more than one number per point. */
internal class TrackRing(private val capacity: Int) {

    private val points = arrayOfNulls<TrackPoint>(capacity)
    private var count = 0
    private var next = 0

    fun append(point: TrackPoint) = synchronized(this) {
        points[next] = point
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** The newest point only — the main screen wants a position, not the whole track. */
    fun latest(): TrackPoint? = synchronized(this) {
        if (count == 0) null else points[((next - 1) % capacity + capacity) % capacity]
    }

    fun snapshot(): List<TrackPoint> = synchronized(this) {
        if (count == 0) return emptyList()
        val start = ((next - count) % capacity + capacity) % capacity
        List(count) { i -> points[(start + i) % capacity]!! }
    }

    fun clear() = synchronized(this) {
        count = 0
        next = 0
    }
}

/**
 * A single slot holding the newest camera frame.
 *
 * One frame, not a ring: a time-lapse thumbnail is only ever looked at as "what is the camera seeing
 * now", and keeping a history of 150 kB frames would dwarf every other live buffer put together. The
 * lock is here for the same reason [SampleRing] has one — the collector runs on `Dispatchers.Default`
 * and Compose reads on Main, so a plain field write would not be reliably visible.
 */
internal class FrameSlot {

    private var frame: FramePreview? = null

    fun set(preview: FramePreview) = synchronized(this) { frame = preview }

    fun latest(): FramePreview? = synchronized(this) { frame }

    fun clear() = synchronized(this) { frame = null }
}

/** Everything the live view draws, captured at one instant. */
/**
 * The newest value of everything, and nothing else.
 *
 * What the main screen reads: it shows one reading per row, so carrying whole windows there would copy
 * a quarter of a million floats a second to display twenty-nine numbers.
 */
data class LiveLatest(
    val values: Map<PublishedSubject, Float> = emptyMap(),
    val fix: TrackPoint? = null,
) {
    operator fun get(subject: PublishedSubject): Float? = values[subject]
}

data class LiveSnapshot(
    val windows: Map<PublishedSubject, SampleWindow> = emptyMap(),
    val track: List<TrackPoint> = emptyList(),
    /** The newest camera frame, or null when the camera is off or has not produced one yet. */
    val frame: FramePreview? = null,
) {
    operator fun get(subject: PublishedSubject): SampleWindow = windows[subject] ?: SampleWindow()
    val lastFix: TrackPoint? get() = track.lastOrNull()
}

/**
 * Recent published values, kept so the live view can show what is actually going on the bus.
 *
 * Deliberately **not** a `StateFlow`. The publish path produces 217 samples/s across 8 concurrent
 * collectors; emitting each one would rebuild an outer structure per sample and put the UI's
 * recomposition rate at the mercy of the sensors. Instead the collectors only append to their own ring,
 * and the screen pulls a [snapshot] on its own ticker — so the cost on the hot path is one lock and two
 * array writes, and the redraw rate is a property of the screen.
 *
 * One ring per subject also means the four 50 Hz IMU subjects never contend with each other.
 */
class LiveSampleStore(
    samplesPerSubject: Int = DEFAULT_CAPACITY,
    trackPoints: Int = DEFAULT_TRACK_CAPACITY,
) {
    private val rings: Map<PublishedSubject, SampleRing> =
        PublishedSubject.entries.associateWith { SampleRing(samplesPerSubject) }

    private val track = TrackRing(trackPoints)

    private val frame = FrameSlot()

    /** Called from a collector, on the publish path. Must never throw — see `SubjectSink.guard`. */
    fun record(subject: PublishedSubject, timeMillis: Long, value: Float) {
        rings[subject]?.append(timeMillis, value)
    }

    fun recordFix(point: TrackPoint) = track.append(point)

    /** Called from the camera collector with an already-downscaled thumbnail, never the full frame. */
    fun recordFrame(preview: FramePreview) = frame.set(preview)

    /** The newest value per subject, plus the last fix. Cheap enough to poll at 1 Hz from the UI. */
    fun latest(): LiveLatest = LiveLatest(
        values = buildMap {
            rings.forEach { (subject, ring) -> ring.latest()?.let { put(subject, it) } }
        },
        fix = track.latest(),
    )

    fun snapshot(): LiveSnapshot = LiveSnapshot(
        windows = rings.mapValues { (_, ring) -> ring.snapshot() },
        track = track.snapshot(),
        frame = frame.latest(),
    )

    /** A new run starts empty, matching `PublisherStatusStore.started()`. */
    fun clear() {
        rings.values.forEach { it.clear() }
        track.clear()
        frame.clear()
    }

    companion object {
        /**
         * ~2.7 minutes at 50 Hz, and far longer for the 1 Hz subjects. Across 25 subjects this is about
         * 2.5 MB of flat arrays — bounded regardless of what rate anyone configures.
         */
        const val DEFAULT_CAPACITY = 8192

        /** An hour of track at the default 1 Hz. Fixes are cheap and a long track is the useful one. */
        const val DEFAULT_TRACK_CAPACITY = 4096
    }
}
