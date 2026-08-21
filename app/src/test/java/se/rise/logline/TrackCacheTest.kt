package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.record.TrackCache
import se.rise.logline.record.TrackFix
import java.io.File

/**
 * The thumbnail cache, which exists so a recording's track is extracted once and never again.
 *
 * Worth pinning because the alternative is measurable: a track is a full decompress of a recording's
 * data section — 18 ms for the median 2.1 MB file on the dev phone, 4.3 s for the 502 MB one — and the
 * list would otherwise pay it on every visit.
 */
class TrackCacheTest {

    private val stamp = TrackCache.Stamp(sizeBytes = 2_000_000L, savedAtMillis = 1_700_000_000_000L)

    private fun directory(): File =
        File.createTempFile("tracks", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @Test
    fun `a track survives a round trip`() {
        val dir = directory()
        try {
            val fixes = listOf(TrackFix(57.7089, 11.9746), TrackFix(57.7090, 11.9750))

            TrackCache.put(dir, id = 42L, stamp = stamp, fixes = fixes)

            assertEquals(fixes, readBack(dir, 42L))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * **Coordinates keep their precision.**
     *
     * Stored as doubles rather than floats on purpose: a float carries about seven significant digits,
     * which at 57°N is a metre of error — wider than most of the stationary runs this thumbnail exists
     * to tell apart, one of which spans 1.8 m in total.
     */
    @Test
    fun `coordinates are not rounded to a metre`() {
        val dir = directory()
        try {
            val fixes = listOf(TrackFix(57.70891234, 11.97461234), TrackFix(57.70891334, 11.97461334))

            TrackCache.put(dir, id = 7L, stamp = stamp, fixes = fixes)
            val back = readBack(dir, 7L)!!

            assertEquals(57.70891234, back[0].latitude, 1e-9)
            assertEquals(11.97461334, back[1].longitude, 1e-9)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * **An empty track is an answer, not a miss.**
     *
     * A recording with no fixes has to be remembered as having none, or every visit to the list rescans
     * every IMU-only run to learn the same thing — and those are exactly the runs where the scan finds
     * nothing to show for it.
     */
    @Test
    fun `a recording with no fixes is remembered as having none`() {
        val dir = directory()
        try {
            TrackCache.put(dir, id = 9L, stamp = stamp, fixes = emptyList())

            val back = readBack(dir, 9L)
            assertNotNull("cached, not a miss", back)
            assertTrue(back!!.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A file replaced under the same MediaStore id must not show the old shape. */
    @Test
    fun `a changed recording misses the cache`() {
        val dir = directory()
        try {
            TrackCache.put(dir, id = 3L, stamp = stamp, fixes = listOf(TrackFix(57.0, 12.0)))

            assertNull(
                "a different size is a different recording",
                TrackCache.get(dir, 3L, stamp.copy(sizeBytes = stamp.sizeBytes + 1)),
            )
            assertNull(
                "and so is a different save time",
                TrackCache.get(dir, 3L, stamp.copy(savedAtMillis = stamp.savedAtMillis + 1)),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a recording that was never read is a miss`() {
        val dir = directory()
        try {
            assertNull(TrackCache.get(dir, 404L, stamp))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Long tracks are thinned on the way in, so an entry stays about two kilobytes. */
    @Test
    fun `a long track is thinned to the cap, keeping its ends`() {
        val dir = directory()
        try {
            val fixes = (0 until 5_000).map { TrackFix(57.0 + it / 100_000.0, 12.0) }

            TrackCache.put(dir, id = 5L, stamp = stamp, fixes = fixes)
            val back = readBack(dir, 5L)!!

            assertEquals(TrackCache.MAX_POINTS, back.size)
            assertEquals(fixes.first(), back.first())
            assertEquals(fixes.last(), back.last())
            assertTrue("about two kilobytes", File(dir, "5.trk").length() < 3_000L)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A deleted recording must not leave its cache entry behind. */
    @Test
    fun `pruning drops entries for recordings that are gone`() {
        val dir = directory()
        try {
            TrackCache.put(dir, id = 1L, stamp = stamp, fixes = listOf(TrackFix(57.0, 12.0)))
            TrackCache.put(dir, id = 2L, stamp = stamp, fixes = listOf(TrackFix(58.0, 12.0)))

            TrackCache.prune(dir, keep = setOf(1L))

            assertNotNull("the listed one survives", readBack(dir, 1L))
            assertNull("the gone one is dropped from disk too", readBack(dir, 2L))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Reads past the in-memory copy, which is what a fresh launch does.
     *
     * `put` populates memory as well as disk, so a plain `get` would pass even with nothing written —
     * the bug this indirection exists to catch.
     */
    private fun readBack(directory: File, id: Long): List<TrackFix>? {
        TrackCache.forgetInMemory()
        return TrackCache.get(directory, id, stamp)
    }
}
