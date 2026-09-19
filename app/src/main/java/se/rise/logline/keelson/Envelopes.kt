package se.rise.logline.keelson

import com.google.protobuf.Duration
import com.google.protobuf.Timestamp
import core.EnvelopeOuterClass.Envelope

fun enclose(payload: ByteArray, now: java.time.Instant = java.time.Instant.now()): ByteArray {
    val ts = Timestamp.newBuilder()
        .setSeconds(now.epochSecond)
        .setNanos(now.nano)
        .build()
    return Envelope.newBuilder()
        .setEnclosedAt(ts)
        .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
        .build()
        .toByteArray()
}

fun protoTimestamp(now: java.time.Instant = java.time.Instant.now()): Timestamp =
    Timestamp.newBuilder().setSeconds(now.epochSecond).setNanos(now.nano).build()

/**
 * Builds a payload `timestamp` from nanoseconds since the Unix epoch.
 *
 * Uses floor division so a pre-epoch instant yields a negative `seconds` with a non-negative `nanos`,
 * which is what protobuf's `Timestamp` requires; plain `/` and `%` would produce a negative `nanos`.
 */
/**
 * Builds a `google.protobuf.Duration` from milliseconds.
 *
 * **Not the same rule as [protoTimestamp].** A `Timestamp` wants a non-negative `nanos` even when
 * `seconds` is negative, so it floors; a `Duration` requires the two to carry the **same sign**, which
 * is why this truncates instead. Nothing here produces a negative duration today — an uptime cannot
 * run backwards — but a helper that quietly produced an invalid message for one would be a trap.
 */
fun protoDuration(millis: Long): Duration =
    Duration.newBuilder()
        .setSeconds(millis / 1_000L)
        .setNanos(((millis % 1_000L) * 1_000_000L).toInt())
        .build()

fun protoTimestamp(epochNanos: Long): Timestamp =
    Timestamp.newBuilder()
        .setSeconds(Math.floorDiv(epochNanos, 1_000_000_000L))
        .setNanos(Math.floorMod(epochNanos, 1_000_000_000L).toInt())
        .build()

/** An envelope opened: when it was enclosed, and the payload inside it. */
class Enclosed(val enclosedAtMillis: Long, val payload: ByteArray)

/**
 * Opens a `core.Envelope`, or null when the bytes are not one.
 *
 * The one shared unwrap. Everything on the wire is enveloped, but the only readers this app had were
 * private to the checklist codec and the platform document decoder; the monitor reads every subject
 * another entity publishes and needs it in the open. Null on any parse failure, since a foreign
 * publisher's bytes are exactly the input that should not throw.
 */
fun unwrapEnvelope(bytes: ByteArray): Enclosed? = runCatching {
    val envelope = Envelope.parseFrom(bytes)
    val at = envelope.enclosedAt
    Enclosed(at.seconds * 1_000L + at.nanos / 1_000_000L, envelope.payload.toByteArray())
}.getOrNull()
