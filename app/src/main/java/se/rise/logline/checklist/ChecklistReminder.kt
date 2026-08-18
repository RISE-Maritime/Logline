package se.rise.logline.checklist

/**
 * A reminder to come back to one checklist item.
 *
 * **Phone-local, and never published.** No checklist message carries a due time — not
 * `ChecklistProcedure`, which is the shared definition, and not `ChecklistState`, which is progress —
 * so putting one on the bus would mean extending the protocol for a thing no other client can show.
 * What the other sites see is the completion event when the reminder does its job.
 *
 * [repeatMinutes] of zero is a one-shot; anything else re-arms at that interval until the item is
 * completed. Completing an item cancels its reminder — an alarm for something already done is the
 * fastest way to teach someone to ignore alarms.
 */
data class ChecklistReminder(
    val procedureId: String,
    val itemId: String,
    val dueAtEpochMillis: Long,
    val repeatMinutes: Int = 0,
) {
    /** Stable across a re-arm, so it can address an `AlarmManager` slot and a notification id. */
    val key: String get() = "$procedureId/$itemId"

    fun next(fromEpochMillis: Long): ChecklistReminder? {
        if (repeatMinutes <= 0) return null
        val step = repeatMinutes * 60_000L
        // Wound forward past any firings missed while the device was off, rather than replaying them.
        var due = dueAtEpochMillis + step
        while (due <= fromEpochMillis) due += step
        return copy(dueAtEpochMillis = due)
    }
}

/**
 * Tab-separated, one reminder per line — the same single-key, newline-delimited shape as the endpoint
 * list and the annotation buttons. Neither character can occur in an id.
 */
fun ChecklistReminder.serialise(): String =
    "$procedureId\t$itemId\t$dueAtEpochMillis\t$repeatMinutes"

/** A line that does not parse is dropped, not thrown on — see `parseAnnotationButton` for why. */
fun parseChecklistReminder(stored: String?): ChecklistReminder? {
    val parts = stored?.split('\t') ?: return null
    if (parts.size != 4) return null
    val procedureId = parts[0].trim()
    val itemId = parts[1].trim()
    val due = parts[2].trim().toLongOrNull() ?: return null
    val repeat = parts[3].trim().toIntOrNull() ?: return null
    if (procedureId.isEmpty() || itemId.isEmpty() || due <= 0 || repeat < 0) return null
    return ChecklistReminder(procedureId, itemId, due, repeat)
}
