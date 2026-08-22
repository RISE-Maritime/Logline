package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.calibrate.ImportDisposition
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.importCandidates
import se.rise.logline.calibrate.parsePlatformDocument
import se.rise.logline.calibrate.platformRegistryJson

/**
 * The crowsnest-shaped export, and what an import does with it.
 *
 * The shape is the whole point: crowsnest keys its registry by keelson entity id and its entries are
 * upstream's `config-schema.json` plus `realm`. Getting that wrong produces a file that looks right
 * and that `PlatformEditDialog` cannot read.
 */
class PlatformRegistryExportTest {

    private fun platform(name: String, sensor: String) = PlatformCalibration.forName(name).copy(
        sensors = listOf(
            SensorMount(
                label = sensor,
                frameId = "${se.rise.logline.calibrate.defaultEntityId(name)}-frame-lidar",
                sensorType = SensorType.LIDAR,
                translation = Vec3M(0.22, 0.0, -0.35),
            ),
        ),
    )

    @Test
    fun `the registry is an object keyed by entity id`() {
        val json = platformRegistryJson(listOf(platform("SSRS18", "Lidar"), platform("Stora Krabban", "Radar")), "rise")

        assertTrue(json.trimStart().startsWith("{"))
        assertTrue(json.contains("\"ssrs18\": {"))
        assertTrue(json.contains("\"stora-krabban\": {"))
        assertTrue("the realm is the field crowsnest needs and upstream has no room for",
            json.contains("\"realm\": \"rise\""))
    }

    /**
     * The entity id is the key, so it must not also be a field.
     *
     * Crowsnest treats the key as the identity; a second copy inside the entry is one more thing that
     * can disagree with it, and upstream's schema is `additionalProperties: false` and has no such
     * field at all.
     */
    @Test
    fun `an entry carries no entity id of its own`() {
        val json = platformRegistryJson(listOf(platform("SSRS18", "Lidar")), "rise")
        assertFalse(json.contains("entity_id"))
    }

    /** Provenance is ours, not crowsnest's — the registry entry is the strict document plus `realm`. */
    @Test
    fun `the registry entry carries no calibration provenance block`() {
        val json = platformRegistryJson(listOf(platform("SSRS18", "Lidar")), "rise")
        assertFalse(json.contains("\"calibration\""))
    }

    /** The round trip that matters: what this writes, this reads. */
    @Test
    fun `an exported registry parses back to the same platforms`() {
        val platforms = listOf(platform("SSRS18", "Lidar"), platform("Stora Krabban", "Radar"))

        val read = parsePlatformDocument(platformRegistryJson(platforms, "rise"))

        assertEquals(platforms.map { it.entityId }, read.map { it.entityId })
        assertEquals(platforms.map { it.name }, read.map { it.name })
        assertEquals(
            platforms.map { r -> r.sensors.map { it.frameId } },
            read.map { r -> r.sensors.map { it.frameId } },
        )
    }

    @Test
    fun `an empty library exports an empty object rather than nothing`() {
        assertEquals(emptyList<PlatformCalibration>(), parsePlatformDocument(platformRegistryJson(emptyList(), "rise")))
    }

    /**
     * A platform already in the library is a replacement, and replacements are opt-out.
     *
     * A silent merge is how somebody's surveyed zero disappears — the screen asks before overwriting,
     * and this is the data that question is built from.
     */
    @Test
    fun `an import names which platforms it would replace`() {
        val existing = listOf(platform("SSRS18", "Lidar"))
        val incoming = listOf(platform("SSRS18", "Radar"), platform("Manatee", "Camera"))

        val candidates = importCandidates(incoming, existing)

        assertEquals(
            listOf(ImportDisposition.REPLACES, ImportDisposition.NEW),
            candidates.map { it.disposition },
        )
        assertEquals(listOf(false, true), candidates.map { it.selected })
    }

    @Test
    fun `a purely additive import has nothing to confirm`() {
        val candidates = importCandidates(listOf(platform("Manatee", "Camera")), listOf(platform("SSRS18", "Lidar")))
        assertTrue(candidates.none { it.disposition == ImportDisposition.REPLACES })
    }
}
