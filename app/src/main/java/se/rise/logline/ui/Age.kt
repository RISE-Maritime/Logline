package se.rise.logline.ui

import java.util.concurrent.TimeUnit

/**
 * How long ago a subject last published, in units a glance can use.
 *
 * Coarse on purpose. The age is driven by a one-second ticker, so millisecond precision would be an
 * artifact of the tick rather than a measurement — a live 55 Hz subject would flicker between "0 ms"
 * and "900 ms". Sub-second liveness is already answered by the achieved-rate line beside it; what this
 * display is for is spotting a subject that has *stopped*.
 *
 * @param nowMillis passed in rather than read here, so the result is a function of its inputs and the
 *   caller controls when it changes.
 */
internal fun formatAge(lastPublishEpochMillis: Long, nowMillis: Long): String {
    if (lastPublishEpochMillis == 0L) return "—"

    val ageMillis = nowMillis - lastPublishEpochMillis
    // A backwards clock step is possible: the boot→epoch offset is re-read per sample, so a mid-run
    // NTP correction can put "last publish" in the future. Report it as fresh rather than negative.
    if (ageMillis < JUST_NOW_MILLIS) return "just now"

    val seconds = TimeUnit.MILLISECONDS.toSeconds(ageMillis)
    if (seconds < 60) return "$seconds s ago"

    val minutes = TimeUnit.MILLISECONDS.toMinutes(ageMillis)
    if (minutes < 60) return "$minutes min ago"

    return "${TimeUnit.MILLISECONDS.toHours(ageMillis)} h ago"
}

private const val JUST_NOW_MILLIS = 2_000L
