package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.record.folderLabelOf
import se.rise.logline.record.isPartialName

/**
 * Naming the folder recordings are written to, and knowing a copy that is still in flight.
 *
 * Both are pure on purpose. The alternative to testing them is reading them off a phone that happens
 * to have a card in it, which is the sort of check nobody repeats.
 */
class OutputFolderTest {

    /**
     * A tree document id is `volume:path`, and the volume half is an identifier rather than a name.
     *
     * `primary` is what the framework calls built-in storage, so showing it would put a word on screen
     * that appears nowhere else on the phone. A card is a serial number, which is no better as a name
     * but is the only thing distinguishing two folders that are otherwise called the same.
     */
    @Test
    fun `a folder is named by its path`() {
        assertEquals("Download/Logline", folderLabelOf("primary:Download/Logline"))
        assertEquals("Documents/trips", folderLabelOf("primary:Documents/trips"))
        // A card or a stick: the volume stays, because two `trips` folders on two volumes are not the
        // same folder and the operator is the one who has to tell them apart.
        assertEquals("1234-5678/trips", folderLabelOf("1234-5678:trips"))
    }

    /** The root of a volume has an empty path, and the identifier is then all there is to show. */
    @Test
    fun `the root of a volume falls back to the volume`() {
        assertEquals("primary", folderLabelOf("primary:"))
        assertEquals("1234-5678", folderLabelOf("1234-5678:"))
    }

    /** A provider that spells its ids some other way is shown verbatim rather than mangled. */
    @Test
    fun `an id with no volume is left alone`() {
        assertEquals("raw:/storage/emulated/0/Download", folderLabelOf("raw:/storage/emulated/0/Download"))
        assertEquals("msf%3A17", folderLabelOf("msf%3A17"))
    }

    /**
     * The suffix a file wears while it is being copied.
     *
     * `MediaStore` hides an incomplete file with `IS_PENDING` and the Storage Access Framework has no
     * such flag, so a chosen folder gets the same guarantee from the name — and the listing has to
     * know it, or a row appears claiming to be `Not a recording` and vanishes a few seconds later.
     */
    @Test
    fun `a copy in flight is known by its suffix`() {
        assertTrue(isPartialName("logline-2026-09-10T082432.mcap.part"))
        assertFalse(isPartialName("logline-2026-09-10T082432.mcap"))
        assertFalse(isPartialName("logline-settings-2026-09-10T082432.json"))
        // The suffix is the end of the name, not anywhere in it.
        assertFalse(isPartialName("part-of-a-trial.mcap"))
    }
}
