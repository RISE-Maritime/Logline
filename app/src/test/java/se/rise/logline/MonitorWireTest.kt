package se.rise.logline

import foxglove.LocationFixOuterClass.LocationFix
import keelson.Decomposed3DVectorOuterClass.Decomposed3DVector
import keelson.Primitives.TimestampedFloat
import foxglove.Vector3OuterClass.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.keelson.enclose
import se.rise.logline.keelson.unwrapEnvelope
import se.rise.logline.monitor.MonitorStore
import se.rise.logline.monitor.PayloadShape
import se.rise.logline.monitor.RemoteSample
import se.rise.logline.monitor.SseEvent
import se.rise.logline.monitor.SseParser
import se.rise.logline.monitor.Topic
import se.rise.logline.monitor.monitorBaseUrl
import se.rise.logline.monitor.monitorKeyExpr
import se.rise.logline.monitor.parseRestSample
import se.rise.logline.monitor.streamUrl
import java.time.Instant

class MonitorWireTest {

    /**
     * Captured verbatim from the rise router's REST plugin (`curl -N` with `Accept: text/event-stream`
     * over every entity's pubsub keys) on 2026-09-19, from the slipway simulator. The value is base64 of a
     * `core.Envelope` around a `keelson.TimestampedFloat` of 90.00745.
     */
    private val captured = listOf(
        "event: PUT",
        "data: {\"key\":\"rise/@v0/slipway-usv-alpha/pubsub/heading_true_north_deg/truth\"," +
            "\"value\":\"CgwI2pu51QYQibzSpQMSEwoMCNqbudUGEMq+rO0CFdADtEI=\",\"encoding\":\"zenoh/bytes\"," +
            "\"timestamp\":\"7687167216474134592/b7fc0b8a264ffe6d35e221a6b312c704\"}",
        "",
    )

    private fun feedAll(lines: List<String>): List<SseEvent> {
        val parser = SseParser()
        return lines.mapNotNull(parser::feed)
    }

    @Test
    fun `a captured router event decodes to its key and float`() {
        val events = feedAll(captured)
        assertEquals(1, events.size)
        val sample = parseRestSample(events.single(), 42L)
        assertNotNull(sample)
        assertEquals("rise/@v0/slipway-usv-alpha/pubsub/heading_true_north_deg/truth", sample!!.key)
        val payload = unwrapEnvelope(sample.bytes)!!.payload
        assertEquals(90.00745f, TimestampedFloat.parseFrom(payload).value, 1e-4f)
    }

    @Test
    fun `keep-alive comments and multi-line data follow the SSE rules`() {
        val events = feedAll(listOf(": ping", "event: x", "data: a", "data:b", "", "", ": again"))
        assertEquals(listOf(SseEvent("x", "a\nb")), events)
    }

    @Test
    fun `an event with no data is not an event`() {
        assertEquals(emptyList<SseEvent>(), feedAll(listOf("event: PUT", "")))
    }

    @Test
    fun `a non-PUT or malformed event yields nothing`() {
        assertNull(parseRestSample(SseEvent("DELETE", "{\"key\":\"a\",\"value\":\"\"}"), 0))
        assertNull(parseRestSample(SseEvent("PUT", "not json"), 0))
        assertNull(parseRestSample(SseEvent("PUT", "{\"key\":\"a\"}"), 0))
    }

    @Test
    fun `the base url comes from the configured router unless set`() {
        assertEquals("http://example.org:8000", monitorBaseUrl("", listOf("tls/example.org:7447")))
        assertEquals("http://10.0.0.5:8000", monitorBaseUrl(" ", listOf("tcp/10.0.0.5:7447")))
        assertEquals("http://[fe80::1]:8000", monitorBaseUrl("", listOf("quic/[fe80::1]:7447")))
        assertEquals("http://h:9", monitorBaseUrl("http://h:9/", listOf("tcp/x:1")))
        assertNull(monitorBaseUrl("", emptyList()))
    }

    @Test
    fun `the key expression keeps @v0 verbatim`() {
        val key = monitorKeyExpr("rise", "case")
        assertEquals("rise/@v0/case/pubsub/**", key)
        assertEquals("http://h:8000/rise/@v0/case/pubsub/**", streamUrl("http://h:8000/", key))
    }

    private fun sample(key: String, payload: ByteArray, at: Long = 1_000L) =
        RemoteSample(key, at, enclose(payload, Instant.ofEpochSecond(1)))

    @Test
    fun `the store sorts samples by subject and source, including nested sources`() {
        val store = MonitorStore()
        val float = TimestampedFloat.newBuilder().setValue(3.5f).build().toByteArray()
        store.accept(sample("rise/@v0/case/pubsub/speed_over_ground_knots/gnss/0", float))
        store.accept(sample("rise/@v0/case/pubsub/speed_over_ground_knots/gnss/0", float, 2_000L))

        val topic = Topic("speed_over_ground_knots", "gnss/0")
        val snap = store.snapshot(setOf(topic), nowMillis = 3_000L)
        assertEquals(2L, snap.info(topic)!!.count)
        assertEquals(PayloadShape.Scalar, snap.info(topic)!!.shape)
        assertEquals(3.5f, snap.latestScalar[topic])
        assertEquals(listOf(1_000L, 2_000L), snap.scalars[topic]!!.timesMillis.toList())
    }

    @Test
    fun `vectors, positions and undecodable payloads are each handled`() {
        val store = MonitorStore()
        val vec = Decomposed3DVector.newBuilder()
            .setVector(Vector3.newBuilder().setX(1.0).setY(2.0).setZ(9.8)).build().toByteArray()
        store.accept(sample("rise/@v0/case/pubsub/linear_acceleration_mpss/imu", vec))
        val fix = LocationFix.newBuilder().setLatitude(57.7).setLongitude(11.9).build().toByteArray()
        store.accept(sample("rise/@v0/case/pubsub/location_fix/gnss", fix))
        // Null Island is proto3's absent position, never drawn.
        store.accept(sample("rise/@v0/case/pubsub/location_fix/gnss", LocationFix.getDefaultInstance().toByteArray()))
        store.accept(RemoteSample("rise/@v0/case/pubsub/roll_deg/imu", 0, byteArrayOf(0x7f, 0x7f)))

        val acc = Topic("linear_acceleration_mpss", "imu")
        val pos = Topic("location_fix", "gnss")
        val snap = store.snapshot(setOf(acc, pos))
        assertEquals(9.8f, snap.vectors[acc]!!.z.latest!!, 1e-6f)
        assertEquals(1, snap.tracks[pos]!!.size)
        assertEquals(1L, snap.info(Topic("roll_deg", "imu"))!!.undecodable)
    }

    @Test
    fun `target keys are somebody else and are ignored`() {
        val store = MonitorStore()
        val float = TimestampedFloat.newBuilder().setValue(1f).build().toByteArray()
        store.accept(sample("rise/@v0/case/pubsub/speed_over_ground_knots/ais/@target/mmsi_1", float))
        assertTrue(store.snapshot(emptySet()).topics.isEmpty())
    }

    @Test
    fun `unwrap reads back what enclose wrote`() {
        val enclosed = unwrapEnvelope(enclose(byteArrayOf(9, 8), Instant.ofEpochMilli(1_234L)))!!
        assertEquals(1_234L, enclosed.enclosedAtMillis)
        assertEquals(listOf<Byte>(9, 8), enclosed.payload.toList())
    }
}
