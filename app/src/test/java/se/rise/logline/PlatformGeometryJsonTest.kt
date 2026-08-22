package se.rise.logline

import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.EulerDeg
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.PlatformType
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.PlatformZero
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.toPlatformGeometryJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exported document, pinned character for character.
 *
 * It is not ours to restyle: `platform-geometry2keelson.py --config <this file>` validates it against
 * `keelson/connectors/platform/config-schema.json`, which is `additionalProperties: false` at every
 * level — one stray key and the connector refuses to start. The last test walks every key the writer
 * emits against a list transcribed from that schema, so a field added here without a home upstream
 * fails at build time rather than in front of somebody's platform.
 */
class PlatformGeometryJsonTest {

    private val ssrs18 = PlatformCalibration(
        name = "SSRS18",
        entityId = "ssrs18",
        parentFrameId = "ssrs18-frame-ccrp",
        platformType = PlatformType.VESSEL,
        description = "Small USV test platform",
        lengthOverAllM = 1.8,
        breadthOverAllM = 0.45,
        sensors = listOf(
            SensorMount(
                label = "Rutx GNSS antenna",
                frameId = "ssrs18-frame-gnss-rutx",
                sensorType = SensorType.GNSS,
                translation = Vec3M(0.27, 0.0, 0.0),
            ),
            SensorMount(
                label = "Starboard camera",
                frameId = "ssrs18-frame-camera-2",
                sensorType = SensorType.CAMERA,
                translation = Vec3M(0.075, 0.405, -0.04),
                rotation = EulerDeg(yaw = 90.0, pitch = 0.0, roll = 0.0),
                capture = CaptureMethod.GNSS_AVERAGE,
                accuracyM = 3.2,
                capturedAtEpochMillis = 1_700_000_000_000L,
            ),
        ),
        zero = PlatformZero(
            latitude = 57.708912345,
            longitude = 11.974560,
            altitudeM = 12.5,
            accuracyM = 3.4,
            scatterM = 0.42,
            headingDeg = 35.0,
            headingSource = HeadingSource.BASELINE,
            capture = CaptureMethod.GNSS_AVERAGE,
            samples = 60,
            capturedAtEpochMillis = 1_700_000_000_000L,
        ),
        updatedAtEpochMillis = 1_700_000_001_000L,
    )

    @Test
    fun `the exported document is exactly upstream's shape`() {
        val expected = """
            {
              "platform_type": "vessel",
              "description": "Small USV test platform",
              "name": "SSRS18",
              "length_over_all_m": 1.8,
              "breadth_over_all_m": 0.45,
              "ccrp_m": { "x": 0, "y": 0, "z": 0 },
              "frame_transforms": [
                {
                  "parent_frame_id": "ssrs18-frame-ccrp",
                  "child_frame_id": "ssrs18-frame-gnss-rutx",
                  "sensor_type": "gnss",
                  "sensor_description": "Rutx GNSS antenna",
                  "translation_m": { "x": 0.27, "y": 0, "z": 0 },
                  "rotation_deg": { "yaw": 0, "pitch": 0, "roll": 0 }
                },
                {
                  "parent_frame_id": "ssrs18-frame-ccrp",
                  "child_frame_id": "ssrs18-frame-camera-2",
                  "sensor_type": "camera",
                  "sensor_description": "Starboard camera",
                  "translation_m": { "x": 0.075, "y": 0.405, "z": -0.04 },
                  "rotation_deg": { "yaw": 90, "pitch": 0, "roll": 0 }
                }
              ]
            }
        """.trimIndent() + "\n"
        assertEquals(expected, ssrs18.toPlatformGeometryJson())
    }

    @Test
    fun `the exported document carries no provenance at all`() {
        // The whole reason there are two variants. A `calibration` block here would fail the
        // connector's schema, which forbids unknown keys.
        val json = ssrs18.toPlatformGeometryJson()
        assertFalse(json.contains("calibration"))
        assertFalse(json.contains("accuracy_m"))
        assertFalse(json.contains("latitude"))
    }

    @Test
    fun `the wire document carries the zero, the heading and how each number was got`() {
        val json = ssrs18.toPlatformGeometryJson(provenance = true)
        assertTrue(json.contains("\"calibration\""))
        assertTrue(json.contains("\"latitude\": 57.708912345"))
        assertTrue(json.contains("\"heading_deg\": 35"))
        assertTrue(json.contains("\"heading_source\": \"baseline\""))
        assertTrue(json.contains("\"scatter_m\": 0.42"))
        assertTrue(json.contains("\"samples\": 60"))
        assertTrue(json.contains("\"capture\": \"gnss_average\""))
        assertTrue(json.contains("\"accuracy_m\": 3.2"))
        assertTrue(json.contains("\"updated_at_ms\": 1700000001000"))
        // Still the same platform-geometry document underneath it.
        assertTrue(json.contains("\"frame_transforms\""))
    }

    @Test
    fun `a typed offset states no accuracy rather than a perfect one`() {
        // proto3's absent-versus-zero problem in JSON form: `"accuracy_m": 0` would claim the offset
        // was measured perfectly, when in fact it was not measured at all.
        val typed = ssrs18.copy(
            sensors = listOf(
                SensorMount(
                    label = "Mast light",
                    frameId = "ssrs18-frame-mast",
                    sensorType = SensorType.OTHER,
                    translation = Vec3M(0.0, 0.0, -2.4),
                )
            ),
        )
        val sensorBlock = typed.toPlatformGeometryJson(provenance = true)
            .substringAfter("\"sensors\": [")
        assertTrue(sensorBlock.contains("\"capture\": \"manual\""))
        assertFalse(sensorBlock.substringBefore("]").contains("accuracy_m"))
    }

    @Test
    fun `absent optional fields are omitted, not written as null`() {
        val bare = PlatformCalibration.forName("Platform One").copy(
            sensors = listOf(
                SensorMount("A", "platform-one-frame-a", SensorType.OTHER, Vec3M(1.0, 0.0, 0.0))
            ),
        )
        val json = bare.toPlatformGeometryJson()
        assertFalse(json.contains("null"))
        assertFalse(json.contains("platform_type"))
        assertFalse(json.contains("length_over_all_m"))
        // The two-space indent is what makes this the *top-level* key: `sensor_description` is a
        // different field and the mount below legitimately has one.
        assertFalse(json.contains("\n  \"description\":"))
        assertTrue(json.contains("\"name\": \"Platform One\""))
    }

    @Test
    fun `a quote in a name cannot break the document`() {
        val awkward = ssrs18.copy(name = "The \"Sea\" Dog", sensors = emptyList())
        assertTrue(awkward.toPlatformGeometryJson().contains("\"name\": \"The \\\"Sea\\\" Dog\""))
    }

    @Test
    fun `rotations are folded into the range the schema allows`() {
        // The schema bounds every angle to -180..180. A typed 270 is a thing somebody can mean and
        // not a thing we may publish, so it becomes -90 — the same rotation.
        val spun = ssrs18.copy(
            sensors = listOf(
                SensorMount(
                    label = "Aft camera",
                    frameId = "ssrs18-frame-camera-aft",
                    sensorType = SensorType.CAMERA,
                    translation = Vec3M(-1.0, 0.0, 0.0),
                    rotation = EulerDeg(yaw = 270.0, pitch = -400.0, roll = 361.0),
                )
            ),
        )
        val json = spun.toPlatformGeometryJson()
        assertTrue(json.contains("\"yaw\": -90"))
        assertTrue(json.contains("\"pitch\": -40"))
        assertTrue(json.contains("\"roll\": 1"))
    }

    @Test
    fun `an empty platform still emits a valid frame_transforms array`() {
        val json = PlatformCalibration.forName("Empty").toPlatformGeometryJson()
        assertTrue(json.contains("\"frame_transforms\": []"))
    }

    @Test
    fun `every key the writer emits exists in upstream's schema`() {
        // Transcribed from connectors/platform/config-schema.json. `additionalProperties: false` means
        // anything not on this list stops the connector dead.
        val allowed = setOf(
            "platform_type", "description", "name", "length_over_all_m", "breadth_over_all_m",
            "ccrp_m", "mmsi_number", "imo_number", "call_sign", "operational_limits",
            "vessel_outlines", "frame_transforms",
            "parent_frame_id", "child_frame_id", "sensor_type", "sensor_description",
            "translation_m", "rotation_deg",
            "x", "y", "z", "yaw", "pitch", "roll",
        )
        val emitted = Regex("\"([a-z_0-9]+)\":").findAll(ssrs18.toPlatformGeometryJson())
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(emptySet<String>(), emitted - allowed)
    }
}
