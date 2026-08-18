package se.rise.logline

import se.rise.logline.config.AnnotationButton
import se.rise.logline.config.AnnotationSeverity
import se.rise.logline.config.isValidCategory
import se.rise.logline.config.parseAnnotationButton
import se.rise.logline.config.serialise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of a button.
 *
 * A line that will not parse is dropped rather than thrown on, which is the same stance the QoS
 * overrides take: a preference file written by an older build must never be able to stop the app
 * reading its settings. That makes "what counts as unparseable" worth pinning, since the failure is
 * otherwise silent — a button quietly missing after an upgrade.
 */
class AnnotationButtonTest {

    @Test
    fun `a button survives a round trip`() {
        val button = AnnotationButton("Passing buoy 4", AnnotationSeverity.Warning, "navigation")

        assertEquals(button, parseAnnotationButton(button.serialise()))
    }

    @Test
    fun `every severity survives a round trip`() {
        AnnotationSeverity.entries.forEach { severity ->
            val button = AnnotationButton("Mark", severity, "note")
            assertEquals(button, parseAnnotationButton(button.serialise()))
        }
    }

    /** Tab-separated, severity first — the shape a stored value has to keep to stay readable. */
    @Test
    fun `the stored form is severity, category, label`() {
        assertEquals(
            "Info\tnavigation\tWaypoint",
            AnnotationButton("Waypoint", AnnotationSeverity.Info, "navigation").serialise(),
        )
    }

    @Test
    fun `a label containing spaces and punctuation is preserved`() {
        val button = AnnotationButton("Wake — 2 m, port side", AnnotationSeverity.Error, "incident")

        assertEquals(button, parseAnnotationButton(button.serialise()))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            AnnotationButton("Waypoint", AnnotationSeverity.Info, "navigation"),
            parseAnnotationButton("  Info \t navigation \t Waypoint  "),
        )
    }

    @Test
    fun `a malformed line parses to null rather than throwing`() {
        assertNull("no separators", parseAnnotationButton("Waypoint"))
        assertNull("truncated", parseAnnotationButton("Info\tnavigation"))
        assertNull("too many fields", parseAnnotationButton("Info\tnav\tWaypoint\textra"))
        assertNull("empty", parseAnnotationButton(""))
        assertNull("null", parseAnnotationButton(null))
    }

    /** A severity renamed or removed upstream of a stored file must read as absent, not crash. */
    @Test
    fun `an unrecognised severity parses to null`() {
        assertNull(parseAnnotationButton("CRITICAL\tnavigation\tWaypoint"))
        assertNull("case matters, as it does for every other stored enum here",
            parseAnnotationButton("INFO\tnavigation\tWaypoint"))
    }

    @Test
    fun `a blank label or an invalid category parses to null`() {
        assertNull("blank label", parseAnnotationButton("Info\tnavigation\t   "))
        assertNull("upper case category", parseAnnotationButton("Info\tNavigation\tWaypoint"))
        assertNull("spaced category", parseAnnotationButton("Info\tnav igation\tWaypoint"))
        assertNull("empty category", parseAnnotationButton("Info\t\tWaypoint"))
    }

    /**
     * The category is a Foxglove namespace, shown as a filter toggle. It stays to what reads cleanly
     * in that list — and short, because the panel does not wrap it.
     */
    @Test
    fun `category validation accepts lower case, digits and underscores only`() {
        assertTrue(isValidCategory("navigation"))
        assertTrue(isValidCategory("deck_2"))
        assertTrue(isValidCategory("note"))

        assertFalse("empty", isValidCategory(""))
        assertFalse("upper case", isValidCategory("Navigation"))
        assertFalse("space", isValidCategory("sea state"))
        assertFalse("hyphen", isValidCategory("sea-state"))
        assertFalse("slash would split a key", isValidCategory("deck/2"))
        assertFalse("too long", isValidCategory("a".repeat(33)))
    }
}
