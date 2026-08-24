package se.rise.logline

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.ui.chartHeight

/**
 * The live chart's collapsed height, which used to be a pinned 400dp.
 *
 * Every figure below was measured on a Pixel 6 with uiautomator — the scroll viewport between the top
 * bar and the navigation bar, read off the `scrollable="true"` node — rather than derived from a screen
 * size on paper. Three bounds interact in one expression and they disagree only on devices nobody has in
 * hand, which is what makes this worth pinning: a chart of the wrong height still looks like a chart.
 */
class ChartHeightTest {

    /** Pixel 6, portrait: 1831 px at 2.625 = 698dp of viewport, where the old constant was tuned. */
    private val pixel6Portrait = 698.dp

    /** The same phone turned sideways: 565 px, 215dp. The case the ceiling exists for. */
    private val pixel6Landscape = 215.dp

    /** 720x1280 at density 320, i.e. a 360x640dp phone: 876 px at 2.0 = 438dp. */
    private val smallPhone = 438.dp

    /**
     * **The phone it was tuned on keeps its layout**, which is the whole claim of replacing a number
     * with a proportion — 0.57 was chosen as what 400dp already was, so this must land within a dp or
     * two of it. Measured after the change, the readouts below the chart moved 6 px.
     */
    @Test
    fun `the reference phone keeps the height the pinned constant gave it`() {
        val h = chartHeight(pixel6Portrait)
        assertTrue("$h should be within 3dp of the old 400dp", (h.value - 400f) in -3f..3f)
    }

    /**
     * The bug being fixed: a pinned 400dp is 91% of a small phone's viewport, so the three navigation
     * values the hierarchy puts directly under the chart fall below the fold on the one device with the
     * least room. A proportion leaves them on screen.
     */
    @Test
    fun `a small phone gets a proportionally shorter chart, not the same one`() {
        val h = chartHeight(smallPhone)
        assertEquals(250f, h.value, 1f)
        assertTrue("$h must leave room for the readouts", h < smallPhone * 0.6f)
        assertTrue("and 400dp did not", 400.dp > smallPhone * 0.9f)
    }

    /**
     * **The floor is not a suggestion.** Below it the chart stops being something to navigate by, so it
     * keeps its height and the readouts move below the fold instead.
     */
    @Test
    fun `the floor holds where the proportion would go under it`() {
        // 300dp of viewport: 0.57 would give 171.
        assertEquals(MIN, chartHeight(300.dp).value, 0.5f)
    }

    /**
     * **And the ceiling beats the floor**, which is the precedence that matters. In landscape the floor
     * alone made the chart 112% of the viewport, so the fix line attached under it — part of the same
     * instrument, inside the same surface — could not be reached without scrolling.
     */
    @Test
    fun `the ceiling wins over the floor in landscape`() {
        val h = chartHeight(pixel6Landscape)
        assertEquals(172f, h.value, 1f)
        assertTrue("the fix line has to stay on screen", h < pixel6Landscape)
        assertTrue("which the floor alone did not manage", MIN.dp > pixel6Landscape)
    }

    /** A degenerate viewport must not produce a negative or a throw — `coerceIn` would have thrown. */
    @Test
    fun `a viewport of nothing gives nothing rather than throwing`() {
        assertEquals(0f, chartHeight(0.dp).value, 0f)
    }

    private companion object {
        const val MIN = 240f
    }
}
