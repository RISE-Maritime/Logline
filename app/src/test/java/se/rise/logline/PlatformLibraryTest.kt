package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.config.Settings

/**
 * The library's own rules: what is active, what publishes, and what a rename does.
 *
 * A platform's entity id is its identity here *and* the `{entity_id}` chunk of every key its geometry
 * travels on, so editing it is a re-key rather than a field edit. Everything that names a platform names it
 * by that id, which is why the mutators exist at all rather than callers doing a `copy`.
 */
class PlatformLibraryTest {

    private fun platform(name: String) = PlatformCalibration.forName(name).copy(
        sensors = listOf(
            SensorMount(
                label = "Lidar",
                frameId = "${se.rise.logline.calibrate.defaultEntityId(name)}-frame-lidar",
                sensorType = SensorType.LIDAR,
                translation = Vec3M(0.22, 0.0, -0.35),
            ),
        ),
    )

    private fun settings(vararg platforms: PlatformCalibration) = Settings(
        realm = Settings.DEFAULT_REALM,
        entityId = "pixel_6",
        routerEndpoints = listOf(Settings.DEFAULT_ENDPOINT),
        locationSource = Settings.DEFAULT_LOCATION_SOURCE,
        imuSource = Settings.DEFAULT_IMU_SOURCE,
        platforms = platforms.toList(),
        activePlatformEntityId = platforms.firstOrNull()?.entityId.orEmpty(),
    )

    /**
     * The active platform publishes whether or not it is in the set.
     *
     * "The phone is on this platform" and "this platform's geometry is not on the bus" is a contradiction, and a
     * state nobody would diagnose — so the invariant lives here rather than in the switch that would
     * otherwise be able to express it.
     */
    @Test
    fun `the active platform always publishes`() {
        val s = settings(platform("SSRS18"), platform("Manatee"))

        assertEquals(listOf("ssrs18"), s.publishingPlatforms().map { it.entityId })
        assertEquals(listOf("ssrs18"), s.setPlatformPublishing("ssrs18", false).publishingPlatforms().map { it.entityId })
    }

    @Test
    fun `opting another platform in publishes both`() {
        val s = settings(platform("SSRS18"), platform("Manatee")).setPlatformPublishing("manatee", true)

        assertEquals(listOf("ssrs18", "manatee"), s.publishingPlatforms().map { it.entityId })
    }

    /** A platform with no sensors has nothing to say, so it publishes nothing even when selected. */
    @Test
    fun `a platform with no sensors never publishes`() {
        val s = settings(PlatformCalibration.forName("SSRS18"))

        assertEquals("ssrs18", s.activePlatformEntityId)
        assertTrue(s.publishingPlatforms().isEmpty())
    }

    /**
     * The one that would bite: everything that names a platform names it by entity id, so a rename has to
     * move the active selection and the publish set with it. Miss that and renaming a platform silently
     * deselects the platform somebody just renamed.
     */
    @Test
    fun `renaming a platform carries its selection across`() {
        val s = settings(platform("SSRS18"), platform("Manatee"))
            .setPlatformPublishing("manatee", true)
        val renamed = s.platforms.first().copy(entityId = "ssrs18-b")

        val after = s.upsertPlatform("ssrs18", renamed)

        assertEquals(listOf("ssrs18-b", "manatee"), after.platforms.map { it.entityId })
        assertEquals("ssrs18-b", after.activePlatformEntityId)
        assertEquals(listOf("ssrs18-b", "manatee"), after.publishingPlatforms().map { it.entityId })
    }

    @Test
    fun `renaming an opted-in platform moves it in the publishing set, not the active one`() {
        val s = settings(platform("SSRS18"), platform("Manatee")).setPlatformPublishing("manatee", true)
        val renamed = s.platforms[1].copy(entityId = "manatee-2")

        val after = s.upsertPlatform("manatee", renamed)

        assertEquals("ssrs18", after.activePlatformEntityId)
        assertEquals(setOf("manatee-2"), after.publishingPlatformEntityIds)
    }

    /** Editing a platform without touching its id replaces it in place rather than appending a twin. */
    @Test
    fun `editing a platform replaces it in place`() {
        val s = settings(platform("SSRS18"), platform("Manatee"))
        val edited = s.platforms.first().copy(description = "Now with a description")

        val after = s.upsertPlatform("ssrs18", edited)

        assertEquals(2, after.platforms.size)
        assertEquals("Now with a description", after.platforms.first().description)
    }

    @Test
    fun `a platform with no previous id is added`() {
        val after = settings(platform("SSRS18")).upsertPlatform(null, platform("Manatee"))

        assertEquals(listOf("ssrs18", "manatee"), after.platforms.map { it.entityId })
        // Adding does not steal the selection: the phone is still on the platform it was on.
        assertEquals("ssrs18", after.activePlatformEntityId)
    }

    /** A dangling selection publishes nothing, so removal has to take every reference with it. */
    @Test
    fun `removing a platform removes every reference to it`() {
        val s = settings(platform("SSRS18"), platform("Manatee")).setPlatformPublishing("manatee", true)

        val after = s.removePlatform("ssrs18")

        assertEquals(listOf("manatee"), after.platforms.map { it.entityId })
        assertEquals("", after.activePlatformEntityId)
        assertEquals(listOf("manatee"), after.publishingPlatforms().map { it.entityId })

        val empty = after.removePlatform("manatee")
        assertTrue(empty.platforms.isEmpty())
        assertTrue(empty.publishingPlatformEntityIds.isEmpty())
    }

    /**
     * Two platforms sharing an entity id would publish onto the same three keys and overwrite each other in
     * Zenoh's latest-value store, so a consumer would read one platform's geometry as the other's.
     */
    @Test
    fun `an entity id already in use is reported as taken`() {
        val s = settings(platform("SSRS18"), platform("Manatee"))

        assertTrue(s.entityIdTaken("manatee", exceptEntityId = "ssrs18"))
        // ...but a platform does not collide with itself, or every edit would be blocked.
        assertFalse(s.entityIdTaken("ssrs18", exceptEntityId = "ssrs18"))
        assertFalse(s.entityIdTaken("brand-new", exceptEntityId = null))
    }

    @Test
    fun `an active selection naming a platform that is gone resolves to nothing`() {
        val s = settings(platform("SSRS18")).copy(activePlatformEntityId = "never-existed")

        assertEquals(null, s.activePlatform())
        assertTrue(s.publishingPlatforms().isEmpty())
    }
}
