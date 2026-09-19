package se.rise.logline.monitor

/**
 * Which topic a card reads for one of its inputs.
 *
 * Foxglove's `resolveInput`, ported once so every card resolves the same way: a **pinned** source
 * wins if it is on the bus; otherwise, with a slot asked for, the first source whose last chunk names
 * that slot; otherwise the first source seen for the subject, in sorted order so the choice does not
 * change between two ticks. Null when the entity publishes nothing on that subject — which the card
 * shows as `—`, never as zero.
 */
fun resolveTopic(
    available: List<Topic>,
    subject: String,
    pinnedSource: String = "",
    slot: Int? = null,
): Topic? {
    if (subject.isEmpty()) return null
    val candidates = available.filter { it.subject == subject }.sorted()
    if (pinnedSource.isNotEmpty()) {
        // A pin that is not on the bus (yet) still wins: silently reading another source while the
        // pinned one is quiet would put a different sensor's numbers under the name somebody chose.
        return candidates.firstOrNull { it.source == pinnedSource } ?: Topic(subject, pinnedSource)
    }
    if (slot != null) return candidates.firstOrNull { slotFromSourceId(it.source) == slot }
    return candidates.firstOrNull()
}

private val PORT_WORDS = setOf("port", "ps", "babord")
private val STARBOARD_WORDS = setOf("starboard", "stbd", "sb", "styrbord")

/**
 * The unit index a source id names in its last chunk: a number, or a side of the boat.
 *
 * Verbatim from `foxglove-custom-panels/src/conning/subjects.ts`, so a twin-engine vessel's port
 * engine is slot 0 in both tools.
 */
fun slotFromSourceId(sourceId: String): Int? {
    val last = sourceId.split('/').lastOrNull { it.isNotEmpty() }?.lowercase() ?: return null
    last.toIntOrNull()?.takeIf { it >= 0 && last.all(Char::isDigit) }?.let { return it }
    if (last in PORT_WORDS) return 0
    return if (last in STARBOARD_WORDS) 1 else null
}

/**
 * The unit a subject is published in, from its suffix — keelson states a unit in the subject name and
 * nowhere else. Empty for an unknown suffix: the number is shown bare rather than under a guessed unit,
 * which is the rule `keelsonSubject.ts` sets and the reason is the same — a wrong unit is a confident
 * claim nothing downstream can tell was invented.
 *
 * The Foxglove table plus the suffixes a phone-shaped entity adds. Matched **longest first**, which
 * is what makes `_mpss` beat `_mps` and `_dbm` beat `_m`.
 */
fun unitsForSubject(subject: String): String =
    SUFFIX_UNITS.firstOrNull { (suffix, _) -> subject.endsWith(suffix) }?.second.orEmpty()

private val SUFFIX_UNITS: List<Pair<String, String>> = listOf(
    "_newton_meter" to "N·m",
    "_mpss" to "m/s²",
    "_radps" to "rad/s",
    "_degps" to "°/s",
    "_gauss" to "G",
    "_newton" to "N",
    "_knots" to "kn",
    "_mps" to "m/s",
    "_deg" to "°",
    "_pct" to "%",
    "_m" to "m",
    "_rpm" to "rpm",
    "_pa" to "Pa",
    "_dbm" to "dBm",
    "_db" to "dB",
    "_celsius" to "°C",
    "_lux" to "lx",
    "_bps" to "bit/s",
    "_ms" to "ms",
    "_hz" to "Hz",
    "_volt" to "V",
    "_ampere" to "A",
    "_bytes" to "B",
).sortedByDescending { it.first.length }

/**
 * Degrees that wrap, which a plot has to unwrap and label as bearings. Not every `_deg`: roll and
 * pitch are signed angles that never pass 360, and labelling them as bearings would print a 3° heel
 * as `003°`.
 */
fun isCircularSubject(subject: String): Boolean =
    subject.endsWith("_deg") && CIRCULAR_WORDS.any { it in subject }

private val CIRCULAR_WORDS = listOf("heading", "course", "yaw_deg", "bearing", "direction", "wind_angle")
