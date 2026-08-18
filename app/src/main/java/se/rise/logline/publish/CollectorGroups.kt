package se.rise.logline.publish

import se.rise.logline.keelson.PublishedSubject

/**
 * One sensor stream and the subjects that come off it.
 *
 * Subjects are not one-to-one with collectors: a single `Location` callback feeds four subjects, one
 * rotation-vector event feeds four more, one battery poll feeds five and one radio poll feeds twelve.
 * That is what [PublishedSubject.rateOwner] already records, and it is the reason the per-subject
 * switches cannot simply cancel "the collector for this subject" — the listener may only be detached
 * once *every* subject riding it is switched off.
 */
data class CollectorGroup(
    /** For logs. The `runX` function this names is the one that produces these subjects. */
    val name: String,
    val subjects: Set<PublishedSubject>,
)

/** GNSS: one fix, four subjects, plus the declination the compass borrows. */
val LOCATION_SUBJECTS = setOf(
    PublishedSubject.LOCATION_FIX,
    PublishedSubject.SPEED_OVER_GROUND,
    PublishedSubject.COURSE_OVER_GROUND,
    PublishedSubject.MAGNETIC_VARIATION,
)

/** The rotation vector, as a quaternion and as three headings. */
val ORIENTATION_SUBJECTS = setOf(
    PublishedSubject.ORIENTATION,
    PublishedSubject.HEADING_MAGNETIC,
    PublishedSubject.HEADING_TRUE_NORTH,
    PublishedSubject.HEADING_ACCURACY,
)

val BATTERY_SUBJECTS = setOf(
    PublishedSubject.BATTERY_STATE_OF_CHARGE,
    PublishedSubject.BATTERY_VOLTAGE,
    PublishedSubject.BATTERY_CURRENT,
    PublishedSubject.BATTERY_TEMPERATURE,
    PublishedSubject.BATTERY_IS_CHARGING,
)

val RADIO_SUBJECTS = setOf(
    PublishedSubject.CELLULAR_RSRP,
    PublishedSubject.CELLULAR_RSRQ,
    PublishedSubject.CELLULAR_SINR,
    PublishedSubject.CELLULAR_RSSI,
    PublishedSubject.CELLULAR_ACCESS_TECHNOLOGY,
    PublishedSubject.WIFI_RSSI,
    PublishedSubject.WIFI_DOWNLINK_BITRATE,
    PublishedSubject.WIFI_UPLINK_BITRATE,
    PublishedSubject.CELL_ID,
    PublishedSubject.PHYSICAL_CELL_ID,
    PublishedSubject.EARFCN,
    PublishedSubject.BAND,
)

/**
 * The rig calibration: two subjects, one ten-second loop.
 *
 * Not a sensor, and the only collector here that has no listener to release — but it belongs on the
 * list all the same, because switching it off should stop the loop rather than leave it re-serialising
 * a document every ten seconds for a sink that drops it.
 */
val CALIBRATION_SUBJECTS = setOf(
    PublishedSubject.FRAME_TRANSFORM,
    PublishedSubject.CONFIGURATION_JSON,
    PublishedSubject.CALIBRATION_ZERO,
)

/**
 * The two subjects whose switch cannot take effect on a run already going.
 *
 * Both decide a foreground-service type — `microphone`, `camera` — and a runtime permission at
 * `startForeground`, and Android 14+ throws rather than warns if the type is declared without the
 * permission. So the type mask has to be right on the first call, which makes them a start-time
 * decision however the switch is presented. Everything else can be switched mid-run.
 */
val START_TIME_SUBJECTS = setOf(PublishedSubject.AUDIO, PublishedSubject.IMAGE_COMPRESSED)

/**
 * Every collector in [SensorPublisher], and what each one publishes.
 *
 * **Every registry entry must appear exactly once**, which `CollectorGroupsTest` asserts. A subject
 * missing from here would keep its sensor registered no matter how its switch was set — the sink gate
 * would stop the samples reaching the bus, so nothing would look broken, and the phone would go on
 * paying for a 50 Hz listener nobody asked for. That is precisely the silent kind of miss the subject
 * registry exists to prevent.
 *
 * The exception is [PublishedSubject.eventDriven]: `log_message` has no collector and no listener, so
 * there is nothing for a switch to release and nothing here to name. Its switch still works, because
 * the sink gate is the half that does that. The test excludes those entries for the same reason.
 */
val COLLECTOR_GROUPS: List<CollectorGroup> = listOf(
    CollectorGroup("location", LOCATION_SUBJECTS),
    CollectorGroup("accel", setOf(PublishedSubject.LINEAR_ACCEL)),
    CollectorGroup("gyro", setOf(PublishedSubject.ANGULAR_VEL)),
    CollectorGroup("orientation", ORIENTATION_SUBJECTS),
    CollectorGroup("magnetometer", setOf(PublishedSubject.MAGNETIC_FIELD)),
    CollectorGroup("pressure", setOf(PublishedSubject.AIR_PRESSURE)),
    CollectorGroup("illuminance", setOf(PublishedSubject.ILLUMINANCE)),
    CollectorGroup("audio", setOf(PublishedSubject.AUDIO)),
    CollectorGroup("camera", setOf(PublishedSubject.IMAGE_COMPRESSED)),
    CollectorGroup("battery", BATTERY_SUBJECTS),
    CollectorGroup("radio", RADIO_SUBJECTS),
    CollectorGroup("calibration", CALIBRATION_SUBJECTS),
)
