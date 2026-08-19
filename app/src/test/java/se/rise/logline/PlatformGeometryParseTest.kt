package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.EulerDeg
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.PlatformType
import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.calibrate.RigZero
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.parsePlatformDocument
import se.rise.logline.calibrate.parsePlatformGeometry
import se.rise.logline.calibrate.toPlatformGeometryJson
import se.rise.logline.calibrate.toRegistryEntryJson
import se.rise.logline.calibrate.toStoredJson

/**
 * The reader, against documents this app did not write.
 *
 * The writer is pinned character-for-character by [PlatformGeometryJsonTest]; this is the other half,
 * and it matters more, because these are the only bytes in the app that come from somewhere else — an
 * exported file, a crowsnest registry, a `configuration_json` off the bus.
 */
class PlatformGeometryParseTest {

    private fun rig() = RigCalibration(
        name = "SSRS18",
        entityId = "ssrs18",
        parentFrameId = "ssrs18-frame-ccrp",
        platformType = PlatformType.VESSEL,
        description = "Small USV test platform",
        lengthOverAllM = 1.8,
        breadthOverAllM = 0.45,
        ccrp = Vec3M(0.1, 0.0, -0.2),
        zero = RigZero(
            latitude = 57.708912,
            longitude = 11.974560,
            altitudeM = 12.5,
            accuracyM = 3.4,
            verticalAccuracyM = 6.1,
            scatterM = 0.42,
            headingDeg = 35.0,
            headingSource = HeadingSource.BASELINE,
            capture = CaptureMethod.GNSS_AVERAGE,
            samples = 60,
            capturedAtEpochMillis = 1_700_000_000_000L,
        ),
        sensors = listOf(
            SensorMount(
                label = "Ouster OS lidar",
                frameId = "ssrs18-frame-lidar",
                sensorType = SensorType.LIDAR,
                translation = Vec3M(0.22, 0.0, -0.35),
                rotation = EulerDeg(yaw = 90.0, pitch = 0.0, roll = -175.0),
                capture = CaptureMethod.GNSS_AVERAGE,
                accuracyM = 3.2,
                capturedAtEpochMillis = 1_700_000_000_500L,
            ),
            SensorMount(
                label = "Rutx GNSS antenna",
                frameId = "ssrs18-frame-gnss",
                sensorType = SensorType.GNSS,
                translation = Vec3M(0.27, 0.0, 0.0),
            ),
        ),
        updatedAtEpochMillis = 1_700_000_001_000L,
    )

    /** The stored form is the only one that has to come back whole — it is what the library holds. */
    @Test
    fun `the stored form round-trips exactly`() {
        assertEquals(rig(), parsePlatformGeometry(rig().toStoredJson()))
    }

    /**
     * The strict export drops provenance on purpose, and the reader must not invent it back.
     *
     * `config-schema.json` is `additionalProperties: false`, so the exported file cannot carry how a
     * number was arrived at. Reading one back has to say "typed, no accuracy" — which is what an
     * unannotated document means — rather than guessing that an offset was captured.
     */
    @Test
    fun `the strict export reads back as geometry with no provenance`() {
        val read = parsePlatformGeometry(rig().toPlatformGeometryJson(), fallbackEntityId = "ssrs18")!!

        assertEquals("ssrs18", read.entityId)
        assertEquals("ssrs18-frame-ccrp", read.parentFrameId)
        assertEquals(rig().sensors.map { it.frameId }, read.sensors.map { it.frameId })
        assertEquals(rig().sensors.map { it.translation }, read.sensors.map { it.translation })
        assertNull("the export carries no zero, so none may be read", read.zero)
        assertTrue(read.sensors.all { it.capture == CaptureMethod.MANUAL })
        assertTrue(read.sensors.all { it.accuracyM == null })
    }

    /**
     * The entity id is the registry's *key*, not a field inside the entry — so a bare document has to
     * be told what it is. Without the fallback an imported registry entry would be nameless.
     */
    @Test
    fun `a document with no entity id takes the one it is filed under`() {
        val read = parsePlatformGeometry(rig().toRegistryEntryJson("rise"), fallbackEntityId = "ssrs18")!!
        assertEquals("ssrs18", read.entityId)

        // ...and with nothing to fall back on, the name is slugified rather than the read failing.
        assertEquals("ssrs18", parsePlatformGeometry(rig().toRegistryEntryJson("rise"))!!.entityId)
    }

    /** `realm`, `queryables`, `data_streams` and the rest are crowsnest's; they parse to nothing here. */
    @Test
    fun `a crowsnest registry entry parses to the geometry it shares with upstream`() {
        val entry = """
            {
              "platform_type": "vessel",
              "description": "SF18 small USV research platform.",
              "name": "SF18",
              "realm": "rise",
              "length_over_all_m": 1.8,
              "breadth_over_all_m": 0.45,
              "mmsi_number": 265538760,
              "imo_number": 0,
              "call_sign": "SLXX",
              "operational_limits": { "max_speed_knots": 6.0 },
              "ccrp_m": { "x": 0.0, "y": 0.0, "z": 0.0 },
              "queryables": [
                { "key_expression": "rise/@v0/sf18/@rpc/get_config/connector_platform" }
              ],
              "data_streams": [
                { "key_expression": "rise/@v0/sf18/pubsub/location_fix/ardusimple/GGA",
                  "expected_hz": 20.0, "liveliness": true }
              ],
              "camera_calibrations": [ { "frame_id": "sf18-demo-frame-camera-axis-1" } ],
              "frame_transforms": [
                {
                  "parent_frame_id": "sf18-demo-frame-ccrp",
                  "child_frame_id": "sf18-demo-frame-gnss-rutx",
                  "sensor_type": "gnss",
                  "sensor_description": "Rutx GNSS antenna",
                  "translation_m": { "x": 0.27, "y": 0, "z": 0 },
                  "rotation_deg": { "yaw": 0, "pitch": 0, "roll": 0 }
                }
              ]
            }
        """.trimIndent()

        val read = parsePlatformGeometry(entry, fallbackEntityId = "sf18")!!

        assertEquals("sf18", read.entityId)
        assertEquals("SF18", read.name)
        assertEquals(PlatformType.VESSEL, read.platformType)
        assertEquals(1.8, read.lengthOverAllM!!, 1e-9)
        // Taken from the transforms rather than defaulted: the rig's own frame naming is `-demo-`,
        // which `defaultParentFrameId` would never have produced.
        assertEquals("sf18-demo-frame-ccrp", read.parentFrameId)
        assertEquals(
            listOf(SensorMount(
                label = "Rutx GNSS antenna",
                frameId = "sf18-demo-frame-gnss-rutx",
                sensorType = SensorType.GNSS,
                translation = Vec3M(0.27, 0.0, 0.0),
            )),
            read.sensors,
        )
    }

    /**
     * The older `keelson-platforms` documents, which use arrays rather than objects.
     *
     * `platforms/sf18/config.json` is one of these and predates `translation_m`/`rotation_deg`. They
     * are real files somebody will try to import, so refusing them would be refusing the data this
     * feature exists for. The array order is squaternion's — roll, pitch, yaw — and reading it
     * yaw-first would silently roll a rig onto its side.
     */
    @Test
    fun `the legacy array form of a transform is understood, roll first`() {
        val legacy = """
            {
              "vessel_name": "storakrabban",
              "length_over_all_m": 2,
              "name": "Stora Krabban",
              "frame_transforms": [
                {
                  "parent_frame_id": "storakrabban-epa-frame-ccrp",
                  "child_frame_id": "storakrabban-epa-frame-gnss-anello",
                  "translation": [0.0, -0.14, -0.07],
                  "rotation": [-180.0, 0.0, 90.0]
                }
              ]
            }
        """.trimIndent()

        val read = parsePlatformGeometry(legacy, fallbackEntityId = "storakrabban")!!
        val mount = read.sensors.single()

        assertEquals(Vec3M(0.0, -0.14, -0.07), mount.translation)
        assertEquals(EulerDeg(yaw = 90.0, pitch = 0.0, roll = -180.0), mount.rotation)
        assertEquals(SensorType.OTHER, mount.sensorType)
    }

    /** The object form of the same older shape, which the `usv` platform uses. */
    @Test
    fun `the legacy object form without the _m and _deg suffixes is understood`() {
        val legacy = """
            {
              "name": "Stora Krabban",
              "frame_transforms": [
                {
                  "parent_frame_id": "storakrabban-epa-frame-ccrp",
                  "child_frame_id": "storakrabban-epa-frame-radar-aptiv",
                  "translation": { "x": 0.08, "y": 0, "z": 0.105 },
                  "rotation": { "roll": 0, "pitch": 0, "yaw": 0 }
                }
              ]
            }
        """.trimIndent()

        val read = parsePlatformGeometry(legacy, fallbackEntityId = "storakrabban")!!
        assertEquals(Vec3M(0.08, 0.0, 0.105), read.sensors.single().translation)
    }

    /**
     * A half-written transform is skipped, not read as zeros.
     *
     * Zeros would put the sensor exactly at the rig's origin — a plausible-looking position that
     * nothing downstream could tell from a real measurement. The same stance the preferences reader
     * takes, and for the same reason.
     */
    @Test
    fun `a transform missing a translation component is skipped rather than read as the origin`() {
        val document = """
            {
              "name": "Rig",
              "frame_transforms": [
                { "parent_frame_id": "rig-frame-ccrp", "child_frame_id": "rig-frame-a",
                  "translation_m": { "x": 1.0, "y": 2.0 } },
                { "parent_frame_id": "rig-frame-ccrp", "child_frame_id": "rig-frame-b",
                  "translation_m": { "x": 1.0, "y": 2.0, "z": 3.0 } },
                { "parent_frame_id": "rig-frame-ccrp",
                  "translation_m": { "x": 1.0, "y": 2.0, "z": 3.0 } }
              ]
            }
        """.trimIndent()

        val read = parsePlatformGeometry(document, fallbackEntityId = "rig")!!
        assertEquals(listOf("rig-frame-b"), read.sensors.map { it.frameId })
    }

    /** Half a position is no position: a lone latitude would put the rig on the Greenwich meridian. */
    @Test
    fun `a zero missing its longitude is dropped and the rig survives without it`() {
        val document = """
            {
              "entity_id": "rig", "name": "Rig",
              "frame_transforms": [],
              "calibration": { "zero": { "latitude": 57.7, "heading_deg": 12.0 } }
            }
        """.trimIndent()

        val read = parsePlatformGeometry(document)!!
        assertNull(read.zero)
        assertEquals("Rig", read.name)
    }

    /** A crowsnest `platform_registry.json` is an object of platforms, and reads as all of them. */
    @Test
    fun `a registry of several platforms reads as several rigs`() {
        val registry = """
            {
              "sf18":  { "name": "SF18",  "realm": "rise", "frame_transforms": [] },
              "gota":  { "name": "Gota",  "realm": "rise", "frame_transforms": [] }
            }
        """.trimIndent()

        val read = parsePlatformDocument(registry)
        assertEquals(listOf("sf18", "gota"), read.map { it.entityId })
        assertEquals(listOf("SF18", "Gota"), read.map { it.name })
    }

    /** ...and one platform, handed to the same function, is one rig rather than a registry of fields. */
    @Test
    fun `a single platform document is not mistaken for a registry`() {
        assertEquals(listOf("ssrs18"), parsePlatformDocument(rig().toStoredJson()).map { it.entityId })
    }

    /**
     * An entity id that is not one is refused, not repaired.
     *
     * It goes straight into `{realm}/@v0/{entity_id}/pubsub/...` and into this app's own navigation
     * routes, so a `/` in it adds a chunk to both. Slugifying would import a rig whose key no longer
     * matches the one the station that sent it uses, which is worse than not importing it.
     */
    @Test
    fun `a document with a malformed entity id is refused`() {
        fun withId(id: String) = """{ "entity_id": "$id", "name": "Rig", "frame_transforms": [] }"""

        assertNull(parsePlatformGeometry(withId("a/b")))
        assertNull(parsePlatformGeometry(withId("Upper")))
        assertNull(parsePlatformGeometry(withId("-leading")))
        assertNull(parsePlatformGeometry(withId("with space")))
        // ...and the shapes crowsnest's own registry uses all pass.
        assertEquals("sf18", parsePlatformGeometry(withId("sf18"))!!.entityId)
        assertEquals("drone_sim_01", parsePlatformGeometry(withId("drone_sim_01"))!!.entityId)
        assertEquals("stora-krabban", parsePlatformGeometry(withId("stora-krabban"))!!.entityId)
    }

    @Test
    fun `text that is not a JSON object reads as nothing rather than throwing`() {
        assertNull(parsePlatformGeometry("{ not json"))
        assertNull(parsePlatformGeometry("[1, 2, 3]"))
        assertNull(parsePlatformGeometry(""))
        assertEquals(emptyList<RigCalibration>(), parsePlatformDocument("null"))
    }

    /** A present-but-null key has to read the same as an absent one, not as the string "null". */
    @Test
    fun `an explicit JSON null reads as absent`() {
        val document = """
            { "entity_id": "rig", "name": "Rig", "description": null,
              "length_over_all_m": null, "platform_type": null, "frame_transforms": [] }
        """.trimIndent()

        val read = parsePlatformGeometry(document)!!
        assertEquals("", read.description)
        assertNull(read.lengthOverAllM)
        assertNull(read.platformType)
    }

    /** An unknown enum value falls back rather than failing the whole document. */
    @Test
    fun `an unknown sensor type or platform type falls back`() {
        val document = """
            {
              "entity_id": "rig", "name": "Rig", "platform_type": "submarine",
              "frame_transforms": [
                { "parent_frame_id": "rig-frame-ccrp", "child_frame_id": "rig-frame-sonar",
                  "sensor_type": "sonar", "translation_m": { "x": 0, "y": 0, "z": 0 } }
              ]
            }
        """.trimIndent()

        val read = parsePlatformGeometry(document)!!
        assertNull(read.platformType)
        assertEquals(SensorType.OTHER, read.sensors.single().sensorType)
    }
}
