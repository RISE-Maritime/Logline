package se.rise.logline.ui

import se.rise.logline.publish.SampleWindow

/**
 * One stretch during which a subject held the same value.
 *
 * [endMillis] is the timestamp of the **last sample carrying this value**, not the first sample of the
 * next one. The two differ by one sample interval, and using the next segment's start would overstate
 * every duration by it — at 0.2 Hz that is five seconds added to each, which is visible.
 */
data class StateSegment(
    val value: Float,
    val startMillis: Long,
    val endMillis: Long,
    /** How many samples carried it. Not the duration — a held reading republishes. */
    val samples: Int,
) {
    /**
     * How long it held.
     *
     * Zero for a single sample, which is honest: one reading says the value existed at an instant, not
     * that it lasted. A run of one is drawn as a hairline rather than given a width it did not have.
     */
    val durationMillis: Long get() = endMillis - startMillis
}

/**
 * Collapse a window into the stretches where its value did not change.
 *
 * The whole of the timeline presentation, kept as a pure function over the same [SampleWindow] the
 * plots use so it can be tested without a running publisher.
 *
 * **Compared exactly, not with a tolerance.** Every subject that reaches here stores an enum ordinal, a
 * boolean or an identifier — values that are whole numbers by construction and arrive through the same
 * float conversion every time. A tolerance would merge two adjacent cell ids, which is precisely the
 * transition the screen exists to show.
 */
fun stateSegments(window: SampleWindow): List<StateSegment> {
    if (window.isEmpty) return emptyList()
    val out = mutableListOf<StateSegment>()
    var value = window.values[0]
    var start = window.timesMillis[0]
    var end = window.timesMillis[0]
    var count = 1
    for (i in 1 until window.size) {
        val v = window.values[i]
        if (v == value) {
            end = window.timesMillis[i]
            count++
            continue
        }
        out += StateSegment(value, start, end, count)
        value = v
        start = window.timesMillis[i]
        end = window.timesMillis[i]
        count = 1
    }
    out += StateSegment(value, start, end, count)
    return out
}

/**
 * The distinct values in a set of segments, in the order they were **first seen**.
 *
 * One row per value on the bar, and the order has to be stable: sorting numerically would have the rows
 * jump about as a new cell id appears mid-run, and sorting by frequency would reorder them as the
 * window slides. First-seen keeps a row where the eye last left it.
 */
fun timelineRows(segments: List<StateSegment>): List<Float> {
    val seen = LinkedHashSet<Float>()
    segments.forEach { seen += it.value }
    return seen.toList()
}

/**
 * The span the bar is drawn against.
 *
 * Null when there is nothing to draw or when every sample landed on one instant — which is not a corner
 * case: a subject that has published once has a zero span, and dividing by it would put every segment
 * at infinite width.
 */
fun timelineSpanMillis(segments: List<StateSegment>): Long? {
    if (segments.isEmpty()) return null
    val span = segments.last().endMillis - segments.first().startMillis
    return span.takeIf { it > 0 }
}
