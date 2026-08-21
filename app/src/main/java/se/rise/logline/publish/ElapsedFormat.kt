package se.rise.logline.publish

import java.util.Locale

/**
 * A span as a clock: `00:12:34`.
 *
 * **Here rather than in `ui/` because two callers need it and only one of them is a screen.** The Events
 * tab prints it beside a running timer; `SensorPublisher` puts it in the message a timed mark carries,
 * and `publish` cannot reach into `ui` without inverting the dependency. A second implementation is
 * exactly what this codebase keeps warning about, so there is one.
 *
 * Not a rounded estimate like `formatRuntimeLeft`, which reads to the minute because a figure off a fuel
 * gauge has no business claiming seconds. This is a measurement, and it should read the way the live
 * clock beside it reads — a run showing `00:12:34` while going should not become "13 min" the moment it
 * stops.
 *
 * `Locale.ROOT`, for the same reason every other formatter in this app uses it: the digits are the same
 * everywhere and a locale-dependent separator here would only be a way to differ between phones.
 */
fun formatElapsed(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}
