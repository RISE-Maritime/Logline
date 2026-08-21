package se.rise.logline.ui

import se.rise.logline.record.RecordingFacts

/**
 * Which recordings a screenful of 119 files should actually show, and in what order.
 *
 * Kept out of the composable and free of Android so the awkward parts — an unknown duration, a query
 * of punctuation, a tie between two files saved in the same second — are decided somewhere a test can
 * reach them.
 */

/** The orders worth offering, in the order they are offered. */
enum class RecordingSort(val label: String) {
    /** The default, and the one a run just finished is found by. */
    Newest("Newest first"),
    Oldest("Oldest first"),

    /** What the disk is full of. */
    Largest("Largest first"),

    /**
     * What an actual trial looks like next to a two-minute bench test — the question "which of these is
     * the real run" that a list of 119 files cannot otherwise answer.
     */
    Longest("Longest first"),
}

/** Whether a recording closed cleanly. */
enum class RecordingFilter(val label: String) {
    All("All"),

    /** Has a summary section: statistics, channels, the lot. */
    Complete("Complete"),

    /**
     * No summary section, which `McapRecovery.finalise` leaves behind for every run a killed process
     * interrupted. Worth being able to see on their own: they are the runs that ended badly, and they
     * are also the ones whose figures the list cannot state.
     */
    Incomplete("Incomplete"),
}

/**
 * Whether a name answers a search.
 *
 * **Separators are ignored on both sides**, so `20260821`, `2026-08-21` and `08-21` all find
 * `logline-2026-08-21T104536.mcap`. A recording's name *is* its timestamp — that is the only thing
 * distinguishing one from another in the list — so a search that insisted on the exact punctuation
 * would be a date search that rejects dates.
 *
 * A query that is nothing but punctuation normalises to empty and therefore matches everything, which
 * is the same answer an empty box gives and the only sensible one: it expresses no constraint.
 */
fun matchesQuery(name: String, query: String): Boolean {
    val wanted = normalise(query)
    return wanted.isEmpty() || normalise(name).contains(wanted)
}

/** The list as the screen should show it: filtered, searched, then ordered. */
fun <T : RecordingFacts> visibleRecordings(
    all: List<T>,
    query: String,
    sort: RecordingSort,
    filter: RecordingFilter,
): List<T> {
    val kept = all.filter { recording ->
        val passesFilter = when (filter) {
            RecordingFilter.All -> true
            RecordingFilter.Complete -> recording.isComplete
            RecordingFilter.Incomplete -> !recording.isComplete
        }
        passesFilter && matchesQuery(recording.name, query)
    }
    // Every order breaks its ties by newest, so two files of the same size do not swap places between
    // recompositions — a list that reshuffles under the thumb is one nobody trusts.
    return when (sort) {
        RecordingSort.Newest -> kept.sortedByDescending { it.savedAtMillis }
        RecordingSort.Oldest -> kept.sortedBy { it.savedAtMillis }
        RecordingSort.Largest -> kept.sortedWith(
            compareByDescending<T> { it.sizeBytes }.thenByDescending { it.savedAtMillis }
        )
        // **An unknown duration sorts last, not as zero.** An incomplete recording has no summary and so
        // no duration, and it may well be the longest run of the lot — it is the one that was still
        // going when the process died. Ranking it as a zero-second run would put the very files
        // somebody is looking for at the bottom while claiming to order by length.
        RecordingSort.Longest -> kept.sortedWith(
            compareByDescending<T> { it.durationMillis ?: UNKNOWN_LAST }
                .thenByDescending { it.savedAtMillis }
        )
    }
}

/**
 * What the list says about itself: `119 recordings`, or `23 of 119` once something is narrowing it.
 *
 * The total is stated whenever it differs, because a search that quietly hides ninety-six files looks
 * exactly like a phone that has lost them.
 */
fun recordingsCount(shown: Int, total: Int): String = when {
    shown == total -> formatCounted(total.toLong(), "recording")
    else -> "$shown of ${formatCounted(total.toLong(), "recording")}"
}

/** Lower-cased with everything that is not a letter or a digit removed. */
private fun normalise(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }

/** Sorts before every real duration under `sortedByDescending`. */
private const val UNKNOWN_LAST = -1L
