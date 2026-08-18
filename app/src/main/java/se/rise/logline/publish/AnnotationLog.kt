package se.rise.logline.publish

import se.rise.logline.config.AnnotationSeverity

/** One mark, as the screen shows it back. */
data class Annotation(
    /** When the button was pressed — the same instant that went into the payload. */
    val atEpochMillis: Long,
    val message: String,
    val severity: AnnotationSeverity,
    val category: String,
)

/**
 * The marks made during this run, newest last, for the annotation screen to show back.
 *
 * **Pulled, never pushed**, for the same reason [LiveSampleStore] is: nothing on the publish path may
 * drive recomposition. A mark is a rare event and a `StateFlow` here would be harmless in isolation —
 * but the rule is what keeps that true, and a screen that reads one store on a ticker and another by
 * subscription is two mechanisms where one will do.
 *
 * `synchronized` for visibility rather than for races: [add] runs on `Dispatchers.Default` and can
 * migrate threads, while [recent] is read from Main. This is the same reasoning as the sample rings.
 *
 * Bounded, and small: this is a confirmation that a press registered, not a second copy of the
 * recording. Everything ever marked is in the MCAP file.
 */
class AnnotationLog(private val capacity: Int = CAPACITY) {

    private val lock = Any()
    private val entries = ArrayDeque<Annotation>()

    /** How many marks this run has made, including any evicted from the list above. */
    private var total = 0

    fun add(annotation: Annotation) = synchronized(lock) {
        entries.addLast(annotation)
        total++
        while (entries.size > capacity) entries.removeFirst()
    }

    /** A snapshot, oldest first. The caller reverses it if it wants newest first. */
    fun recent(): List<Annotation> = synchronized(lock) { entries.toList() }

    fun count(): Int = synchronized(lock) { total }

    fun clear() = synchronized(lock) {
        entries.clear()
        total = 0
    }

    private companion object {
        const val CAPACITY = 100
    }
}
