package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.config.Settings

/**
 * The library's own rules: what is active, what publishes, and what a rename does.
 *
 * A rig's entity id is its identity here *and* the `{entity_id}` chunk of every key its geometry
 * travels on, so editing it is a re-key rather than a field edit. Everything that names a rig names it
 * by that id, which is why the mutators exist at all rather than callers doing a `copy`.
 */
class RigLibraryTest {

    private fun rig(name: String) = RigCalibration.forName(name).copy(
        sensors = listOf(
            SensorMount(
                label = "Lidar",
                frameId = "${se.rise.logline.calibrate.defaultEntityId(name)}-frame-lidar",
                sensorType = SensorType.LIDAR,
                translation = Vec3M(0.22, 0.0, -0.35),
            ),
        ),
    )

    private fun settings(vararg rigs: RigCalibration) = Settings(
        realm = Settings.DEFAULT_REALM,
        entityId = "pixel_6",
        routerEndpoints = listOf(Settings.DEFAULT_ENDPOINT),
        locationSource = Settings.DEFAULT_LOCATION_SOURCE,
        imuSource = Settings.DEFAULT_IMU_SOURCE,
        rigs = rigs.toList(),
        activeRigEntityId = rigs.firstOrNull()?.entityId.orEmpty(),
    )

    /**
     * The active rig publishes whether or not it is in the set.
     *
     * "The phone is on this rig" and "this rig's geometry is not on the bus" is a contradiction, and a
     * state nobody would diagnose — so the invariant lives here rather than in the switch that would
     * otherwise be able to express it.
     */
    @Test
    fun `the active rig always publishes`() {
        val s = settings(rig("SSRS18"), rig("Manatee"))

        assertEquals(listOf("ssrs18"), s.publishingRigs().map { it.entityId })
        assertEquals(listOf("ssrs18"), s.setRigPublishing("ssrs18", false).publishingRigs().map { it.entityId })
    }

    @Test
    fun `opting another rig in publishes both`() {
        val s = settings(rig("SSRS18"), rig("Manatee")).setRigPublishing("manatee", true)

        assertEquals(listOf("ssrs18", "manatee"), s.publishingRigs().map { it.entityId })
    }

    /** A rig with no sensors has nothing to say, so it publishes nothing even when selected. */
    @Test
    fun `a rig with no sensors never publishes`() {
        val s = settings(RigCalibration.forName("SSRS18"))

        assertEquals("ssrs18", s.activeRigEntityId)
        assertTrue(s.publishingRigs().isEmpty())
    }

    /**
     * The one that would bite: everything that names a rig names it by entity id, so a rename has to
     * move the active selection and the publish set with it. Miss that and renaming a rig silently
     * deselects the rig somebody just renamed.
     */
    @Test
    fun `renaming a rig carries its selection across`() {
        val s = settings(rig("SSRS18"), rig("Manatee"))
            .setRigPublishing("manatee", true)
        val renamed = s.rigs.first().copy(entityId = "ssrs18-b")

        val after = s.upsertRig("ssrs18", renamed)

        assertEquals(listOf("ssrs18-b", "manatee"), after.rigs.map { it.entityId })
        assertEquals("ssrs18-b", after.activeRigEntityId)
        assertEquals(listOf("ssrs18-b", "manatee"), after.publishingRigs().map { it.entityId })
    }

    @Test
    fun `renaming an opted-in rig moves it in the publishing set, not the active one`() {
        val s = settings(rig("SSRS18"), rig("Manatee")).setRigPublishing("manatee", true)
        val renamed = s.rigs[1].copy(entityId = "manatee-2")

        val after = s.upsertRig("manatee", renamed)

        assertEquals("ssrs18", after.activeRigEntityId)
        assertEquals(setOf("manatee-2"), after.publishingRigEntityIds)
    }

    /** Editing a rig without touching its id replaces it in place rather than appending a twin. */
    @Test
    fun `editing a rig replaces it in place`() {
        val s = settings(rig("SSRS18"), rig("Manatee"))
        val edited = s.rigs.first().copy(description = "Now with a description")

        val after = s.upsertRig("ssrs18", edited)

        assertEquals(2, after.rigs.size)
        assertEquals("Now with a description", after.rigs.first().description)
    }

    @Test
    fun `a rig with no previous id is added`() {
        val after = settings(rig("SSRS18")).upsertRig(null, rig("Manatee"))

        assertEquals(listOf("ssrs18", "manatee"), after.rigs.map { it.entityId })
        // Adding does not steal the selection: the phone is still on the rig it was on.
        assertEquals("ssrs18", after.activeRigEntityId)
    }

    /** A dangling selection publishes nothing, so removal has to take every reference with it. */
    @Test
    fun `removing a rig removes every reference to it`() {
        val s = settings(rig("SSRS18"), rig("Manatee")).setRigPublishing("manatee", true)

        val after = s.removeRig("ssrs18")

        assertEquals(listOf("manatee"), after.rigs.map { it.entityId })
        assertEquals("", after.activeRigEntityId)
        assertEquals(listOf("manatee"), after.publishingRigs().map { it.entityId })

        val empty = after.removeRig("manatee")
        assertTrue(empty.rigs.isEmpty())
        assertTrue(empty.publishingRigEntityIds.isEmpty())
    }

    /**
     * Two rigs sharing an entity id would publish onto the same three keys and overwrite each other in
     * Zenoh's latest-value store, so a consumer would read one rig's geometry as the other's.
     */
    @Test
    fun `an entity id already in use is reported as taken`() {
        val s = settings(rig("SSRS18"), rig("Manatee"))

        assertTrue(s.entityIdTaken("manatee", exceptEntityId = "ssrs18"))
        // ...but a rig does not collide with itself, or every edit would be blocked.
        assertFalse(s.entityIdTaken("ssrs18", exceptEntityId = "ssrs18"))
        assertFalse(s.entityIdTaken("brand-new", exceptEntityId = null))
    }

    @Test
    fun `an active selection naming a rig that is gone resolves to nothing`() {
        val s = settings(rig("SSRS18")).copy(activeRigEntityId = "never-existed")

        assertEquals(null, s.activeRig())
        assertTrue(s.publishingRigs().isEmpty())
    }
}
