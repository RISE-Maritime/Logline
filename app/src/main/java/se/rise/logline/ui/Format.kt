package se.rise.logline.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

/**
 * How far a unit sits from the number it belongs to.
 *
 * Degrees, percent and an SI-prefixed rate are written tight against the figure — `57°`, `89%`,
 * `219Mbit/s` — and a word unit takes a space: `0.3 kn`. There were two of these, one in the live
 * view's card header and one in its dashboard readings, each with its own list of exceptions, which
 * is how `0.3 kn` and `2.2Gbit/s` came to be spaced by different rules on the same screen.
 */
internal fun unitGap(unit: String): Dp =
    if (unit == "°" || unit == "%" || unit == "bit/s") 0.dp else 3.dp

/**
 * A bearing at a fixed three digits: `000`, `047`, `315`.
 *
 * The zero padding is not decoration. Three values sit side by side on the live dashboard and the row
 * is centred on each, so an unpadded course stepping from `9` to `10` to `100` shifts its neighbours as
 * it goes — a readout that twitches while the phone turns reads as unreliable, whatever the numbers
 * say. Every marine instrument pads for the same reason.
 *
 * Wrapped into 0-359 first: a platform is free to hand back `360.0`, and `360°` is a bearing nobody
 * writes.
 */
internal fun formatBearing(degrees: Float): String {
    val wrapped = ((degrees % 360f) + 360f) % 360f
    // Rounded before the modulo would be wrong: 359.7 rounds to 360, which has to come back to 000.
    return "%03d".fmt(Math.round(wrapped) % 360)
}

/**
 * A wall-clock time, for "when did this start".
 *
 * The device's own zone and a fixed 24-hour pattern: this sits beside an elapsed duration on the same
 * card, and a locale that chose `10:31:04 PM` would make the pair read as two different kinds of
 * number. [DateTimeFormatter] rather than `SimpleDateFormat` because it is immutable and thread-safe,
 * and this is called once a second from recomposition.
 */
private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

internal fun formatClock(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(CLOCK_FORMAT)
