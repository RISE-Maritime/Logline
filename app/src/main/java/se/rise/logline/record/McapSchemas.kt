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
    Subjects.VIDEO_COMPRESSED to "foxglove.CompressedVideo",
    Subjects.FRAME_TRANSFORM to "foxglove.FrameTransform",
    Subjects.RAW_NMEA0183 to "keelson.TimestampedString",
    Subjects.LOCATION_FIX_SATELLITES_USED to "keelson.TimestampedInt",
    Subjects.LOCATION_FIX_SATELLITES_VISIBLE to "keelson.TimestampedInt",
    Subjects.LOCATION_FIX_QUALITY to "keelson.LocationFixQuality",
    Subjects.LOCATION_FIX_ACCURACY_HORIZONTAL_M to "keelson.TimestampedFloat",
    Subjects.LOCATION_FIX_ACCURACY_VERTICAL_M to "keelson.TimestampedFloat",
    Subjects.ALTITUDE_ABOVE_MSL_M to "keelson.TimestampedFloat",
    Subjects.LOCATION_FIX_UNDULATION_M to "keelson.TimestampedFloat",
    Subjects.ROLL_DEG to "keelson.TimestampedFloat",
    Subjects.PITCH_DEG to "keelson.TimestampedFloat",
    Subjects.YAW_DEG to "keelson.TimestampedFloat",
    Subjects.ROLL_RATE_DEGPS to "keelson.TimestampedFloat",
    Subjects.PITCH_RATE_DEGPS to "keelson.TimestampedFloat",
    Subjects.YAW_RATE_DEGPS to "keelson.TimestampedFloat",
    Subjects.CONFIGURATION_JSON to "keelson.TimestampedString",
    Subjects.LOG_MESSAGE to "foxglove.Log",
    Subjects.BATTERY_STATE_OF_CHARGE_PCT to "keelson.TimestampedFloat",
    Subjects.BATTERY_VOLTAGE_V to "keelson.TimestampedFloat",
    Subjects.BATTERY_CURRENT_A to "keelson.TimestampedFloat",
    Subjects.BATTERY_TEMPERATURE_CELSIUS to "keelson.TimestampedFloat",
    Subjects.BATTERY_IS_CHARGING to "keelson.TimestampedBool",
    Subjects.DEVICE_UPTIME_DURATION to "keelson.TimestampedDuration",
    Subjects.DISK_FREE_BYTES to "keelson.TimestampedInt64",
    Subjects.DISK_USED_PCT to "keelson.TimestampedFloat",
    Subjects.MEMORY_USED_PCT to "keelson.TimestampedFloat",
    Subjects.SWAP_USED_PCT to "keelson.TimestampedFloat",
    Subjects.HOST_NAME to "keelson.TimestampedString",
    Subjects.HOST_BOOT_TIME to "keelson.TimestampedTimestamp",
    Subjects.IMU_TEMPERATURE_CELSIUS to "keelson.TimestampedFloat",
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
    Subjects.RADIO_DOWNLINK_BANDWIDTH_MHZ to "keelson.TimestampedFloat",
)

/** The asset protoc writes at build time — a serialised `FileDescriptorSet`. */
internal const val DESCRIPTOR_ASSET = "keelson_payloads.desc"
