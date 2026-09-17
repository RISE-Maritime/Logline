package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.EulerDeg
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.PlatformType
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.PlatformZero
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

    private fun platform() = PlatformCalibration(
        name = "Sealog",
        entityId = "sealog",
        parentFrameId = "sealog-frame-ccrp",
        platformType = PlatformType.VESSEL,
        description = "Small USV test platform",
        lengthOverAllM = 1.8,
        breadthOverAllM = 0.45,
        ccrp = Vec3M(0.1, 0.0, -0.2),
        zero = PlatformZero(
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
                frameId = "sealog-frame-lidar",
                sensorType = SensorType.LIDAR,
                translation = Vec3M(0.22, 0.0, -0.35),
                rotation = EulerDeg(yaw = 90.0, pitch = 0.0, roll = -175.0),
                capture = CaptureMethod.GNSS_AVERAGE,
                accuracyM = 3.2,
                capturedAtEpochMillis = 1_700_000_000_500L,
            ),
            SensorMount(
                label = "Rutx GNSS antenna",
                frameId = "sealog-frame-gnss",
                sensorType = SensorType.GNSS,
                translation = Vec3M(0.27, 0.0, 0.0),
            ),
        ),
        updatedAtEpochMillis = 1_700_000_001_000L,
    )

    /** The stored form is the only one that has to come back whole — it is what the library holds. */
    @Test
    fun `the stored form round-trips exactly`() {
        assertEquals(platform(), parsePlatformGeometry(platform().toStoredJson()))
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
        val read = parsePlatformGeometry(platform().toPlatformGeometryJson(), fallbackEntityId = "sealog")!!

        assertEquals("sealog", read.entityId)
        assertEquals("sealog-frame-ccrp", read.parentFrameId)
        assertEquals(platform().sensors.map { it.frameId }, read.sensors.map { it.frameId })
        assertEquals(platform().sensors.map { it.translation }, read.sensors.map { it.translation })
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
        val read = parsePlatformGeometry(platform().toRegistryEntryJson("rise"), fallbackEntityId = "sealog")!!
        assertEquals("sealog", read.entityId)

        // ...and with nothing to fall back on, the name is slugified rather than the read failing.
        assertEquals("sealog", parsePlatformGeometry(platform().toRegistryEntryJson("rise"))!!.entityId)
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
        // Taken from the transforms rather than defaulted: the platform's own frame naming is `-demo-`,
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
     * yaw-first would silently roll a platform onto its side.
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
     * Zeros would put the sensor exactly at the platform's origin — a plausible-looking position that
     * nothing downstream could tell from a real measurement. The same stance the preferences reader
     * takes, and for the same reason.
     */
    @Test
    fun `a transform missing a translation component is skipped rather than read as the origin`() {
        val document = """
            {
              "name": "Platform",
              "frame_transforms": [
                { "parent_frame_id": "platform-frame-ccrp", "child_frame_id": "platform-frame-a",
                  "translation_m": { "x": 1.0, "y": 2.0 } },
                { "parent_frame_id": "platform-frame-ccrp", "child_frame_id": "platform-frame-b",
                  "translation_m": { "x": 1.0, "y": 2.0, "z": 3.0 } },
                { "parent_frame_id": "platform-frame-ccrp",
                  "translation_m": { "x": 1.0, "y": 2.0, "z": 3.0 } }
              ]
            }
        """.trimIndent()

        val read = parsePlatformGeometry(document, fallbackEntityId = "platform")!!
        assertEquals(listOf("platform-frame-b"), read.sensors.map { it.frameId })
    }

    /** Half a position is no position: a lone latitude would put the platform on the Greenwich meridian. */
    @Test
    fun `a zero missing its longitude is dropped and the platform survives without it`() {
        val document = """
            {
              "entity_id": "platform", "name": "Platform",
              "frame_transforms": [],
              "calibration": { "zero": { "latitude": 57.7, "heading_deg": 12.0 } }
            }
        """.trimIndent()

        val read = parsePlatformGeometry(document)!!
        assertNull(read.zero)
        assertEquals("Platform", read.name)
    }

    /** A crowsnest `platform_registry.json` is an object of platforms, and reads as all of them. */
    @Test
    fun `a registry of several platforms reads as several platforms`() {
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

    /** ...and one platform, handed to the same function, is one platform rather than a registry of fields. */
    @Test
    fun `a single platform document is not mistaken for a registry`() {
        assertEquals(listOf("sealog"), parsePlatformDocument(platform().toStoredJson()).map { it.entityId })
    }

    /**
     * An entity id that is not one is refused, not repaired.
     *
     * It goes straight into `{realm}/@v0/{entity_id}/pubsub/...` and into this app's own navigation
     * routes, so a `/` in it adds a chunk to both. Slugifying would import a platform whose key no longer
     * matches the one the station that sent it uses, which is worse than not importing it.
     */
    @Test
    fun `a document with a malformed entity id is refused`() {
        fun withId(id: String) = """{ "entity_id": "$id", "name": "Platform", "frame_transforms": [] }"""

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
        assertEquals(emptyList<PlatformCalibration>(), parsePlatformDocument("null"))
    }

    /** A present-but-null key has to read the same as an absent one, not as the string "null". */
    @Test
    fun `an explicit JSON null reads as absent`() {
        val document = """
            { "entity_id": "platform", "name": "Platform", "description": null,
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
              "entity_id": "platform", "name": "Platform", "platform_type": "submarine",
              "frame_transforms": [
                { "parent_frame_id": "platform-frame-ccrp", "child_frame_id": "platform-frame-sonar",
                  "sensor_type": "sonar", "translation_m": { "x": 0, "y": 0, "z": 0 } }
              ]
            }
        """.trimIndent()

        val read = parsePlatformGeometry(document)!!
        assertNull(read.platformType)
        assertEquals(SensorType.OTHER, read.sensors.single().sensorType)
    }

    /**
     * The two names `0.6.0-pre.18` renamed still read, and that is the difference between an upgrade
     * and a silent data loss.
     *
     * `landkrabba` became `sensor_station` and `roc` became `operator_station`. Every platform in the
     * library is stored as its wire document, so *this* parser is what reads a platform surveyed before
     * the upgrade — and it is deliberately tolerant, meaning an unrecognised value comes back null
     * rather than failing. Without the alias a phone would quietly forget what its platforms were, with
     * nothing on screen to say so. Same for a document from a station that has not upgraded yet.
     */
    @Test
    fun `the platform type names renamed upstream still read`() {
        fun typeOf(value: String) = parsePlatformGeometry(
            """{ "entity_id": "p", "name": "P", "platform_type": "$value", "frame_transforms": [] }"""
        )!!.platformType

        assertEquals(PlatformType.SENSOR_STATION, typeOf("landkrabba"))
        assertEquals(PlatformType.OPERATOR_STATION, typeOf("roc"))
        // The new spellings are what actually gets written, and must not have been broken by the alias.
        assertEquals(PlatformType.SENSOR_STATION, typeOf("sensor_station"))
        assertEquals(PlatformType.OPERATOR_STATION, typeOf("operator_station"))
        assertEquals(PlatformType.VESSEL, typeOf("vessel"))
    }

    /**
     * Only the new spelling is ever written, whatever was read.
     *
     * The alias is a one-way ramp: `config-schema.json` is `additionalProperties: false` with a
     * constrained enum, so re-emitting `landkrabba` would produce a document the platform connector
     * rejects — which is the whole reason for the rename.
     */
    @Test
    fun `a legacy type is written back in the new vocabulary`() {
        val read = parsePlatformGeometry(
            """{ "entity_id": "p", "name": "P", "platform_type": "landkrabba", "frame_transforms": [] }"""
        )!!

        val json = read.toPlatformGeometryJson()
        assertTrue(json, json.contains("\"platform_type\": \"sensor_station\""))
        assertFalse("the retired name must never go back on the wire", json.contains("landkrabba"))
    }
}
