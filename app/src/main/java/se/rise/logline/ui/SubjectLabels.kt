package se.rise.logline.ui

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects
import kotlin.math.abs

/**
 * What a subject is called on screen, and the unit its value carries.
 *
 * `speed_over_ground_knots` is the name on the wire and it has to stay exactly that — but it is a poor
 * thing to read on a boat. This is the display half only: the wire name is still shown wherever the
 * subject is being configured rather than watched, because that is where it matters.
 *
 * Spelled out rather than derived from the subject string. A mechanical rule handles
 * `speed_over_ground_knots` well and `radio_rsrp_dbm` badly — acronyms, the `radio_` prefix that means
 * "which link" rather than part of the name, and units like `pct` that are not the last token. Where the
 * table is missing an entry, [derivedName] still produces something readable, and `SubjectLabelsTest`
 * fails if that fallback is ever what a shipped subject relies on.
 */
data class SubjectLabel(val name: String, val unit: String? = null)

fun labelOf(entry: PublishedSubject): SubjectLabel = when (entry) {
    // Keyed on the *entry* rather than the subject, and the only one that has to be: `location_fix` is
    // published twice — the phone's live position, and the rig's surveyed zero point. Two rows both
    // called "Position" would be a genuinely dangerous thing to read.
    PublishedSubject.CALIBRATION_ZERO -> SubjectLabel("Zero point")
    else -> labelOfSubject(entry.subject)
}

private fun labelOfSubject(subject: String): SubjectLabel = when (subject) {
    Subjects.LOCATION_FIX -> SubjectLabel("Position")
    Subjects.SPEED_OVER_GROUND_KNOTS -> SubjectLabel("Speed over ground", "kn")
    Subjects.COURSE_OVER_GROUND_DEG -> SubjectLabel("Course over ground", "°")
    Subjects.MAGNETIC_VARIATION_DEG -> SubjectLabel("Magnetic variation", "°")

    Subjects.LINEAR_ACCELERATION_MPSS -> SubjectLabel("Linear acceleration", "m/s²")
    Subjects.ANGULAR_VELOCITY_RADPS -> SubjectLabel("Angular velocity", "rad/s")
    Subjects.ORIENTATION_QUATERNION -> SubjectLabel("Orientation")
    Subjects.MAGNETIC_FIELD_GAUSS -> SubjectLabel("Magnetic field", "G")
    Subjects.HEADING_MAGNETIC_DEG -> SubjectLabel("Heading, magnetic", "°")
    Subjects.HEADING_TRUE_NORTH_DEG -> SubjectLabel("Heading, true", "°")
    Subjects.HEADING_ACCURACY_DEG -> SubjectLabel("Heading accuracy", "°")

    Subjects.AIR_PRESSURE_PA -> SubjectLabel("Air pressure", "Pa")
    Subjects.ILLUMINANCE_LUX -> SubjectLabel("Illuminance", "lx")
    // Plural: the row counts the marks made this run, it does not show "a value".
    Subjects.LOG_MESSAGE -> SubjectLabel("Annotations")
    // Named for what the screen can actually show. The subject carries sound; what is
    // plotted and displayed is the chunk's level, because a waveform is not a sparkline.
    Subjects.AUDIO -> SubjectLabel("Audio level", "dBFS")
    // Same reasoning: the subject carries a picture, and what a sparkline can show of it is how big
    // each frame came out. The frame itself is drawn above the plot in the live view.
    Subjects.IMAGE_COMPRESSED -> SubjectLabel("Frame size", "kB")
    Subjects.BATTERY_STATE_OF_CHARGE_PCT -> SubjectLabel("Charge", "%")
    Subjects.BATTERY_VOLTAGE_V -> SubjectLabel("Battery voltage", "V")
    Subjects.BATTERY_CURRENT_A -> SubjectLabel("Battery current", "A")
    Subjects.BATTERY_TEMPERATURE_CELSIUS -> SubjectLabel("Battery temperature", "°C")
    Subjects.BATTERY_IS_CHARGING -> SubjectLabel("Charging")

    // The link is already in the section heading, so these drop the `radio_` and keep the acronym the
    // way a radio engineer writes it.
    Subjects.RADIO_RSRP_DBM -> SubjectLabel("RSRP", "dBm")
    Subjects.RADIO_RSRQ_DB -> SubjectLabel("RSRQ", "dB")
    Subjects.RADIO_SINR_DB -> SubjectLabel("SINR", "dB")
    Subjects.RADIO_RSSI_DBM -> SubjectLabel("RSSI", "dBm")
    Subjects.RADIO_ACCESS_TECHNOLOGY -> SubjectLabel("Access technology")
    // "Link speed", not "downlink": these come from `WifiInfo.getRxLinkSpeedMbps()`, which is the rate
    // the radio negotiated — not traffic the phone is moving. Calling it a bitrate on screen implied
    // Logline was pushing 2.16 Gbit/s. The wire subject keeps keelson's name; only the label is ours.
    Subjects.RADIO_DOWNLINK_BITRATE_BPS -> SubjectLabel("Link speed, down", "bit/s")
    Subjects.RADIO_UPLINK_BITRATE_BPS -> SubjectLabel("Link speed, up", "bit/s")
    Subjects.RADIO_CELL_ID -> SubjectLabel("Cell ID")
    Subjects.RADIO_PHYSICAL_CELL_ID -> SubjectLabel("Physical cell ID")
    Subjects.RADIO_EARFCN -> SubjectLabel("EARFCN")
    Subjects.RADIO_BAND -> SubjectLabel("Band")

    // The rig, not the phone. "Sensor pose" rather than "Frame transform" because that is what the
    // message says about the world; the wire name is still on the subject's own screen.
    Subjects.FRAME_TRANSFORM -> SubjectLabel("Sensor pose")
    // The row's value is how many sensors the published document describes — see `runCalibration`,
    // which hands the count to the sink as the sample's value.
    Subjects.CONFIGURATION_JSON -> SubjectLabel("Rig geometry", "sensors")

    else -> SubjectLabel(derivedName(subject))
}

/**
 * A readable name for a subject nobody has named yet: underscores to spaces, first letter up.
 *
 * A backstop, not the mechanism — it exists so a subject added upstream and picked up here still reads
 * as words rather than as a shouty identifier while someone gets round to naming it.
 */
internal fun derivedName(subject: String): String =
    subject.replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * The reading itself, at the precision it is worth.
 *
 * A speed of 3.44827 knots is three false digits: GNSS speed is good to a tenth at best, a pressure to
 * the pascal, a signal strength to the dBm. Precision here is a claim about the measurement, so each
 * subject gets the one it can support rather than a single global format.
 */
fun formatLiveValue(entry: PublishedSubject, value: Float): String = when (entry.subject) {
    Subjects.COURSE_OVER_GROUND_DEG,
    Subjects.HEADING_MAGNETIC_DEG,
    Subjects.HEADING_TRUE_NORTH_DEG,
    Subjects.HEADING_ACCURACY_DEG -> "%.0f".fmt(value)

    Subjects.MAGNETIC_VARIATION_DEG -> "%+.1f".fmt(value)
    Subjects.SPEED_OVER_GROUND_KNOTS -> "%.1f".fmt(value)
    Subjects.AIR_PRESSURE_PA -> "%.0f".fmt(value)
    // Frame sizes run to hundreds of kB; a decimal place on that is noise.
    Subjects.IMAGE_COMPRESSED -> "%.0f".fmt(value)
    Subjects.BATTERY_STATE_OF_CHARGE_PCT -> "%.0f".fmt(value)
    Subjects.BATTERY_VOLTAGE_V -> "%.2f".fmt(value)
    Subjects.BATTERY_CURRENT_A -> "%+.2f".fmt(value)
    Subjects.BATTERY_TEMPERATURE_CELSIUS -> "%.1f".fmt(value)
    // A live boolean arrives as 1 or 0; "Yes" is what a person reads.
    Subjects.BATTERY_IS_CHARGING -> if (value != 0f) "Yes" else "No"

    Subjects.RADIO_RSRP_DBM,
    Subjects.RADIO_RSRQ_DB,
    Subjects.RADIO_SINR_DB,
    Subjects.RADIO_RSSI_DBM -> "%.0f".fmt(value)

    // Bit rates run to hundreds of millions; the raw number is unreadable at a glance.
    Subjects.RADIO_DOWNLINK_BITRATE_BPS,
    Subjects.RADIO_UPLINK_BITRATE_BPS -> formatBitrate(value)

    Subjects.RADIO_CELL_ID,
    Subjects.RADIO_PHYSICAL_CELL_ID,
    Subjects.RADIO_EARFCN -> formatCount(value.toLong())

    // A count of sensors, so a whole number. The general fallback below would render three of them
    // as "3.00", which reads as a measurement rather than a tally.
    Subjects.CONFIGURATION_JSON -> "%.0f".fmt(value)

    // Everything vector-valued is stored as magnitude; three or four significant figures is all any of
    // these mean.
    else -> when {
        value == 0f -> "0"
        abs(value) >= 100f -> "%.0f".fmt(value)
        abs(value) >= 1f -> "%.2f".fmt(value)
        abs(value) >= 0.01f -> "%.3f".fmt(value)
        // Four decimals rather than scientific notation. A gyroscope at rest is genuinely near zero,
        // and "0,0002" is read at a glance where "2,0×10^-04" is not — this is a dashboard, not a
        // spreadsheet, and the live view's plot carries the detail.
        else -> "%.4f".fmt(value)
    }
}

/** Bits per second as a person says it: 219 Mbit/s, not 219000000. */
internal fun formatBitrate(bps: Float): String = when {
    abs(bps) >= 1_000_000_000f -> "%.1fG".fmt(bps / 1_000_000_000f)
    abs(bps) >= 1_000_000f -> "%.0fM".fmt(bps / 1_000_000f)
    abs(bps) >= 1_000f -> "%.0fk".fmt(bps / 1_000f)
    else -> "%.0f".fmt(bps)
}

/**
 * A position as a navigator writes it: `57.4359°N 12.0326°E`.
 *
 * The hemisphere letters are not decoration. In a locale that writes decimals with a comma — Swedish,
 * where this is used — `57,4359, 12,0326` has three commas doing two different jobs and reads as
 * four numbers. A letter between the two makes it unambiguous whatever the separator is.
 */
fun formatPosition(latitude: Double, longitude: Double): String {
    val ns = if (latitude >= 0) "N" else "S"
    val ew = if (longitude >= 0) "E" else "W"
    return "%.4f°%s %.4f°%s".fmt(abs(latitude), ns, abs(longitude), ew)
}
