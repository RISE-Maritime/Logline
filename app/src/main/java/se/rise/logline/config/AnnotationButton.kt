package se.rise.logline.config

/**
 * How serious a mark is, and the one thing a Foxglove reader can filter on unconditionally.
 *
 * Maps straight onto `foxglove.Log.Level`. The Log panel's minimum-severity control is "always
 * enforced, even if the node name is selected or the message text matches the search filter" — so this
 * is not decoration, it is the coarse filter somebody scrubbing a six-hour recording actually reaches
 * for. Three levels rather than six: `foxglove.Log` also has DEBUG and FATAL, and neither means
 * anything coming off a human pressing a button on a phone.
 */
enum class AnnotationSeverity(val label: String) {
    Info("Info"),
    Warning("Warning"),
    Error("Error"),
}

/**
 * One configurable marker button.
 *
 * The three fields are chosen to land on the three things Foxglove's Log panel can filter by:
 * [severity] becomes `level`, [category] becomes `name` — which the panel lists as a namespace
 * checkbox — and [label] becomes the `message` text, which is what its search matches.
 *
 * [category] is deliberately not the label. The panel shows one toggle per distinct `name`, so a
 * unique string per button would grow that list until it was useless; a small stable set of categories
 * is what makes it a filter rather than a legend.
 */
data class AnnotationButton(
    val label: String,
    val severity: AnnotationSeverity,
    val category: String,
)

/**
 * The category used for a typed note, and for the automatic mark at the start of a run.
 *
 * Separate categories on purpose: `system` is the app talking, `note` is a person typing, and a
 * reader who wants only the operator's own marks can switch the first off in one click.
 */
const val NOTE_CATEGORY = "note"
const val SYSTEM_CATEGORY = "system"

/** What a category may contain — it is a Foxglove namespace, and it is read at a glance in a list. */
private val CATEGORY_PATTERN = Regex("[a-z0-9_]{1,32}")

fun isValidCategory(category: String): Boolean = CATEGORY_PATTERN.matches(category)

/**
 * Tab-separated, one button per line — the same single-key, newline-delimited shape as the endpoint
 * list. Neither a tab nor a newline can be typed into the fields that feed this, and both sides trim.
 *
 * Severity first so a truncated line loses the label rather than the meaning, and so the stored value
 * sorts and diffs readably.
 */
fun AnnotationButton.serialise(): String = "${severity.name}\t$category\t$label"

/**
 * A line that does not parse is dropped rather than thrown on, the same way an unrecognised QoS
 * override is read as "no override": a preference file written by an older build, or one where a
 * severity has since been renamed, must not be able to stop the app reading its settings.
 */
fun parseAnnotationButton(stored: String?): AnnotationButton? {
    val parts = stored?.split('\t') ?: return null
    if (parts.size != 3) return null
    val severity = AnnotationSeverity.entries.firstOrNull { it.name == parts[0].trim() } ?: return null
    val category = parts[1].trim()
    val label = parts[2].trim()
    if (!isValidCategory(category) || label.isEmpty()) return null
    return AnnotationButton(label = label, severity = severity, category = category)
}
