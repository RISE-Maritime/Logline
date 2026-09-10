package se.rise.logline.ui

import se.rise.logline.publish.formatElapsed
import se.rise.logline.record.RecordingFacts
import se.rise.logline.record.RecordingKind
import se.rise.logline.record.recordingKindOf

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
fun matchesQuery(name: String, query: String, tags: Set<String> = emptySet()): Boolean {
    val wanted = normalise(query)
    if (wanted.isEmpty()) return true
    if (normalise(name).contains(wanted)) return true
    // Tags are searched too, which is most of why they are worth typing: a name is a timestamp and a
    // tag is what the run *was*, so "quay trial" has to find the recording somebody labelled that.
    return tags.any { normalise(it).contains(wanted) }
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
            // **Both compare against a value, so null falls out of each.** A settings profile or a
            // platform-geometry export has no MCAP summary and so no answer to "did it close properly";
            // it therefore shows under All and under neither of the other two, which is what keeps
            // it out of the bulk delete built on the Incomplete set.
            RecordingFilter.Complete -> recording.isComplete == true
            RecordingFilter.Incomplete -> recording.isComplete == false
        }
        passesFilter && matchesQuery(recording.name, query, recording.tags)
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

/**
 * What a row says about a file under its name.
 *
 * Lives here rather than in the screen because this is where the mislabel was: every JSON export in
 * `Downloads/Logline` used to read `982 B · no summary`, which describes a recording that failed and
 * not a settings profile that is exactly as it should be. A file that is not a recording now says what
 * it is instead.
 *
 * A recording with no summary reads **`incomplete, never closed`** rather than `no summary`, so the row
 * and the filter that selects it use the same word — and so the state reads as something that happened
 * to the run rather than as a missing field.
 */
fun recordingSubtitle(file: RecordingFacts): String = buildString {
    append(formatBytes(file.sizeBytes))
    append(" · ")
    when (recordingKindOf(file.name)) {
        RecordingKind.SettingsProfile -> append("Settings profile")
        RecordingKind.PlatformGeometry -> append("Platform geometry export")
        RecordingKind.PlatformLibrary -> append("Platform library export")
        RecordingKind.Other -> append("Not a recording")
        RecordingKind.Recording -> {
            // **The figures and the interruption are two separate facts**, and a rescued recording
            // now has both. It used to have neither: recovery left the file with no summary at all,
            // so `incomplete` was the only thing the row could say and it stood in for the count as
            // well. Recovery rebuilds the summary now, so an interrupted run states what it holds and
            // *then* says how it ended — which is the more useful half for somebody deciding whether
            // to keep it. A file whose summary cannot be read still says only the second.
            val messages = file.messages
            if (messages != null) {
                append(formatCounted(messages, "message"))
                val duration = file.durationMillis ?: 0L
                if (duration >= 1_000L) {
                    append(" over ")
                    append(formatElapsed(duration))
                }
            }
            if (file.isComplete != true) {
                if (messages != null) append(" · ")
                append("incomplete, never closed")
            }
        }
    }
}

/** Lower-cased with everything that is not a letter or a digit removed. */
private fun normalise(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }

/** Sorts before every real duration under `sortedByDescending`. */
private const val UNKNOWN_LAST = -1L
