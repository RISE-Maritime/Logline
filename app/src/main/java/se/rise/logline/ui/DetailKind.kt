package se.rise.logline.ui

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects

/**
 * How a subject's history should be *drawn* — which is not the same question as what it measures.
 *
 * The live view stores every subject as a float, because the publish path cannot afford anything else
 * (see `LiveSampleStore`). That is the right storage and the wrong presentation for two of them: a
 * float that is really an enum, and a float that is really the *length* of a string.
 */
enum class DetailKind {
    /** A measurement over time. Almost everything. */
    Plot,

    /**
     * Something a person reads rather than plots.
     *
     * The stored float for these is the sentence length, which plots as a meaningless staircase — the
     * text itself is kept separately and only for the subjects listed here.
     */
    Text,

    /**
     * A value that holds, then changes: an enum, a boolean, or an identifier.
     *
     * A line between "2D" and "3D" draws a slope through a state that has no intermediate value, and
     * the reading anybody wants from one of these is *when it changed and how long it held*, which a
     * line does not answer at all.
     */
    Timeline,
}

/**
 * Which of the three a subject gets.
 *
 * One function so the card and the detail screen cannot disagree — the card decides whether to draw a
 * sparkline at all from the same answer the screen branches on, which is what stops a subject being
 * plotted in one place and tabulated in the other.
 *
 * `log_message` deliberately needs no case: `SensorPublisher.mark()` calls `emit` with no value, so it
 * records nothing to the live store and never produces a card to tap.
 */
fun detailKind(entry: PublishedSubject): DetailKind = when (entry.subject) {
    Subjects.RAW_NMEA0183 -> DetailKind.Text

    // The two genuine states: an enum with three values and a boolean.
    Subjects.LOCATION_FIX_QUALITY,
    Subjects.BATTERY_IS_CHARGING -> DetailKind.Timeline

    // Identifiers, not quantities. A cell id is a *name* that happens to be a number — the difference
    // between 2 118 401 and 2 118 407 is not seven of anything, so plotting them draws a staircase
    // whose height means nothing. What a cell id is read for on a moving vessel is handovers, which is
    // exactly what a timeline shows. EARFCN is the channel and moves with the cell.
    Subjects.RADIO_CELL_ID,
    Subjects.RADIO_PHYSICAL_CELL_ID,
    Subjects.RADIO_EARFCN -> DetailKind.Timeline

    else -> DetailKind.Plot
}
