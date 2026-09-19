package se.rise.logline.monitor

import foxglove.LocationFixOuterClass.LocationFix
import keelson.Decomposed3DVectorOuterClass.Decomposed3DVector
import keelson.Primitives.TimestampedBool
import keelson.Primitives.TimestampedDouble
import keelson.Primitives.TimestampedFloat
import keelson.Primitives.TimestampedInt
import keelson.Primitives.TimestampedInt64
import keelson.Primitives.TimestampedString
import se.rise.logline.keelson.pubsubSubjectAndSource
import se.rise.logline.keelson.unwrapEnvelope
import se.rise.logline.publish.SampleRing
import se.rise.logline.publish.SampleWindow
import se.rise.logline.publish.TrackPoint
import se.rise.logline.publish.TrackRing
import java.util.concurrent.ConcurrentHashMap

/** One (subject, source) pair on the watched entity. The entity is the tab's, so it is not repeated. */
data class Topic(val subject: String, val source: String) : Comparable<Topic> {
    override fun compareTo(other: Topic): Int =
        compareValuesBy(this, other, Topic::subject, Topic::source)

    override fun toString(): String = "$subject/$source"
}

/** How a subject's payload is read, from its keelson type. */
enum class PayloadShape { Scalar, Vector, Position, Text, Undecoded }

internal fun shapeOf(subject: String): PayloadShape = when (remoteSubjectTypes[subject]) {
    "keelson.TimestampedFloat", "keelson.TimestampedFloatPeriod", "keelson.TimestampedDouble",
    "keelson.TimestampedInt", "keelson.TimestampedInt64", "keelson.TimestampedBool",
    -> PayloadShape.Scalar
    "keelson.Decomposed3DVector" -> PayloadShape.Vector
    "foxglove.LocationFix" -> PayloadShape.Position
    "keelson.TimestampedString" -> PayloadShape.Text
    else -> PayloadShape.Undecoded
}

/** What the tab knows about one topic, for the "seen on the bus" list and the card pickers. */
data class TopicInfo(
    val topic: Topic,
    val shape: PayloadShape,
    val count: Long,
    val lastArrivalMillis: Long,
    /** Samples that arrived but would not decode as the subject's declared type. */
    val undecodable: Long,
)

/** A three-axis vector's recent history, one window per axis. */
data class VectorWindow(val x: SampleWindow, val y: SampleWindow, val z: SampleWindow)

/**
 * An immutable pull of everything the cards read, taken on the screen's ticker.
 *
 * Cheap on purpose: windows are copied only for topics a card has asked for ([MonitorStore.snapshot]'s
 * `wanted`), the rest carry just their latest value — the same reason `liveText` is kept off
 * `LiveSnapshot`.
 */
data class MonitorSnapshot(
    val nowMillis: Long = System.currentTimeMillis(),
    val topics: List<TopicInfo> = emptyList(),
    val scalars: Map<Topic, SampleWindow> = emptyMap(),
    val latestScalar: Map<Topic, Float> = emptyMap(),
    val vectors: Map<Topic, VectorWindow> = emptyMap(),
    val tracks: Map<Topic, List<TrackPoint>> = emptyMap(),
    val texts: Map<Topic, String> = emptyMap(),
) {
    fun info(topic: Topic): TopicInfo? = topics.firstOrNull { it.topic == topic }
    fun topicsOf(subject: String): List<Topic> = topics.map { it.topic }.filter { it.subject == subject }
}

/**
 * Samples from the watched entity, held for the Monitor tab to pull.
 *
 * **Pulled, never pushed** — the rule `LiveSampleStore` exists to keep. A replay can push hundreds of
 * samples a second over the stream and none of them may drive recomposition; the screen takes a
 * [snapshot] on a 5 Hz ticker instead. The rings are the live store's own ([SampleRing], [TrackRing]),
 * which are `synchronized` for the visibility reason recorded there: the stream is read on
 * `Dispatchers.IO` and Compose reads on Main.
 *
 * Arrival time, not payload time, goes into the rings — for the reason `SubjectSink.record` gives,
 * made stronger here: a replayed payload is stamped with the hour the recording was made.
 */
class MonitorStore(private val capacity: Int = DEFAULT_CAPACITY) {

    private class Entry(val shape: PayloadShape) {
        @Volatile var count = 0L
        @Volatile var last = 0L
        @Volatile var bad = 0L
    }

    private val entries = ConcurrentHashMap<Topic, Entry>()
    private val scalars = ConcurrentHashMap<Topic, SampleRing>()
    private val vectors = ConcurrentHashMap<Topic, Array<SampleRing>>()
    private val tracks = ConcurrentHashMap<Topic, TrackRing>()
    private val texts = ConcurrentHashMap<Topic, String>()

    /** Take one sample off the stream. Samples for other entities or non-pubsub keys are ignored. */
    fun accept(sample: RemoteSample) {
        val (subject, source) = pubsubSubjectAndSource(sample.key) ?: return
        // `@target/...` keys describe somebody else (an AIS target), never the watched entity itself.
        if ("/@target" in "/$source") return
        val topic = Topic(subject, source)
        val entry = entries.getOrPut(topic) { Entry(shapeOf(subject)) }
        entry.count++
        entry.last = sample.receivedAtMillis
        if (!decodeInto(topic, entry.shape, sample)) entry.bad++
    }

    private fun decodeInto(topic: Topic, shape: PayloadShape, sample: RemoteSample): Boolean {
        if (shape == PayloadShape.Undecoded) return true
        val payload = unwrapEnvelope(sample.bytes)?.payload ?: return false
        val t = sample.receivedAtMillis
        return runCatching {
            when (shape) {
                PayloadShape.Scalar -> {
                    val value = scalarOf(topic.subject, payload) ?: return false
                    scalars.getOrPut(topic) { SampleRing(capacity) }.append(t, value)
                }
                PayloadShape.Vector -> {
                    val v = Decomposed3DVector.parseFrom(payload).vector
                    val rings = vectors.getOrPut(topic) { Array(3) { SampleRing(capacity) } }
                    rings[0].append(t, v.x.toFloat())
                    rings[1].append(t, v.y.toFloat())
                    rings[2].append(t, v.z.toFloat())
                }
                PayloadShape.Position -> {
                    val fix = LocationFix.parseFrom(payload)
                    // proto3 cannot tell an absent double from 0, and one fix at 0°N 0°E rescales the
                    // chart to the Atlantic — the rule McapTrack already follows.
                    if (fix.latitude == 0.0 && fix.longitude == 0.0) return true
                    tracks.getOrPut(topic) { TrackRing(TRACK_CAPACITY) }
                        .append(TrackPoint(fix.latitude, fix.longitude, null, null, t))
                }
                PayloadShape.Text -> texts[topic] = TimestampedString.parseFrom(payload).value
                PayloadShape.Undecoded -> Unit
            }
            true
        }.getOrDefault(false)
    }

    /** Every topic seen so far, sorted — cheap, for resolving the cards' inputs before a [snapshot]. */
    fun topics(): List<Topic> = entries.keys.sorted()

    /**
     * Pull what the cards need. `wanted` names the topics whose full windows are copied; everything
     * else carries only its latest value.
     */
    fun snapshot(wanted: Set<Topic>, nowMillis: Long = System.currentTimeMillis()): MonitorSnapshot {
        val infos = entries.map { (topic, e) -> TopicInfo(topic, e.shape, e.count, e.last, e.bad) }
            .sortedBy { it.topic }
        return MonitorSnapshot(
            nowMillis = nowMillis,
            topics = infos,
            scalars = scalars.filterKeys { it in wanted }.mapValues { it.value.snapshot() },
            latestScalar = scalars.mapNotNull { (k, r) -> r.latest()?.let { k to it } }.toMap(),
            vectors = vectors.filterKeys { it in wanted }
                .mapValues { (_, r) -> VectorWindow(r[0].snapshot(), r[1].snapshot(), r[2].snapshot()) },
            tracks = tracks.filterKeys { it in wanted }.mapValues { it.value.snapshot() },
            texts = HashMap(texts),
        )
    }

    /** Forget everything — the entity changed, and one entity's samples must not draw as another's. */
    fun clear() {
        entries.clear()
        scalars.clear()
        vectors.clear()
        tracks.clear()
        texts.clear()
    }

    companion object {
        /** Per scalar topic. At a replay's 10 Hz that is ~14 minutes; at an IMU's 100 Hz, 80 s. */
        const val DEFAULT_CAPACITY = 8192
        const val TRACK_CAPACITY = 4096
    }
}

/** One numeric reading out of whichever primitive the subject declares, as a float. */
internal fun scalarOf(subject: String, payload: ByteArray): Float? =
    when (remoteSubjectTypes[subject]) {
        "keelson.TimestampedFloat" -> TimestampedFloat.parseFrom(payload).value
        // FloatPeriod shares TimestampedFloat's field numbers for timestamp and value.
        "keelson.TimestampedFloatPeriod" -> TimestampedFloat.parseFrom(payload).value
        "keelson.TimestampedDouble" -> TimestampedDouble.parseFrom(payload).value.toFloat()
        "keelson.TimestampedInt" -> TimestampedInt.parseFrom(payload).value.toFloat()
        "keelson.TimestampedInt64" -> TimestampedInt64.parseFrom(payload).value.toFloat()
        "keelson.TimestampedBool" -> if (TimestampedBool.parseFrom(payload).value) 1f else 0f
        else -> null
    }
