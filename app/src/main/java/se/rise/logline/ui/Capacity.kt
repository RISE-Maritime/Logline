package se.rise.logline.ui

import se.rise.logline.config.Settings
import se.rise.logline.keelson.Subjects
import se.rise.logline.record.MIN_FREE_BYTES
import se.rise.logline.sensors.toIntervalMillis

/**
 * What every subject except audio and the camera costs per hour, measured at the default rates.
 *
 * The figure the README quotes, and until now spelled into three separate sentences of prose. It is
 * deliberately the *whole* set: a run with half the subjects switched off writes less than this, so
 * the capacity below reads short rather than long — which is the direction an estimate about running
 * out of room should err in.
 */
internal const val BASE_MEGABYTES_PER_HOUR = 77

/** The publisher's own bounds, mirrored so a quoted data rate is the one that will actually run. */
internal const val MIN_FRAME_INTERVAL_MILLIS = 500L
internal const val MAX_FRAME_INTERVAL_MILLIS = 600_000L

/**
 * What a run configured like this will write per hour.
 *
 * The two optional subjects dominate when they are on — audio is about 109 MB/h at the default and the
 * camera about 158 MB/h — so a capacity estimate that ignored them would be out by a factor of four on
 * the settings that most need the warning.
 */
internal fun megabytesPerHour(settings: Settings): Int {
    val frameHz = 1_000.0 / settings.rate(Subjects.IMAGE_COMPRESSED)
        .toIntervalMillis()
        .coerceIn(MIN_FRAME_INTERVAL_MILLIS, MAX_FRAME_INTERVAL_MILLIS)
    return BASE_MEGABYTES_PER_HOUR +
        (if (settings.audioEnabled) audioMegabytesPerHour(settings.audioSampleRateHz, settings.audioChannels) else 0) +
        (if (settings.cameraEnabled) cameraMegabytesPerHour(settings.cameraWidth, settings.cameraHeight, frameHz) else 0)
}

/**
 * How long the free space lasts at that rate, in milliseconds, or null when there is none to be had.
 *
 * The fuel is the space **above** [MIN_FREE_BYTES], not the whole volume, because that floor is where
 * the recorder refuses to open the next file — which is the moment being predicted. A phone already
 * below it has no recording time at all, however many bytes the volume still reports.
 *
 * Arithmetic, not measurement, and the two are not interchangeable. This is what a phone standing
 * still can say *before* a run; once one is going, `RecordingStatus.spaceRuntime` measures the real
 * fill rate — including whatever else on the phone is filling the same volume — and should be
 * preferred wherever both exist.
 */
internal fun recordingCapacityMillis(freeBytes: Long, megabytesPerHour: Int): Long? {
    if (megabytesPerHour <= 0) return null
    val fuel = freeBytes - MIN_FREE_BYTES
    if (fuel <= 0L) return 0L
    val bytesPerHour = megabytesPerHour.toLong() * 1_048_576L
    return (fuel.toDouble() / bytesPerHour * 3_600_000.0).toLong()
}

/**
 * A capacity, at the precision a capacity deserves.
 *
 * Not [formatRuntimeLeft], which is for a measured drain and rounds to the minute: a volume with three
 * weeks of room on it would read "528 h" there, which is a number nobody can picture. This says days
 * past two of them and never claims minutes — the rate it divides by is an average of a set of
 * subjects, so the third significant figure is noise.
 *
 * Hours run all the way to 48 rather than stopping at 24, so "1 day" never appears: a day and a half
 * reads "36 h", which is more use to somebody deciding whether a passage fits.
 */
internal fun formatCapacity(millis: Long): String {
    val minutes = millis / 60_000L
    if (minutes < 60L) return "under an hour"
    val hours = minutes / 60L
    if (hours < 48L) return "$hours h"
    return formatCounted(hours / 24L, "day")
}

/**
 * Bytes as a person reads them: `42.1 GB`, `512 MB`.
 *
 * Powers of 1024 with the short names, matching what the rest of the app already prints for a file
 * size — the point is that the recording figure and the free-space figure beside it are the same kind
 * of number, not that the units are pedantically correct.
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "unknown"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "%.1f GB".fmt(bytes / gb)
        bytes >= mb -> "%.0f MB".fmt(bytes / mb)
        bytes >= kb -> "%.0f kB".fmt(bytes / kb)
        else -> formatCounted(bytes, "byte")
    }
}
