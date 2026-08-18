package se.rise.logline.ui

import java.util.Locale

/**
 * `String.format` with a decimal point, on every phone.
 *
 * Kotlin's `String.format` extension formats with `Locale.getDefault()`, so on a Swedish phone
 * `"%.1f".format(55.3)` reads `55,3` — and a comma is the thousands separator to the other half of
 * the world, so `1,5` V is ambiguous rather than merely foreign. These are readings off a sensor bus
 * that are read next to the numbers keelson itself prints, and those are always point-decimal.
 * `Locale.ROOT` is the same choice `Double.json()` in `calibrate/PlatformGeometryJson.kt` makes.
 *
 * Use this for every numeric format string in the UI; a plain `.format()` is the bug.
 */
internal fun String.fmt(vararg args: Any?): String = String.format(Locale.ROOT, this, *args)

/**
 * A sample count a glance can take in: `681 204`, not `681204`.
 *
 * Grouped with a narrow no-break space (U+202F) rather than a comma or a plain space — a comma reads as
 * a decimal point to half of Europe, and an ordinary space lets a number wrap across two lines.
 */
internal fun formatCount(count: Long): String {
    val digits = count.toString()
    if (digits.length <= 4) return digits
    val negative = digits.startsWith("-")
    val body = if (negative) digits.substring(1) else digits
    val grouped = body.reversed().chunked(3).joinToString(" ").reversed()
    return if (negative) "-$grouped" else grouped
}

/**
 * A rate with as much precision as it means and no more.
 *
 * A measured average of 217.0233 samples/s is not accurate to four decimals, and a 0.02 Hz subject
 * would round to a flat `0.0` at one — so the number of decimals follows the magnitude.
 */
/**
 * A count with its noun, singular when it should be.
 *
 * "1 samples" is the kind of thing that makes a careful reader distrust every other number on the
 * screen, and these screens are read for exactly that — whether the numbers can be trusted.
 */
internal fun formatCounted(count: Long, singular: String, plural: String = "${singular}s"): String =
    "${formatCount(count)} ${if (count == 1L) singular else plural}"

internal fun formatRate(hz: Double): String = when {
    hz >= 100 -> "%.0f".fmt(hz)
    hz >= 0.1 -> "%.1f".fmt(hz)
    else -> "%.2f".fmt(hz)
}

/**
 * A remaining-time estimate, at the precision an estimate deserves.
 *
 * Never seconds, and never a decimal: this number comes from a fuel gauge that moves in steps, and
 * "3 h 42 min" already claims more than it knows. Rounded to the nearest minute below an hour and to
 * whole hours past a day, where the minutes are noise.
 */
internal fun formatRuntimeLeft(millis: Long): String {
    val minutes = (millis + 30_000L) / 60_000L
    if (minutes < 1) return "under a minute"
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    if (hours >= 24) return "$hours h"
    val rest = minutes % 60
    return if (rest == 0L) "$hours h" else "$hours h $rest min"
}
