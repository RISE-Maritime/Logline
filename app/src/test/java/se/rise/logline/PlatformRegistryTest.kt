package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.defaultEntityId
import se.rise.logline.platform.PlatformRegistry
import se.rise.logline.platform.RemotePlatformRegistry
import se.rise.logline.platform.decodePlatformRegistry
import se.rise.logline.platform.encodePlatformRegistry
import se.rise.logline.platform.mergeRemotePlatforms
import se.rise.logline.platform.shouldApplyRemote

/**
 * The shared library: its key, its bytes, and who wins.
 *
 * The conflict rules are transcribed from crowsnest's `dataflowConfigSync.js` so the two sides resolve
 * a disagreement the same way rather than each being self-consistent and mutually wrong. They are also
 * the half of this that can silently do the wrong thing, which is why they are pure functions.
 */
class PlatformRegistryTest {

    private fun platform(name: String, capture: CaptureMethod = CaptureMethod.GNSS_AVERAGE) =
        PlatformCalibration.forName(name, atEpochMillis = 1_700_000_000_000L).copy(
            sensors = listOf(
                SensorMount(
                    label = "Lidar",
                    frameId = "${defaultEntityId(name)}-frame-lidar",
                    sensorType = SensorType.LIDAR,
                    translation = Vec3M(0.22, 0.0, -0.35),
                    capture = capture,
                    accuracyM = if (capture == CaptureMethod.GNSS_AVERAGE) 3.2 else null,
                    capturedAtEpochMillis = 1_700_000_000_500L,
                ),
            ),
        )

    /**
     * The token is deliberately not a keelson subject.
     *
     * `platform_registry` is nowhere in `subjects.yaml`, and that is what makes a consumer's decode
     * fall through to raw JSON instead of failing to unwrap an envelope. Same trick crowsnest already
     * plays for `dataflow_config`. `@v0` is verbatim — a wildcard never crosses it.
     */
    @Test
    fun `the library key is the non-keelson subject shape`() {
        assertEquals(
            "rise/@v0/platforms/pubsub/platform_registry/library/latest",
            PlatformRegistry.key("rise"),
        )
        assertEquals(
            "crowsnest/@v0/fleet/pubsub/platform_registry/library/latest",
            PlatformRegistry.key("crowsnest", "fleet"),
        )
    }

    /** What goes out comes back, provenance included — a share that lost it would lose uncertainty. */
    @Test
    fun `a library round-trips through the wire form`() {
        val platforms = listOf(platform("Sealog"), platform("Manatee", CaptureMethod.MANUAL))

        val decoded = decodePlatformRegistry(
            encodePlatformRegistry(7L, "origin-a", 1_700_000_009_000L, platforms)
        )!!

        assertEquals(7L, decoded.version)
        assertEquals("origin-a", decoded.origin)
        assertEquals(1_700_000_009_000L, decoded.updatedAtEpochMillis)
        assertEquals(platforms, decoded.platforms)
        assertEquals(CaptureMethod.GNSS_AVERAGE, decoded.platforms.first().sensors.single().capture)
        assertEquals(3.2, decoded.platforms.first().sensors.single().accuracyM!!, 1e-9)
    }

    @Test
    fun `bytes that are not a library decode to nothing rather than throwing`() {
        assertNull(decodePlatformRegistry("{ not json".toByteArray()))
        assertNull(decodePlatformRegistry("{}".toByteArray()))
        assertNull(decodePlatformRegistry(ByteArray(0)))
    }

    /**
     * The origin guard is not an optimisation.
     *
     * A publisher's own sample cache re-delivers, so without it a phone applies its own library back
     * over itself on every reconnect and the version ratchets for no reason.
     */
    @Test
    fun `a phone never applies its own library back over itself`() {
        val own = RemotePlatformRegistry(99L, "origin-a", 0L, listOf(platform("Sealog")))

        assertFalse(shouldApplyRemote(own, localVersion = 1L, ownOrigin = "origin-a"))
        assertTrue(shouldApplyRemote(own, localVersion = 1L, ownOrigin = "origin-b"))
    }

    /** Last-writer-wins by version, and "the same version" is not newer. */
    @Test
    fun `only a strictly newer library is applied`() {
        val remote = RemotePlatformRegistry(5L, "origin-b", 0L, listOf(platform("Sealog")))

        assertTrue(shouldApplyRemote(remote, localVersion = 4L, ownOrigin = "origin-a"))
        assertFalse(shouldApplyRemote(remote, localVersion = 5L, ownOrigin = "origin-a"))
        assertFalse(shouldApplyRemote(remote, localVersion = 6L, ownOrigin = "origin-a"))
        assertFalse(shouldApplyRemote(null, localVersion = 0L, ownOrigin = "origin-a"))
    }

    /**
     * The rule that matters most: a remote library replaces documents and never local publish policy.
     *
     * One operator saving a library must not silently start every phone in the fleet publishing
     * geometry under entity ids nobody told them about — so the merge returns platforms, and the caller's
     * active selection and publish set are never in its argument list to begin with.
     */
    @Test
    fun `a remote library brings documents and takes nothing else`() {
        val local = listOf(platform("Sealog"), platform("Manatee"))
        val remote = listOf(platform("Sealog").copy(description = "edited elsewhere"), platform("Gota"))

        val merged = mergeRemotePlatforms(local, remote, protectedEntityIds = emptySet())

        assertEquals(listOf("sealog", "gota"), merged.map { it.entityId })
        assertEquals("edited elsewhere", merged.first().description)
    }

    /**
     * A knowing deviation from crowsnest's whole-map replace.
     *
     * Taking a platform out from under a live publisher is the one case where last-writer-wins is not
     * acceptable: the run would keep a publisher and a liveliness token for a platform the library no
     * longer has. It comes back as soon as it is switched off.
     */
    @Test
    fun `a platform this phone is publishing is never deleted by a remote library`() {
        val local = listOf(platform("Sealog"), platform("Manatee"))
        val remote = listOf(platform("Gota"))

        val merged = mergeRemotePlatforms(local, remote, protectedEntityIds = setOf("manatee"))

        assertEquals(listOf("gota", "manatee"), merged.map { it.entityId })
        // ...and the one nothing was publishing is gone, as the remote said.
        assertTrue(merged.none { it.entityId == "sealog" })
    }

    /**
     * Applying a merged library must not republish it at the sender's own version.
     *
     * `mergeRemotePlatforms` keeps platforms this phone is publishing, so what comes out of an apply is not what
     * arrived. Republishing that at `remote.version` would leave the shared key holding two different
     * libraries both claiming to be the same one, and neither station able to accept the other's —
     * `shouldApplyRemote` needs strictly greater. The applier therefore bumps past it.
     */
    @Test
    fun `a merged library is ordered after the one it was merged from`() {
        val remote = RemotePlatformRegistry(5L, "origin-b", 0L, listOf(platform("Gota")))
        val merged = mergeRemotePlatforms(
            local = listOf(platform("Sealog")),
            remote = remote.platforms,
            protectedEntityIds = setOf("sealog"),
        )
        // What the applier stores: the remote's version, then bumped.
        val storedVersion = remote.version + 1

        assertEquals(listOf("gota", "sealog"), merged.map { it.entityId })
        assertTrue(
            "the merged library must be acceptable to the station it was merged from",
            shouldApplyRemote(
                RemotePlatformRegistry(storedVersion, "origin-a", 0L, merged),
                localVersion = remote.version,
                ownOrigin = "origin-b",
            ),
        )
    }

    @Test
    fun `a protected platform the remote also has is not duplicated`() {
        val local = listOf(platform("Sealog"))
        val remote = listOf(platform("Sealog").copy(description = "edited elsewhere"))

        val merged = mergeRemotePlatforms(local, remote, protectedEntityIds = setOf("sealog"))

        assertEquals(1, merged.size)
        assertEquals("edited elsewhere", merged.single().description)
    }
}
