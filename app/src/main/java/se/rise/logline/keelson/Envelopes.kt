package se.rise.logline.keelson

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
fun protoTimestamp(epochNanos: Long): Timestamp =
    Timestamp.newBuilder()
        .setSeconds(Math.floorDiv(epochNanos, 1_000_000_000L))
        .setNanos(Math.floorMod(epochNanos, 1_000_000_000L).toInt())
        .build()
