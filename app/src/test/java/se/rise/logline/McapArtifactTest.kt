package se.rise.logline

import se.rise.logline.record.McapWriter
import org.junit.Test
import java.io.File

/**
 * Writes a real MCAP file to disk so it can be validated with keelson's own reader, which is the only
 * check that proves interoperability. Not an assertion test — the assertions live in McapWriterTest.
 */
class McapArtifactTest {
    @Test
    fun `emit a sample recording for external validation`() {
        val target = System.getenv("MCAP_ARTIFACT") ?: return
        val descriptor = File("src/main/assets/keelson_payloads.desc").readBytes()
        File(target).outputStream().use { out ->
            val w = McapWriter(out)
            w.start()
            val locSchema = w.addSchema("foxglove.LocationFix", "protobuf", descriptor)
            val locChannel = w.addChannel(
                "rise/@v0/pixel_6/pubsub/location_fix/phone", locSchema, "protobuf"
            )
            // A real serialised foxglove.LocationFix: lat 57.435949, lon 12.032747, alt 65.9
            val fix = foxglove.LocationFixOuterClass.LocationFix.newBuilder()
                .setLatitude(57.435949).setLongitude(12.032747).setAltitude(65.9)
                .setFrameId("phone")
                .build()
            w.writeMessage(locChannel, 1, 1_700_000_000_000_000_000L, 1_699_999_999_000_000_000L, fix.toByteArray())

            val fSchema = w.addSchema("keelson.TimestampedFloat", "protobuf", descriptor)
            val fChannel = w.addChannel(
                "rise/@v0/pixel_6/pubsub/air_pressure_pa/phone", fSchema, "protobuf"
            )
            val pressure = keelson.Primitives.TimestampedFloat.newBuilder().setValue(100438.8f).build()
            w.writeMessage(fChannel, 1, 1_700_000_001_000_000_000L, 1_700_000_000_500_000_000L, pressure.toByteArray())

            // An operator annotation. Included because `foxglove.Log` is the one payload here that no
            // sensor produces, so nothing else would catch its descriptor going missing — and a reader
            // that cannot resolve the schema shows a channel of undecodable bytes rather than an error.
            val logSchema = w.addSchema("foxglove.Log", "protobuf", descriptor)
            val logChannel = w.addChannel(
                "rise/@v0/pixel_6/pubsub/log_message/phone", logSchema, "protobuf"
            )
            val mark = foxglove.LogOuterClass.Log.newBuilder()
                .setLevel(foxglove.LogOuterClass.Log.Level.WARNING)
                .setMessage("Wake encountered")
                .setName("incident")
                .build()
            w.writeMessage(logChannel, 1, 1_700_000_002_000_000_000L, 1_700_000_002_000_000_000L, mark.toByteArray())
            w.finish()
        }
    }
}
