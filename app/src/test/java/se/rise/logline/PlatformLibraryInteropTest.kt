package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import se.rise.logline.platform.PlatformRegistry
import se.rise.logline.platform.decodePlatformRegistry

/**
 * A library **crowsnest produced**, decoded by this app.
 *
 * Every other test here round-trips this app's own encoder through its own decoder, which cannot catch
 * the failure that matters: the two implementations agreeing with themselves and not with each other. A
 * renamed field or a key typo "makes both sides work perfectly and never meet, with no error on either"
 * — crowsnest's own check script's words for the same hazard, from the other side of it.
 *
 * This is the discipline `ChecklistWireTest` already uses for the checklist messages, applied to the one
 * document the two projects exchange. The fixture below is **not hand-written**: it is the literal output
 * of `encodeLibrary()` in `crowsnest-dev/src/services/platformLibrarySync.js`, run over the `sf18` entry
 * of that repo's own `src/DB/platform_registry.json`, trimmed to two sensors for readability. Regenerate
 * it the same way rather than editing it by hand — a fixture somebody adjusted to make a test pass is a
 * fixture that no longer proves anything about crowsnest.
 *
 * **This direction cannot currently be tested live**, which is why it is pinned here: receiving a library
 * means subscribing, and a subscription aborts the process — see `ZenohBinding.SUBSCRIPTIONS_SAFE`. The
 * phone → crowsnest direction *is* testable on a real bus, because publishing is unaffected.
 */
class PlatformLibraryInteropTest {

    private val crowsnestLibrary = """
        {"v":1,"version":7,"origin":"crowsnest-ROC-1","updatedAtEpochMillis":1787576550000,
        "platforms":{"sf18":{"entity_id":"sf18","parent_frame_id":"sf18-demo-frame-ccrp",
        "platform_type":"vessel","name":"SF18","length_over_all_m":1.8,"breadth_over_all_m":0.45,
        "mmsi_number":265538760,"imo_number":0,"call_sign":"SLXX","ccrp_m":{"x":0,"y":0,"z":0},
        "frame_transforms":[{"parent_frame_id":"sf18-demo-frame-ccrp",
        "child_frame_id":"sf18-demo-frame-lidar-os","sensor_type":"lidar",
        "sensor_description":"Ouster OS lidar","translation_m":{"x":0.22,"y":0,"z":0},
        "rotation_deg":{"yaw":0,"pitch":0,"roll":0}},{"parent_frame_id":"sf18-demo-frame-ccrp",
        "child_frame_id":"sf18-demo-frame-camera-axis-4","sensor_type":"camera",
        "sensor_description":"Port camera (Axis)","translation_m":{"x":0.075,"y":-0.41,"z":-0.04},
        "rotation_deg":{"yaw":-90,"pitch":0,"roll":0}}],"realm":"rise"}}}
    """.trimIndent().replace("\n", "")

    /** The envelope: the three fields the conflict rules are decided on. */
    @Test
    fun `a crowsnest library decodes with its version and origin intact`() {
        val decoded = decodePlatformRegistry(crowsnestLibrary.toByteArray())

        assertNotNull("crowsnest's own output must decode here", decoded)
        assertEquals(7L, decoded!!.version)
        // The origin guard is what stops a phone applying its own library back over itself; it only
        // works if the field survives the crossing.
        assertEquals("crowsnest-ROC-1", decoded.origin)
        assertEquals(1_787_576_550_000L, decoded.updatedAtEpochMillis)
    }

    /**
     * The platform itself, keyed by the object's own field name.
     *
     * `entity_id` is the identity *and* the `{entity_id}` chunk of every key that platform publishes on,
     * so reading it from the wrong place would file a vessel's geometry under a name nobody looks up.
     */
    @Test
    fun `the platform comes through with its identity and parent frame`() {
        val platform = decodePlatformRegistry(crowsnestLibrary.toByteArray())!!.platforms.single()

        assertEquals("sf18", platform.entityId)
        assertEquals("SF18", platform.name)
        assertEquals("sf18-demo-frame-ccrp", platform.parentFrameId)
    }

    /**
     * **The sensors, which is where the two shapes genuinely differ.**
     *
     * Crowsnest writes `frame_transforms` with `translation_m` / `rotation_deg` objects; this app's model
     * is a list of mounts. If that mapping ever slipped, a library would still decode — with no sensors,
     * or with sensors at the origin — and nothing downstream could tell that from a platform somebody
     * had genuinely not surveyed. That is the failure this whole file exists for.
     */
    @Test
    fun `frame transforms become sensor mounts, offsets and angles intact`() {
        val platform = decodePlatformRegistry(crowsnestLibrary.toByteArray())!!.platforms.single()

        assertEquals(2, platform.sensors.size)

        val lidar = platform.sensors.first { it.frameId == "sf18-demo-frame-lidar-os" }
        assertEquals(0.22, lidar.translation.x, 1e-9)
        assertEquals(0.0, lidar.translation.y, 1e-9)

        // The negative yaw is the one worth spelling out: a sign lost here puts a port camera to
        // starboard, which reads as a perfectly plausible install.
        val camera = platform.sensors.first { it.frameId == "sf18-demo-frame-camera-axis-4" }
        assertEquals(-0.41, camera.translation.y, 1e-9)
        assertEquals(-90.0, camera.rotation.yaw, 1e-9)
    }

    /**
     * And the key both sides publish on, character for character.
     *
     * Checked against crowsnest's `platformLibraryKey()`, which builds
     * `${'$'}{realm}/@v0/${'$'}{entity}/pubsub/platform_registry/library/latest` with the same `platforms`
     * default. A typo here is the silent-never-meet failure in its purest form.
     */
    @Test
    fun `the key matches crowsnest's builder exactly`() {
        assertEquals(
            "rise/@v0/platforms/pubsub/platform_registry/library/latest",
            PlatformRegistry.key("rise"),
        )
        assertEquals("platforms", PlatformRegistry.DEFAULT_ENTITY)
    }

    /** A library with the platforms object renamed is not a library, rather than an empty one. */
    @Test
    fun `a renamed platforms field decodes to nothing rather than to an empty library`() {
        val renamed = crowsnestLibrary.replace("\"platforms\":", "\"platform_list\":")

        assertNull(decodePlatformRegistry(renamed.toByteArray()))
    }
}
