package se.rise.logline.ui

import se.rise.logline.config.Settings
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects
import se.rise.logline.record.MIN_FREE_BYTES
import se.rise.logline.sensors.toIntervalMillis

/**
 * What every subject except audio and the camera costs per hour, at each of the three recording rates.
 *
 * Deliberately the *whole* set: a run with half the subjects switched off writes less, so the capacity
 * reads short rather than long — the direction an estimate about running out of room should err in.
 *
 * Three figures rather than one because recording has three modes spanning nearly three orders of
 * magnitude, which is precisely what the Session screen needs to be able to say out loud. All three are
 * measured on a Pixel 6 with zstd chunks. The maximum figure was first taken over 153 s (10.2 MB) and
 * has since been confirmed over a full hour: **254,479,728 bytes in 60.61 minutes is 240.2 MB/h**,
 * agreeing with the constant to 0.3%. Before compression the same run cost ~720 MB/h. The configured
 * rate was measured over a comparable run.
 */
internal const val MAX_MEGABYTES_PER_HOUR = 241

internal const val CONFIGURED_MEGABYTES_PER_HOUR = 23

/**
 * What a Minimum run costs — **measured, and small enough that it is not the constraint.**
 *
 * An hour in Minimum wrote 371,893 bytes, i.e. 0.361 MB/h across its eight channels; the ninth,
 * `battery_current_a`, adds about 0.02. Rounded **up** to 0.4 for the same reason the set above is the
 * whole set: an estimate about running out of room must err short.
 *
 * Worth knowing before trying to shave it: 45% of that file is chunk framing rather than payload. At
 * 0.2 Hz the two-second flush bound produces chunks averaging 0.4 kB, where zstd manages 1.58× against
 * a full run's 3.05×. That bound is a *recovery* decision — it is what caps a killed process's loss —
 * and trading it for bytes amounting to 8.7 MB a day is the wrong way round.
 */
internal const val MINIMUM_MEGABYTES_PER_HOUR = 0.4

/**
 * What the *current* settings cost, which is what the capacity estimate divides by.
 *
 * Minimum is checked first, and must be: it forces every other subject off and caps position at 0.2 Hz
 * whatever `recordAllMax` says, so reading the rate mode first would have a Minimum run — which defaults
 * to `recordAllMax = true` — quote 241 MB/h for a file that fills six hundred times slower. This is the
 * same ordering `Settings.recordRate` keeps, and for the same reason.
 *
 * A `Double` rather than an `Int` because the minimum figure truncates to zero, and a zero rate makes
 * [recordingCapacityMillis] return null — which would silently remove the free-space line rather than
 * correct it.
 */
internal fun baseMegabytesPerHour(settings: Settings): Double = when {
    settings.minimumMode -> MINIMUM_MEGABYTES_PER_HOUR
    settings.recordAllMax -> MAX_MEGABYTES_PER_HOUR.toDouble()
    else -> CONFIGURED_MEGABYTES_PER_HOUR.toDouble()
}

/** The publisher's own bounds, mirrored so a quoted data rate is the one that will actually run. */
internal const val MIN_FRAME_INTERVAL_MILLIS = 500L
internal const val MAX_FRAME_INTERVAL_MILLIS = 600_000L

/**
 * What a run configured like this will write per hour.
 *
 * The two optional subjects dominate when they are on — audio is about 109 MB/h at the default and the
 * camera about 158 MB/h — so a capacity estimate that ignored them would be out by a factor of four on
 * the settings that most need the warning.
 *
 * **Which of them are on is read from [Settings.offSubjects], never from the enable flags**, because the
 * flag and the run disagree in two cases the flags cannot see. Minimum forces all three off, so a
 * Minimum run with the camera switch left standing used to have 158 MB/h added to an estimate for a run
 * that will not write a single frame; and video and the time-lapse cannot both run, which `offSubjects`
 * resolves in `videoEnabled`'s favour. One source of truth for "is this subject going to produce
 * anything", the same one `SubjectSink.emit` gates on.
 */
internal fun megabytesPerHour(settings: Settings): Double {
    // The **record** rate: this predicts how fast the file fills, and the file is written at the rate
    // the sensor is sampled at, not the thinner rate the bus is given.
    val frameHz = 1_000.0 / settings.recordRate(Subjects.IMAGE_COMPRESSED)
        .toIntervalMillis()
        .coerceIn(MIN_FRAME_INTERVAL_MILLIS, MAX_FRAME_INTERVAL_MILLIS)
    val off = settings.offSubjects()
    return baseMegabytesPerHour(settings) +
        (if (PublishedSubject.AUDIO !in off) audioMegabytesPerHour(settings.audioSampleRateHz, settings.audioChannels) else 0) +
        (if (PublishedSubject.IMAGE_COMPRESSED !in off) cameraMegabytesPerHour(settings.cameraWidth, settings.cameraHeight, frameHz) else 0) +
        (if (PublishedSubject.VIDEO_COMPRESSED !in off) videoMegabytesPerHour(settings.videoBitrateKbps) else 0)
}

/**
 * A fill rate as the card prints it: `241`, `23`, `0.4`.
 *
 * A decimal place only where the figure needs one, so the three modes read as the same kind of number
 * rather than `241.0` beside `0.4`. Shaped like [formatRate], and through `.fmt()` for the reason every
 * number in this app is — `"%.1f".format(x)` follows the default locale and prints `0,4` on a Swedish
 * phone.
 */
internal fun formatMegabytesPerHour(megabytesPerHour: Double): String =
    if (megabytesPerHour >= 10.0) "%.0f".fmt(megabytesPerHour) else "%.1f".fmt(megabytesPerHour)

/**
 * What continuous video costs per hour — **exact, unlike the JPEG figure beside it.**
 *
 * `cameraMegabytesPerHour` guesses at how well a scene will compress, and deliberately guesses high. A
 * bitrate needs no guess: it is what the encoder was told to produce and what it holds to within a few
 * percent, so this is arithmetic on a number the user chose.
 *
 * At the 300 kbps default that is 128 MB/h — less than the time-lapse's 158 MB/h for twenty times the
 * frames, which is the whole reason those defaults were picked. At 2 Mbps it is 858 MB/h and turns ten
 * days of recording into under two.
 */
internal fun videoMegabytesPerHour(bitrateKbps: Int): Int =
    (bitrateKbps.toLong() * 1000 / 8 * 3600 / 1_048_576).toInt()

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
internal fun recordingCapacityMillis(freeBytes: Long, megabytesPerHour: Double): Long? {
    if (megabytesPerHour <= 0.0) return null
    val fuel = freeBytes - MIN_FREE_BYTES
    if (fuel <= 0L) return 0L
    val bytesPerHour = megabytesPerHour * 1_048_576.0
    return (fuel.toDouble() / bytesPerHour * 3_600_000.0).toLong()
}

/**
 * A capacity, at the precision a capacity deserves — **hedge included.**
 *
 * Not [formatRuntimeLeft], which is for a measured drain and rounds to the minute: a volume with three
 * weeks of room on it would read "528 h" there, which is a number nobody can picture. This says days
 * past two of them and never claims minutes — the rate it divides by is an average of a set of
 * subjects, so the third significant figure is noise.
 *
 * Hours run all the way to 48 rather than stopping at 24, so "1 day" never appears: a day and a half
 * reads "36 h", which is more use to somebody deciding whether a passage fits.
 *
 * **And it stops at a year**, because Minimum gave it a number to stop at: 64 GB at 0.4 MB/h is 6 826
 * days, and a card reading `6 826 days` reads as arithmetic nobody checked rather than as reassurance.
 * Past that horizon the disk has stopped being the constraint — the battery is, which is the end
 * `timeLeft()` names once a run is actually going.
 *
 * **The word "about" is part of the answer, not part of the sentence around it**, and it moved in here
 * because two of the four arms carry their own hedge and the caller could not know which. Prefixing
 * "about" outside gave `about over a year of recording`, and had already been giving `about under an
 * hour` before Minimum made it obvious. Each arm now reads correctly on its own.
 */
internal fun formatCapacity(millis: Long): String {
    val minutes = millis / 60_000L
    if (minutes < 60L) return "under an hour"
    val hours = minutes / 60L
    if (hours < 48L) return "about $hours h"
    val days = hours / 24L
    if (days >= 365L) return "over a year"
    return "about ${formatCounted(days, "day")}"
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
