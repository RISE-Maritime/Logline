package se.rise.logline.keelson

/**
 * Wire names, transcribed from `messages/subjects.yaml` upstream.
 *
 * A subject only means something if a consumer agrees on its name *and* its payload type, so these are
 * never invented here — see [PublishedSubject] for which ones this app actually publishes.
 *
 * Note the units are keelson's, not Android's: gauss not microtesla, pascals not hectopascals, knots
 * not metres per second, volts not millivolts. Every one of those needs a conversion on the way out
 * (`sensors/Units.kt`).
 */
object Subjects {
    const val LOCATION_FIX = "location_fix"
    const val SPEED_OVER_GROUND_KNOTS = "speed_over_ground_knots"
    const val COURSE_OVER_GROUND_DEG = "course_over_ground_deg"
    const val LINEAR_ACCELERATION_MPSS = "linear_acceleration_mpss"
    const val ANGULAR_VELOCITY_RADPS = "angular_velocity_radps"
    const val ORIENTATION_QUATERNION = "orientation_quaternion"
    const val MAGNETIC_FIELD_GAUSS = "magnetic_field_gauss"

    // The compass. Heading is where the phone points; `course_over_ground_deg` above is where it is
    // travelling, and on the water those are different numbers — which is the reason to log both.
    const val HEADING_MAGNETIC_DEG = "heading_magnetic_deg"
    const val HEADING_TRUE_NORTH_DEG = "heading_true_north_deg"
    const val HEADING_ACCURACY_DEG = "heading_accuracy_deg"
    const val MAGNETIC_VARIATION_DEG = "magnetic_variation_deg"
    const val AIR_PRESSURE_PA = "air_pressure_pa"
    const val ILLUMINANCE_LUX = "illuminance_lux"

    // Sound. Upstream types this as `keelson.Audio`, whose encodings are MP3 and WAV only.
    const val AUDIO = "audio"

    // Camera frames. `foxglove.CompressedImage` carries the bytes plus a media-type string, so the
    // encoding travels with the frame; upstream's `image_raw` is the uncompressed sibling and is not
    // what a phone should be putting on a bus.
    const val IMAGE_COMPRESSED = "image_compressed"

    // Operator annotations. `foxglove.Log` is the one upstream payload carrying free text with a
    // severity, and Foxglove's Log panel reads it natively — filtering on `level` and on `name`, which
    // is why a mark's category goes in `name` rather than being spelled into the message.
    const val LOG_MESSAGE = "log_message"

    // Rig calibration. `frame_transform` carries one sensor's pose relative to the rig's reference
    // frame, and `configuration_json` the whole geometry document — the same pair, and the same
    // document shape, that keelson's own `connectors/platform` publishes. See `docs/calibration.md`.
    const val FRAME_TRANSFORM = "frame_transform"
    const val CONFIGURATION_JSON = "configuration_json"
    const val BATTERY_STATE_OF_CHARGE_PCT = "battery_state_of_charge_pct"
    const val BATTERY_VOLTAGE_V = "battery_voltage_v"
    const val BATTERY_CURRENT_A = "battery_current_a"
    const val BATTERY_TEMPERATURE_CELSIUS = "battery_temperature_celsius"
    const val BATTERY_IS_CHARGING = "battery_is_charging"

    // Radio link quality. Upstream models these per *link*, distinguished by source_id rather than by
    // separate subject names — so `radio_rssi_dbm` is published once for cellular and once for wifi.
    // Note the legacy unitless `radio_rssi` is a different, older subject; do not use it.
    const val RADIO_RSRP_DBM = "radio_rsrp_dbm"
    const val RADIO_RSRQ_DB = "radio_rsrq_db"
    const val RADIO_SINR_DB = "radio_sinr_db"
    const val RADIO_RSSI_DBM = "radio_rssi_dbm"
    const val RADIO_ACCESS_TECHNOLOGY = "radio_access_technology"
    const val RADIO_DOWNLINK_BITRATE_BPS = "radio_downlink_bitrate_bps"
    const val RADIO_UPLINK_BITRATE_BPS = "radio_uplink_bitrate_bps"

    // Serving cell identity. Upstream's note is the reason these exist: "A change in any of these means
    // the link handed over, and measurements either side of it are not comparable."
    const val RADIO_CELL_ID = "radio_cell_id"
    const val RADIO_PHYSICAL_CELL_ID = "radio_physical_cell_id"
    const val RADIO_EARFCN = "radio_earfcn"
    const val RADIO_BAND = "radio_band"

    // Collaborative checklists. Deliberately **not** in `PublishedSubject`: that registry is the
    // *sensor* publishing set, and every derived thing — sampling rates, `SensorManager` types, MCAP
    // channels, live-view rings, the main screen's rows, the per-subject switches — reads it. A
    // checklist subject there would grow a phantom sensor card with a rate control for a thing a
    // person taps. These four are published and subscribed by `checklist/ChecklistSync`, which owns
    // its own session and its own lifetime.
    const val CHECKLIST_EVENT = "checklist_event"
    const val CHECKLIST_STATE = "checklist_state"
    const val CHECKLIST_PRESENCE = "checklist_presence"
    const val CHECKLIST_PROCEDURE = "checklist_procedure"
}

/** The fixed source ids for the radio links, which are hardware facts rather than user settings. */
object RadioSources {
    const val CELLULAR = "cellular"
    const val WIFI = "wifi"
}

fun pubsubKey(realm: String, entityId: String, subject: String, sourceId: String): String =
    "$realm/@v0/$entityId/pubsub/$subject/$sourceId"

/**
 * Key for a source's liveliness token, per the Keelson protocol specification §5.1.
 *
 * The `*` in the subject position is literal, not a placeholder: one token per *source*, declaring
 * that the process is alive and may produce output on any subject. The spec is explicit that this is
 * a presence signal, not a capability declaration — so this is deliberately not one token per subject.
 */
fun livelinessKey(realm: String, entityId: String, sourceId: String): String =
    "$realm/@v0/$entityId/pubsub/*/$sourceId"
