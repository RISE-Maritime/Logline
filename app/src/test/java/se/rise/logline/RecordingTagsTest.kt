package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.record.encodeTags
import se.rise.logline.record.normaliseTag
import se.rise.logline.record.parseTags
import se.rise.logline.ui.matchesQuery

/**
 * Tags on a recording: what survives being written down, and what a search finds.
 *
 * The words live in `Settings` and are written into the recording at close — see [McapTagsTest] for
 * that round trip. These are the rules that decide what a tag *is*.
 *
 * The store itself is DataStore and needs a device; everything that decides what a tag *is* is pure and
 * lives here, because the failure mode is quiet — a tag that changes shape between the chip and the
 * file reads as a typo, and one that splits in two on the way back out is a tag nobody typed.
 */
class RecordingTagsTest {

    @Test
    fun `a tag keeps its words and loses its padding`() {
        assertEquals("quay trial", normaliseTag("  quay trial  "))
        assertEquals("quay trial", normaliseTag("quay    trial"))
    }

    /**
     * **The separator cannot survive inside a tag.** A newline would split one tag into two on the way
     * back out of the store — the round trip inventing a tag nobody wrote.
     */
    @Test
    fun `a tag cannot contain the separator`() {
        val tag = normaliseTag("engine\nrun")

        assertEquals("engine run", tag)
        assertEquals(setOf("engine run"), parseTags(encodeTags(setOf(tag!!))))
    }

    @Test
    fun `nothing but whitespace is not a tag`() {
        assertNull(normaliseTag(""))
        assertNull(normaliseTag("   "))
        assertNull(normaliseTag("\n"))
    }

    /** Long enough for a phrase; a row still has to stay a row. */
    @Test
    fun `a very long tag is cut and left tidy`() {
        val tag = normaliseTag("x".repeat(80))!!

        assertEquals(40, tag.length)
        assertTrue(tag.all { it == 'x' })
    }

    @Test
    fun `tags survive a round trip through storage`() {
        val tags = setOf("quay trial", "engine run", "calm")

        assertEquals(tags, parseTags(encodeTags(tags)))
    }

    @Test
    fun `an empty set encodes and parses as nothing`() {
        assertEquals("", encodeTags(emptySet()))
        assertEquals(emptySet<String>(), parseTags(""))
    }

    /**
     * **The point of tagging.** A recording's name is a timestamp, so it answers "when" and nothing
     * else; the tag is what a person will actually go looking for.
     */
    @Test
    fun `a search matches a tag as well as a name`() {
        val name = "logline-2026-08-21T104536.mcap"
        val tags = setOf("quay trial", "engine run")

        assertTrue(matchesQuery(name, "quay", tags))
        assertTrue("case is ignored, as for names", matchesQuery(name, "ENGINE", tags))
        assertTrue("the name still matches", matchesQuery(name, "0821", tags))
        assertTrue("and separators are still ignored", matchesQuery(name, "quaytrial", tags))
    }

    @Test
    fun `a search matching neither name nor tag finds nothing`() {
        assertTrue(!matchesQuery("logline-2026-08-21T104536.mcap", "zzz", setOf("quay trial")))
    }

    /** An untagged recording is still found by its name. */
    @Test
    fun `a recording with no tags is unaffected`() {
        assertTrue(matchesQuery("logline-2026-08-21T104536.mcap", "0821", emptySet()))
        assertTrue(!matchesQuery("logline-2026-08-21T104536.mcap", "quay", emptySet()))
    }
}
