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
    // The raw sentences have their own listener rather than riding the `Location` callback, and still
    // belong here: nothing in `NmeaProvider` starts the GNSS engine, so the sentences only flow while
    // this collector's fused request is keeping the chip going. Sharing the group is what makes that
    // true in both directions — switch every other GNSS subject off and leave this one on, and the
    // collector stays up, the request stays alive, and the receiver keeps talking.
    PublishedSubject.RAW_NMEA0183,
    // `GnssStatus` is the same shape of thing: it reports on a running engine and does not start one.
    PublishedSubject.SATELLITES_VISIBLE,
    PublishedSubject.SATELLITES_USED,
    PublishedSubject.FIX_QUALITY,
    // These come off the `Location` object itself, like the speed and the course.
    PublishedSubject.ACCURACY_HORIZONTAL,
    PublishedSubject.ACCURACY_VERTICAL,
    PublishedSubject.ALTITUDE_ABOVE_MSL,
    PublishedSubject.FIX_UNDULATION,
)

/**
 * The three that come off `GnssStatus` rather than off the `Location` — named so the collector can
 * report a refused permission on all of them at once, the way the fix subjects do.
 */
val GNSS_STATUS_SUBJECTS = setOf(
    PublishedSubject.SATELLITES_VISIBLE,
    PublishedSubject.SATELLITES_USED,
    PublishedSubject.FIX_QUALITY,
)

/** The IMU's die temperature — its own sensor, so its own collector. */
val IMU_TEMPERATURE_SUBJECTS = setOf(PublishedSubject.IMU_TEMPERATURE)

/**
 * The attitude as readable angles, on their own rotation-vector registration.
 *
 * A second listener on a sensor the orientation collector is already using, which the codebase
 * otherwise warns against — the reason is the rate: these run at 10 Hz where the quaternion runs at
 * 50, and one registration cannot serve both.
 */
val ATTITUDE_SUBJECTS = setOf(
    PublishedSubject.ROLL,
    PublishedSubject.PITCH,
    PublishedSubject.YAW,
)

/** The same, for the gyro's three axes in degrees per second. */
val ATTITUDE_RATE_SUBJECTS = setOf(
    PublishedSubject.ROLL_RATE,
    PublishedSubject.PITCH_RATE,
    PublishedSubject.YAW_RATE,
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
    // Not a battery reading, but the same poll and the same device — see the registry.
    PublishedSubject.DEVICE_UPTIME,
    // Nor are these, and for the same reason: one `statfs` beside the gauge read, at the same 0.2 Hz.
    // The group is a device poll now rather than a battery one; only its name still says otherwise.
    PublishedSubject.DISK_FREE_BYTES,
    PublishedSubject.DISK_USED_PCT,
    // The rest of the host family a phone can answer for. Memory and swap are health; the name and the
    // boot instant are identity, which upstream republishes on a long interval and this rides the poll
    // for rather than opening a collector for two values that never change.
    PublishedSubject.MEMORY_USED_PCT,
    PublishedSubject.SWAP_USED_PCT,
    PublishedSubject.HOST_NAME,
    PublishedSubject.HOST_BOOT_TIME,
)

/**
 * One camera session, two use cases: `ImageCapture` for the stills and a `Preview` feeding the H.264
 * encoder. Both subjects share a collector, and the camera is released only when both are off.
 *
 * Named here rather than spelled at the two call sites, because it *was* spelled at both — and when
 * video was added to the group but not to `SensorPublisher.start()`, the collector simply never ran
 * with the time-lapse switched off. Nothing failed; the subject sat claimed and silent.
 */
val CAMERA_SUBJECTS = setOf(PublishedSubject.IMAGE_COMPRESSED, PublishedSubject.VIDEO_COMPRESSED)

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
    PublishedSubject.CELL_BANDWIDTH_DOWNLINK,
)

/**
 * The platform calibration: two subjects, one ten-second loop.
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
val START_TIME_SUBJECTS = setOf(
    PublishedSubject.AUDIO,
    PublishedSubject.IMAGE_COMPRESSED,
    // Video is here for a second reason on top of the foreground-service type it shares with the
    // stills: it rides the *same* collector as `image_compressed`, and `supervise()` only starts a
    // collector on the all-off → any-on edge. Switching video on mid-run while the time-lapse was
    // already going would therefore rebind nothing and produce no frame, with nothing on screen
    // looking wrong. The restart is what makes the switch mean something.
    PublishedSubject.VIDEO_COMPRESSED,
)

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
    // Their own groups, not part of `location`. The precedent that could mislead is `raw_nmea0183`,
    // which has its own listener and still shares that group — but it shares it *because* it cannot
    // start the GNSS engine, only hear one already running. `GPS_PROVIDER` does start it, so these
    // are genuinely separate streams and each switch has to release its own listener.
    CollectorGroup("locationGnss", setOf(PublishedSubject.LOCATION_FIX_GNSS)),
    CollectorGroup("locationNetwork", setOf(PublishedSubject.LOCATION_FIX_NETWORK)),
    CollectorGroup("accel", setOf(PublishedSubject.LINEAR_ACCEL)),
    CollectorGroup("gyro", setOf(PublishedSubject.ANGULAR_VEL)),
    CollectorGroup("orientation", ORIENTATION_SUBJECTS),
    CollectorGroup("imuTemperature", IMU_TEMPERATURE_SUBJECTS),
    CollectorGroup("attitude", ATTITUDE_SUBJECTS),
    CollectorGroup("attitudeRates", ATTITUDE_RATE_SUBJECTS),
    CollectorGroup("magnetometer", setOf(PublishedSubject.MAGNETIC_FIELD)),
    CollectorGroup("pressure", setOf(PublishedSubject.AIR_PRESSURE)),
    CollectorGroup("illuminance", setOf(PublishedSubject.ILLUMINANCE)),
    CollectorGroup("audio", setOf(PublishedSubject.AUDIO)),
    CollectorGroup("camera", CAMERA_SUBJECTS),
    CollectorGroup("battery", BATTERY_SUBJECTS),
    CollectorGroup("radio", RADIO_SUBJECTS),
    CollectorGroup("calibration", CALIBRATION_SUBJECTS),
)
