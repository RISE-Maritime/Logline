package se.rise.logline.record

import se.rise.logline.keelson.Subjects

/**
 * Which protobuf type each published subject carries.
 *
 * Transcribed from `messages/subjects.yaml` upstream, and it must stay in step with it: an MCAP schema
 * naming the wrong type produces a file that opens but decodes to nonsense. This is the same
 * subject→type mapping the Python SDK reads out of `subjects.yaml` at runtime; the app has no yaml
 * parser and no descriptor registry, so it is spelled out here and pinned by a test.
 */
internal val subjectSchemaNames: Map<String, String> = mapOf(
    Subjects.LOCATION_FIX to "foxglove.LocationFix",
    Subjects.SPEED_OVER_GROUND_KNOTS to "keelson.TimestampedFloat",
    Subjects.COURSE_OVER_GROUND_DEG to "keelson.TimestampedFloat",
    Subjects.LINEAR_ACCELERATION_MPSS to "keelson.Decomposed3DVector",
    Subjects.ANGULAR_VELOCITY_RADPS to "keelson.Decomposed3DVector",
    Subjects.ORIENTATION_QUATERNION to "keelson.TimestampedQuaternion",
    Subjects.MAGNETIC_FIELD_GAUSS to "keelson.Decomposed3DVector",
    Subjects.HEADING_MAGNETIC_DEG to "keelson.TimestampedFloat",
    Subjects.HEADING_TRUE_NORTH_DEG to "keelson.TimestampedFloat",
    Subjects.HEADING_ACCURACY_DEG to "keelson.TimestampedFloat",
    Subjects.MAGNETIC_VARIATION_DEG to "keelson.TimestampedFloat",
    Subjects.AIR_PRESSURE_PA to "keelson.TimestampedFloat",
    Subjects.ILLUMINANCE_LUX to "keelson.TimestampedFloat",
    Subjects.AUDIO to "keelson.Audio",
    Subjects.IMAGE_COMPRESSED to "foxglove.CompressedImage",
    Subjects.FRAME_TRANSFORM to "foxglove.FrameTransform",
    Subjects.RAW_NMEA0183 to "keelson.TimestampedString",
    Subjects.LOCATION_FIX_SATELLITES_USED to "keelson.TimestampedInt",
    Subjects.LOCATION_FIX_SATELLITES_VISIBLE to "keelson.TimestampedInt",
    Subjects.LOCATION_FIX_QUALITY to "keelson.LocationFixQuality",
    Subjects.CONFIGURATION_JSON to "keelson.TimestampedString",
    Subjects.LOG_MESSAGE to "foxglove.Log",
    Subjects.BATTERY_STATE_OF_CHARGE_PCT to "keelson.TimestampedFloat",
    Subjects.BATTERY_VOLTAGE_V to "keelson.TimestampedFloat",
    Subjects.BATTERY_CURRENT_A to "keelson.TimestampedFloat",
    Subjects.BATTERY_TEMPERATURE_CELSIUS to "keelson.TimestampedFloat",
    Subjects.BATTERY_IS_CHARGING to "keelson.TimestampedBool",
    Subjects.RADIO_RSRP_DBM to "keelson.TimestampedFloat",
    Subjects.RADIO_RSRQ_DB to "keelson.TimestampedFloat",
    Subjects.RADIO_SINR_DB to "keelson.TimestampedFloat",
    Subjects.RADIO_RSSI_DBM to "keelson.TimestampedFloat",
    Subjects.RADIO_ACCESS_TECHNOLOGY to "keelson.TimestampedString",
    Subjects.RADIO_DOWNLINK_BITRATE_BPS to "keelson.TimestampedFloat",
    Subjects.RADIO_UPLINK_BITRATE_BPS to "keelson.TimestampedFloat",
    Subjects.RADIO_CELL_ID to "keelson.TimestampedInt64",
    Subjects.RADIO_PHYSICAL_CELL_ID to "keelson.TimestampedInt",
    Subjects.RADIO_EARFCN to "keelson.TimestampedInt",
    Subjects.RADIO_BAND to "keelson.TimestampedString",
)

/** The asset protoc writes at build time — a serialised `FileDescriptorSet`. */
internal const val DESCRIPTOR_ASSET = "keelson_payloads.desc"
