package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.rise.logline.calibrate.PlatformPhotos
import se.rise.logline.calibrate.photoFileName
import se.rise.logline.calibrate.photoRotationDegrees
import se.rise.logline.calibrate.photoSampleSize
import java.io.File

/**
 * The photo store, which is where a platform's picture and its entity id are tied together.
 *
 * Worth testing despite being a handful of file operations, because the file name *is* the key: the
 * two moments an entity id stops naming what it named — a rename and a delete — are the two ways a
 * platform ends up showing somebody else's boat, and neither fails loudly.
 */
class PlatformPhotosTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun photos() = PlatformPhotos(File(temp.root, "platforms"))

    @Test
    fun `a written photo is found again under its entity id`() {
        val store = photos()
        assertNull("nothing before anything is written", store.photo("ssrs18"))

        store.write("ssrs18", byteArrayOf(1, 2, 3))

        assertEquals(listOf<Byte>(1, 2, 3), store.photo("ssrs18")?.readBytes()?.toList())
        assertNull("and only under its own id", store.photo("stora-krabban"))
    }

    /**
     * **A rename is a re-key**, the same rule `Settings.upsertPlatform` exists for.
     *
     * The entity id is both the identity and the `{entity_id}` chunk of every key the platform
     * publishes on, so renaming one is routine. Leaving the file behind would show the platform as
     * having no photograph while a file nothing can reach sat in `filesDir` forever.
     */
    @Test
    fun `a rename carries the photo across`() {
        val store = photos()
        store.write("ssrs18", byteArrayOf(7))

        store.move("ssrs18", "ssrs-18")

        assertNull(store.photo("ssrs18"))
        assertEquals(listOf<Byte>(7), store.photo("ssrs-18")?.readBytes()?.toList())
    }

    /** The destination is overwritten: the id is the identity, so what was there is not this platform. */
    @Test
    fun `a rename onto an occupied id replaces what was there`() {
        val store = photos()
        store.write("ssrs18", byteArrayOf(7))
        store.write("stora-krabban", byteArrayOf(9))

        store.move("ssrs18", "stora-krabban")

        assertEquals(listOf<Byte>(7), store.photo("stora-krabban")?.readBytes()?.toList())
        assertNull(store.photo("ssrs18"))
    }

    /** A rename that renames nothing, and a rename of a platform that has no photo, are both no-ops. */
    @Test
    fun `a rename with nothing to move is harmless`() {
        val store = photos()
        store.write("ssrs18", byteArrayOf(7))

        store.move("ssrs18", "ssrs18")
        assertEquals(listOf<Byte>(7), store.photo("ssrs18")?.readBytes()?.toList())

        store.move("stora-krabban", "ssrs18")
        assertEquals("the absent source did not blank the target", listOf<Byte>(7), store.photo("ssrs18")?.readBytes()?.toList())
    }

    /**
     * **Deleting a platform has to delete its photograph.**
     *
     * Otherwise the next platform given that entity id — which is an ordinary thing to do, the ids
     * being short slugs like `ssrs18` — silently inherits a stranger's boat, and every screen showing
     * it would be confidently wrong.
     */
    @Test
    fun `removing a platform removes its photo`() {
        val store = photos()
        store.write("ssrs18", byteArrayOf(7))

        store.remove("ssrs18")

        assertNull(store.photo("ssrs18"))
        store.write("ssrs18", byteArrayOf(8))
        assertEquals("a new platform on the same id gets its own", listOf<Byte>(8), store.photo("ssrs18")?.readBytes()?.toList())
    }

    /** A blank entity id names no platform, and must not write `.jpg` — a hidden file nothing owns. */
    @Test
    fun `a blank entity id has no photo and writes nothing`() {
        val store = photos()

        store.write("", byteArrayOf(1))

        assertNull(store.photo(""))
        assertFalse("no stray file", File(temp.root, "platforms").exists())
    }

    // ---- file names ----

    /**
     * Entity ids are slugs by default and free text in fact, so the encoding has to survive whatever
     * somebody types. A separator or a dot segment reaching the file name is the interesting failure:
     * `../` would put a write outside the directory entirely.
     */
    @Test
    fun `a file name is safe whatever the entity id contains`() {
        assertEquals("ssrs18.jpg", photoFileName("ssrs18"))
        assertEquals("stora-krabban.jpg", photoFileName("stora-krabban"))
        assertEquals("a%2Fb.jpg", photoFileName("a/b"))
        assertEquals("..%2F..%2Fetc.jpg", photoFileName("../../etc"))
        assertEquals("a%20b.jpg", photoFileName("a b"))
        // Per UTF-16 code unit, not per UTF-8 byte — so this is `%E4` where RFC 3986 would say
        // `%C3%A4`. It is not a URL and nothing decodes it; it only has to be deterministic, unique
        // and legal as a file name, and a wider character simply produces more hex digits.
        assertEquals("r%E4ddning.jpg", photoFileName("räddning"))
        assertEquals("a%2192b.jpg", photoFileName("a→b"))
    }

    /** Distinct ids must not collide, or one platform overwrites another's picture. */
    @Test
    fun `different entity ids give different file names`() {
        val names = listOf("a/b", "a%2Fb", "a b", "ab", "a.b").map { photoFileName(it) }
        assertEquals("no two ids share a file", names.size, names.toSet().size)
    }

    // ---- downsampling ----

    /**
     * The decode's power-of-two downsample, chosen so the decoded bitmap is still at or above the
     * target before the exact scale runs. One power out is invisible on screen — the picture appears
     * either slightly soft or having briefly held four times the memory — which is why it is pinned.
     */
    @Test
    fun `the sample size leaves the long edge at or above the target`() {
        // A Pixel photograph, portrait: 4080 / 2 = 2040, still >= 1280; / 4 = 1020, which is not.
        assertEquals(2, photoSampleSize(3072, 4080, 1280))
        // Landscape is measured on the same edge — the cap is the long one, not the width.
        assertEquals(2, photoSampleSize(4080, 3072, 1280))
        // Already at or below the target: nothing to downsample.
        assertEquals(1, photoSampleSize(1080, 2400, 1280))
        assertEquals(1, photoSampleSize(640, 480, 1280))
        // Exactly twice the target must not halve into it: 2560 / 2 = 1280, which is still >=.
        assertEquals(2, photoSampleSize(2560, 1000, 1280))
        // A big panorama keeps halving.
        assertEquals(8, photoSampleSize(12000, 4000, 1280))
    }

    // ---- orientation ----

    /**
     * The three rotations, by their EXIF tag values rather than `ExifInterface`'s constants — those
     * are `android.media` statics, which a JVM test reads as zero, so every case would agree with
     * every other and the test would pass against any mapping at all.
     */
    @Test
    fun `an exif orientation becomes the rotation that corrects it`() {
        assertEquals(0, photoRotationDegrees(1))
        assertEquals(180, photoRotationDegrees(3))
        assertEquals(90, photoRotationDegrees(6))
        assertEquals(270, photoRotationDegrees(8))
    }

    /**
     * The mirrored orientations are left alone on purpose. They come from a flipped front camera and
     * essentially never from a photograph of a boat, and a wrong flip is worse than none: it puts the
     * port side to starboard in a picture somebody is using to place sensors.
     */
    @Test
    fun `mirrored and unknown orientations are left alone`() {
        listOf(0, 2, 4, 5, 7, 9, -1).forEach {
            assertEquals("orientation $it", 0, photoRotationDegrees(it))
        }
    }

    @Test
    fun `a photo directory is created on the first write and not before`() {
        val store = photos()
        assertFalse(File(temp.root, "platforms").exists())

        store.write("ssrs18", byteArrayOf(1))

        assertTrue(File(temp.root, "platforms").isDirectory)
    }
}
