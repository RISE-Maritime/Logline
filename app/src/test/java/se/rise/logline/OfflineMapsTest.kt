package se.rise.logline

import se.rise.logline.map.archiveFileNameOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The extension is the whole basis on which osmdroid decides what an archive is.
 *
 * `ArchiveFileFactory` dispatches on it and nothing else — so a file saved without one, or renamed to
 * something friendlier on the way in, is simply never read. The map stays blank with a 400 MB file
 * sitting in the right directory looking imported, which is about the worst failure this feature could
 * have. Everything here is about refusing that before the copy rather than after it.
 */
class OfflineMapsTest {

    @Test
    fun `the formats osmdroid registers are accepted`() {
        listOf("baltic.mbtiles", "coast.gemf", "tiles.zip", "area.sqlite").forEach { name ->
            assertEquals("$name should be accepted", name, archiveFileNameOrNull(name))
        }
    }

    /** Providers hand back all sorts of capitalisation; the format is the same either way. */
    @Test
    fun `the extension is matched regardless of case`() {
        assertEquals("Baltic.MBTiles", archiveFileNameOrNull("Baltic.MBTiles"))
        assertEquals("COAST.GEMF", archiveFileNameOrNull("COAST.GEMF"))
    }

    /** The name is kept as it was — only the *decision* ignores case, not the file on disk. */
    @Test
    fun `the stored name keeps its original spelling`() {
        assertEquals("Öresund 2026.mbtiles", archiveFileNameOrNull("Öresund 2026.mbtiles"))
    }

    @Test
    fun `anything else is refused`() {
        assertNull(archiveFileNameOrNull("chart.pdf"))
        assertNull(archiveFileNameOrNull("notes.txt"))
        // A recording is not a map, and shares a folder with things that are.
        assertNull(archiveFileNameOrNull("logline-2026-08-19T094832.mcap"))
    }

    /** No extension at all is the case that would otherwise copy happily and never render. */
    @Test
    fun `a name with no extension is refused`() {
        assertNull(archiveFileNameOrNull("baltic"))
        assertNull(archiveFileNameOrNull(""))
        assertNull(archiveFileNameOrNull("   "))
    }

    /**
     * A provider can hand back something path-shaped. Only the last segment is the file name, and a
     * directory called `maps.mbtiles` holding a file called `data` must not read as an archive.
     */
    @Test
    fun `only the last path segment is considered`() {
        assertEquals("baltic.mbtiles", archiveFileNameOrNull("downloads/charts/baltic.mbtiles"))
        assertNull(archiveFileNameOrNull("maps.mbtiles/data"))
    }
}
