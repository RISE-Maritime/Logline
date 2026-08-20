package se.rise.logline.keelson

import android.hardware.Sensor
import se.rise.logline.sensors.SensorRate

/**
 * Which configured source id a subject's key is built from.
 *
 * The protocol's `source_id` names *what produced the data*, so subjects that come off different
 * hardware get different ids. All three default to `phone`, which is why the usual case is a single
 * *source-level* liveliness token (specification §5.1) — the subject-level tokens beside it are a
 * separate tier and there is one of those per subject.
 */
enum class SourceKind {
    LOCATION,
    IMU,
    DEVICE,

    /**
     * The radio links. Unlike the others this is not a configurable id: `cellular` and `wifi` are what
     * the measurement *is*, so each entry carries its own fixed [PublishedSubject.fixedSourceId].
     */
    RADIO,

    /**
     * The rig calibration.
     *
     * The odd one out, and not only in its source id: these two subjects are the only ones published
     * under a **different entity**. `entity_id` names the physical thing the data is about, and a
     * rig's geometry is about the rig, not about the phone that surveyed it — see
     * [se.rise.logline.config.Settings.entityFor].
     */
    CALIBRATION,
}

/**
 * Every subject this app publishes, and everything the rest of the app needs to know about it.
 *
 * **This is the single source of truth.** Before it existed the subject set was spelled out in ten
 * separate places — a status field, a `when` branch, a rate default, a UI row, a persistence list —
 * and six of those failed *silently* when missed: the QoS override simply never persisted, or the card
 * never appeared, or the notification undercounted. Adding an entry here is now the whole of it.
 *
 * Order is the order the main screen lists them.
 */
enum class PublishedSubject(
    /** The wire name, from `messages/subjects.yaml` upstream. Never spell one inline. */
    val subject: String,
    val defaultRate: SensorRate,
    val source: SourceKind,
    /**
     * `Sensor.TYPE_*` when `SensorManager` is the origin, so the per-sensor screen can report the
     * hardware's own limits. Null for the fused location provider (no rate query exists) and for
     * battery (not a `SensorManager` sensor at all).
     */
    val sensorType: Int? = null,
    /**
     * Set when this subject rides another subject's sample stream and therefore has no rate of its
     * own. `speed_over_ground_knots` and `course_over_ground_deg` come off the *same* `Location`
     * callback as `location_fix`; offering them an independent Hz control would be a control that
     * silently does nothing.
     */
    val rateOwner: String? = null,
    /**
     * A source id fixed by the hardware rather than configured. Set for the radio links, where the same
     * subject is published once per link — `radio_rssi_dbm` under both `cellular` and `wifi`.
     *
     * This is why the registry's unique key is the *entry*, not the subject string: two entries legally
     * share a subject and are told apart by their source.
     */
    val fixedSourceId: String? = null,
    /**
     * Whether recent samples are held for replay when a dropped router link comes back.
     *
     * True for everything small. False for the camera, and that is a size argument rather than a
     * taste one: `SensorPublisher.replay()` paces by *message count*, so a dozen buffered 150 kB frames
     * go out as a multi-megabyte burst that the DROP-everywhere egress queue sheds silently — taking
     * the live navigation data queued behind it. A two-minute-old time-lapse frame is not worth that,
     * and the MCAP recording remains the complete copy either way.
     */
    val bufferedForReplay: Boolean = true,
    /**
     * Published when a human does something, not on a clock.
     *
     * Two things follow, and both are about not lying on screen. There is no rate to configure — the
     * subject has no stream of its own — so the sampling control is hidden rather than offered as a
     * dial that does nothing. And it cannot go *stale*: `subjectHealth()` reads silence as a fault for
     * every other subject, which is exactly right for a sensor and exactly wrong here, where silence
     * means nobody has marked anything yet. That is the normal state of a run.
     */
    val eventDriven: Boolean = false,
    /**
     * Whether the live view shows this in **Basic**.
     *
     * Thirty-nine plots is a page nobody scrolls to the bottom of during a run, and almost none of it
     * answers "is the boat where I think it is, and is the data any good". This flag names the handful
     * that do; everything else is one tap away under **All**.
     *
     * It defaults to false and `SubjectRegistryTest` pins the resulting set, which is the point: a
     * subject added later has to make the call deliberately rather than falling into — or silently out
     * of — a curated list. The same reason [bufferedForReplay] and [eventDriven] are pinned there.
     *
     * Not to be confused with a *rate* or a *switch*: a subject left out of Basic still publishes, is
     * still recorded, and still counts towards its group's health badge.
     */
    val featured: Boolean = false,
) {
    LOCATION_FIX(
        subject = Subjects.LOCATION_FIX,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        featured = true,
    ),
    SPEED_OVER_GROUND(
        subject = Subjects.SPEED_OVER_GROUND_KNOTS,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
        featured = true,
    ),
    COURSE_OVER_GROUND(
        subject = Subjects.COURSE_OVER_GROUND_DEG,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
        featured = true,
    ),
    /**
     * Declination — how far magnetic north is from true north here.
     *
     * A property of *position*, not of attitude, which is why it rides the fix rather than the compass:
     * it moves over kilometres, and publishing it at the rotation vector's 50 Hz would be 50 copies a
     * second of a number that changes on the scale of a day's sailing.
     */
    MAGNETIC_VARIATION(
        subject = Subjects.MAGNETIC_VARIATION_DEG,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
    ),
    /**
     * The receiver's own sentences, which is the only unfiltered GNSS this app can offer.
     *
     * Everything above comes from the *fused* provider — GNSS blended with wifi and cell, handed back
     * as a `Location` with fix quality, DOP and satellite detail already discarded. These carry all of
     * it, and in the form the rest of the fleet already speaks.
     *
     * Rides `location_fix` because the chip sets the pace and the fused request is what sets the chip
     * going: nothing here starts the GNSS engine, it only listens to one that is running. Several
     * sentences arrive per fix, so at the 1 Hz default this is the busiest of the GNSS subjects by
     * message count while being among the smallest by bytes.
     */
    RAW_NMEA0183(
        subject = Subjects.RAW_NMEA0183,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
    ),
    /**
     * How much of the sky the receiver can hear, and how much of it it is using.
     *
     * The gap between the two is the reading: twenty visible and none used is a phone under a steel
     * deck, and from everywhere else in this app that looks identical to a good fix — a position
     * arrives either way. These three are what a log gets asked afterwards, when the question is
     * whether a track can be trusted rather than where it went.
     *
     * On `GnssStatus`, which reports on a running engine without starting one, so they ride the fused
     * request the same way the raw sentences do.
     */
    SATELLITES_VISIBLE(
        subject = Subjects.LOCATION_FIX_SATELLITES_VISIBLE,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
    ),
    SATELLITES_USED(
        subject = Subjects.LOCATION_FIX_SATELLITES_USED,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
    ),
    FIX_QUALITY(
        subject = Subjects.LOCATION_FIX_QUALITY,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
        featured = true,
    ),
    /**
     * How far off the fix might be, as plain numbers.
     *
     * Both are already inside `location_fix`'s covariance matrix, where nothing can read them without
     * decoding nine doubles and knowing which three matter — so they cannot be plotted, alarmed on, or
     * glanced at. As their own subjects they are a line on a chart next to the track, which is what
     * anyone actually wants from them.
     *
     * Off the same `Location` as the fix, so no rate of their own. Skipped rather than zeroed when the
     * platform omits them: `0.0` metres of error reads as a perfect fix, which is the most dangerous
     * thing this app could say.
     */
    ACCURACY_HORIZONTAL(
        subject = Subjects.LOCATION_FIX_ACCURACY_HORIZONTAL_M,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
        featured = true,
    ),
    ACCURACY_VERTICAL(
        subject = Subjects.LOCATION_FIX_ACCURACY_VERTICAL_M,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
    ),
    /**
     * Altitude the way a person means it, and the correction that explains the other one.
     *
     * `location_fix.altitude` is `Location.getAltitude()`, which Android defines as height above the
     * **WGS84 ellipsoid** — and foxglove's proto says only "Altitude in meters", so nothing on the wire
     * resolves which surface it is measured from. In Sweden the two are 30-35 m apart, which reads as a
     * broken sensor rather than as a different reference.
     *
     * `altitude_above_msl_m` is the one anybody wants; `location_fix_undulation_m` is `h − H`, the
     * geoid's height above the ellipsoid, and it is published precisely because it *explains* the
     * discrepancy and lets a consumer convert between the two.
     */
    ALTITUDE_ABOVE_MSL(
        subject = Subjects.ALTITUDE_ABOVE_MSL_M,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
    ),
    FIX_UNDULATION(
        subject = Subjects.LOCATION_FIX_UNDULATION_M,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
    ),
    LINEAR_ACCEL(
        subject = Subjects.LINEAR_ACCELERATION_MPSS,
        defaultRate = SensorRate.Hz(50.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_LINEAR_ACCELERATION,
    ),
    ANGULAR_VEL(
        subject = Subjects.ANGULAR_VELOCITY_RADPS,
        defaultRate = SensorRate.Hz(50.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_GYROSCOPE,
    ),
    ORIENTATION(
        subject = Subjects.ORIENTATION_QUATERNION,
        defaultRate = SensorRate.Hz(50.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_ROTATION_VECTOR,
    ),
    MAGNETIC_FIELD(
        subject = Subjects.MAGNETIC_FIELD_GAUSS,
        defaultRate = SensorRate.Hz(50.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_MAGNETIC_FIELD,
    ),
    /**
     * The compass, derived from the same rotation-vector event as [ORIENTATION] — hence the rateOwner:
     * it is the same reading expressed as one angle instead of four components.
     *
     * `sensorType` is set as well, so a device with no rotation vector reports these as unavailable
     * rather than as subjects that merely never produced anything.
     */
    HEADING_MAGNETIC(
        subject = Subjects.HEADING_MAGNETIC_DEG,
        defaultRate = SensorRate.Hz(50.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_ROTATION_VECTOR,
        rateOwner = Subjects.ORIENTATION_QUATERNION,
    ),
    /** Magnetic heading plus the local declination, so it needs a fix before it can publish at all. */
    HEADING_TRUE_NORTH(
        subject = Subjects.HEADING_TRUE_NORTH_DEG,
        defaultRate = SensorRate.Hz(50.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_ROTATION_VECTOR,
        rateOwner = Subjects.ORIENTATION_QUATERNION,
        featured = true,
    ),
    /**
     * The platform's own 1-sigma estimate, straight off the rotation vector's fifth component.
     *
     * Published beside the heading rather than used to gate it: a heading with a stated 60° uncertainty
     * is information, a heading silently withheld is not.
     */
    /**
     * The attitude as three angles, which is the form a person can read.
     *
     * `orientation_quaternion` carries the same information exactly and unreadably: nobody looks at a
     * plot of `w` and knows how much the boat was moving. These come off the *same* `getOrientation`
     * call that already produces the heading — two of the three used to be computed and thrown away —
     * so they cost no sensor work, only messages.
     *
     * **Their own rate, and their own collector, which is a deliberate deviation.** Riding the rotation
     * vector at its 50 Hz default would have added ~150 messages a second for three subjects that
     * describe motion with a period of seconds; 10 Hz is ample for anything a hull does, and a
     * separate registration is what makes the dial real rather than decorative. `roll_deg` carries the
     * rate for the trio because one listener cannot serve three different ones.
     */
    ROLL(
        subject = Subjects.ROLL_DEG,
        defaultRate = SensorRate.Hz(10.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_ROTATION_VECTOR,
    ),
    PITCH(
        subject = Subjects.PITCH_DEG,
        defaultRate = SensorRate.Hz(10.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_ROTATION_VECTOR,
        rateOwner = Subjects.ROLL_DEG,
    ),
    YAW(
        subject = Subjects.YAW_DEG,
        defaultRate = SensorRate.Hz(10.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_ROTATION_VECTOR,
        rateOwner = Subjects.ROLL_DEG,
    ),
    /**
     * Body rotation rates about the same three axes, in degrees per second.
     *
     * A unit conversion of `angular_velocity_radps`, which is already on the bus as a vector in rad/s —
     * published separately because "roll rate" is the name every marine and aviation system uses for
     * it, and because a scalar can be plotted and alarmed on where a vector component cannot.
     *
     * **Body rates, not the derivatives of the angles above.** They coincide only near level; away
     * from it Euler rates and body rates differ by a transformation, and claiming otherwise would be
     * wrong in exactly the conditions these are interesting in.
     */
    ROLL_RATE(
        subject = Subjects.ROLL_RATE_DEGPS,
        defaultRate = SensorRate.Hz(10.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_GYROSCOPE,
    ),
    PITCH_RATE(
        subject = Subjects.PITCH_RATE_DEGPS,
        defaultRate = SensorRate.Hz(10.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_GYROSCOPE,
        rateOwner = Subjects.ROLL_RATE_DEGPS,
    ),
    YAW_RATE(
        subject = Subjects.YAW_RATE_DEGPS,
        defaultRate = SensorRate.Hz(10.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_GYROSCOPE,
        rateOwner = Subjects.ROLL_RATE_DEGPS,
    ),
    /**
     * The IMU's own die temperature, where a device exposes one.
     *
     * What explains gyro bias drift on a phone sitting in the sun, and it is the *chip's* temperature
     * rather than the air's — which is the point, since the chip runs hotter than what is around it.
     *
     * No `sensorType`, deliberately: this comes from a **vendor** sensor found by string type
     * (`com.google.sensor.gyro_temperature`), because Android has no constant for it and the numeric
     * type is assigned per vendor. `unavailableSubjects()` asks for it directly instead.
     */
    IMU_TEMPERATURE(
        subject = Subjects.IMU_TEMPERATURE_CELSIUS,
        defaultRate = SensorRate.Hz(0.2),
        source = SourceKind.IMU,
    ),
    HEADING_ACCURACY(
        subject = Subjects.HEADING_ACCURACY_DEG,
        defaultRate = SensorRate.Hz(50.0),
        source = SourceKind.IMU,
        sensorType = Sensor.TYPE_ROTATION_VECTOR,
        rateOwner = Subjects.ORIENTATION_QUATERNION,
    ),
    /**
     * Ambient light, in lux.
     *
     * The sensor is **on-change**: it reports when the light changes and can go many seconds without
     * an event. The collector holds the last reading and republishes it at this rate — see
     * `sensors/SampleHold.kt` — so the rate here means what it does everywhere else.
     */
    ILLUMINANCE(
        subject = Subjects.ILLUMINANCE_LUX,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.DEVICE,
        sensorType = Sensor.TYPE_LIGHT,
    ),
    /**
     * The microphone, as one WAV chunk per publish.
     *
     * The rate here *is* the chunk length — 1 Hz means one-second chunks — because an audio frame is
     * one indivisible subject (protocol specification §1b) and the only question is how long a frame is.
     * Off unless `Settings.audioEnabled`, so unlike every other entry this one can be present in the
     * registry and publish nothing at all.
     */
    AUDIO(
        subject = Subjects.AUDIO,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.DEVICE,
    ),
    /**
     * The camera, as one JPEG per publish.
     *
     * The rate is the time-lapse interval — 0.5 Hz is a frame every two seconds — for the same reason
     * as [AUDIO]: a frame is one indivisible subject, so the only question is how often one is taken.
     * Off unless `Settings.cameraEnabled`, and the heaviest subject in the app when it is on: about
     * 270 MB/h at the default, against ~77 MB/h for everything else combined.
     *
     * No `sensorType` — a camera is not a `SensorManager` sensor, so `unavailableSubjects()` answers
     * for it with a `PackageManager` feature check instead.
     */
    IMAGE_COMPRESSED(
        subject = Subjects.IMAGE_COMPRESSED,
        defaultRate = SensorRate.Hz(0.5),
        source = SourceKind.DEVICE,
        bufferedForReplay = false,
    ),

    /**
     * Continuous H.264, one message per frame.
     *
     * Shares the camera with [IMAGE_COMPRESSED] rather than replacing it — a time-lapse is the cheap
     * option for a long unattended run and this is the one that shows motion, and the two bind as one
     * `Preview` + `ImageCapture` pair on a single camera session.
     *
     * `bufferedForReplay = false` for the same reason as the stills, only more so: `replay()` paces by
     * *message count*, so a buffered second of video is a burst of ten multi-kilobyte frames that the
     * DROP-everywhere egress queue sheds silently, taking the live navigation data queued behind it.
     * The recording is the complete copy.
     *
     * The rate here is the *encoder's* frame rate rather than a sampling interval — nothing polls it,
     * the camera pushes — so the per-subject rate control sets what the encoder is asked to produce.
     */
    VIDEO_COMPRESSED(
        subject = Subjects.VIDEO_COMPRESSED,
        defaultRate = SensorRate.Hz(10.0),
        source = SourceKind.DEVICE,
        bufferedForReplay = false,
    ),
    /**
     * Operator annotations — a marker pressed by a human to say "this is the bit that matters".
     *
     * The only subject with no collector behind it: [se.rise.logline.publish.SensorPublisher.mark]
     * publishes it directly, so it has no entry in `COLLECTOR_GROUPS` and has no sensor to release when
     * it is switched off. It still goes through the same sink as everything else, so it records,
     * replays and counts identically.
     *
     * `background` upstream, the lowest priority on the bus: an annotation must never crowd out live
     * navigation data, and nothing downstream is waiting on it in real time.
     */
    LOG_MESSAGE(
        subject = Subjects.LOG_MESSAGE,
        // Never read: `eventDriven` entries have no stream to sample. The field is not nullable, and
        // making it so for this one case would push a null check into every rate lookup.
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.DEVICE,
        eventDriven = true,
    ),
    // Atmospheric pressure moves slowly and the sensor caps at 25 Hz anyway; 1 Hz is the useful
    // default, with the control there for anyone logging pressure for heave.
    AIR_PRESSURE(
        subject = Subjects.AIR_PRESSURE_PA,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.DEVICE,
        sensorType = Sensor.TYPE_PRESSURE,
        featured = true,
    ),
    // The battery subjects are polled rather than event-driven, so they fit the same rate model as
    // everything else. 0.2 Hz is one sample every five seconds — the values change far slower.
    BATTERY_STATE_OF_CHARGE(
        subject = Subjects.BATTERY_STATE_OF_CHARGE_PCT,
        defaultRate = SensorRate.Hz(0.2),
        source = SourceKind.DEVICE,
        featured = true,
    ),
    BATTERY_VOLTAGE(
        subject = Subjects.BATTERY_VOLTAGE_V,
        defaultRate = SensorRate.Hz(0.2),
        source = SourceKind.DEVICE,
        rateOwner = Subjects.BATTERY_STATE_OF_CHARGE_PCT,
    ),
    BATTERY_CURRENT(
        subject = Subjects.BATTERY_CURRENT_A,
        defaultRate = SensorRate.Hz(0.2),
        source = SourceKind.DEVICE,
        rateOwner = Subjects.BATTERY_STATE_OF_CHARGE_PCT,
    ),
    BATTERY_TEMPERATURE(
        subject = Subjects.BATTERY_TEMPERATURE_CELSIUS,
        defaultRate = SensorRate.Hz(0.2),
        source = SourceKind.DEVICE,
        rateOwner = Subjects.BATTERY_STATE_OF_CHARGE_PCT,
    ),
    /**
     * How long the phone has been up, which is the field that tells a reboot from a restart.
     *
     * Months later, a gap in a recording has two explanations that look identical from the data — the
     * app was stopped and started, or the phone went down and came back — and they mean very different
     * things about an unattended rig. Uptime resetting across the gap says which.
     *
     * `elapsedRealtime`, so deep sleep counts: the phone was up, it was merely asleep. Rides the
     * battery poll because it is the same kind of question about the same device, and because a
     * monotonic counter needs no rate of its own.
     */
    DEVICE_UPTIME(
        subject = Subjects.DEVICE_UPTIME_DURATION,
        defaultRate = SensorRate.Hz(0.2),
        source = SourceKind.DEVICE,
        rateOwner = Subjects.BATTERY_STATE_OF_CHARGE_PCT,
    ),
    BATTERY_IS_CHARGING(
        subject = Subjects.BATTERY_IS_CHARGING,
        defaultRate = SensorRate.Hz(0.2),
        source = SourceKind.DEVICE,
        rateOwner = Subjects.BATTERY_STATE_OF_CHARGE_PCT,
    ),

    // Radio link quality, one poll per tick feeding both links. 1 Hz by default to match location_fix:
    // the value of this data is correlating link quality against position along a route.
    CELLULAR_RSRP(
        subject = Subjects.RADIO_RSRP_DBM,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.CELLULAR,
    ),
    CELLULAR_RSRQ(
        subject = Subjects.RADIO_RSRQ_DB,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.CELLULAR,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),
    CELLULAR_SINR(
        subject = Subjects.RADIO_SINR_DB,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.CELLULAR,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),
    CELLULAR_RSSI(
        subject = Subjects.RADIO_RSSI_DBM,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.CELLULAR,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),
    CELLULAR_ACCESS_TECHNOLOGY(
        subject = Subjects.RADIO_ACCESS_TECHNOLOGY,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.CELLULAR,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),
    // Same subject as CELLULAR_RSSI, different link. This pair is the reason the registry key is the
    // entry rather than the subject name.
    WIFI_RSSI(
        subject = Subjects.RADIO_RSSI_DBM,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.WIFI,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),
    WIFI_DOWNLINK_BITRATE(
        subject = Subjects.RADIO_DOWNLINK_BITRATE_BPS,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.WIFI,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),
    WIFI_UPLINK_BITRATE(
        subject = Subjects.RADIO_UPLINK_BITRATE_BPS,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.WIFI,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),

    // Serving cell identity. Read from the same poll as the quality metrics, but gated on
    // ACCESS_FINE_LOCATION because `getAllCellInfo()` requires it — so an IMU-only run publishes the
    // quality subjects and none of these.
    CELL_ID(
        subject = Subjects.RADIO_CELL_ID,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.CELLULAR,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),
    PHYSICAL_CELL_ID(
        subject = Subjects.RADIO_PHYSICAL_CELL_ID,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.CELLULAR,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),
    EARFCN(
        subject = Subjects.RADIO_EARFCN,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.CELLULAR,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),
    BAND(
        subject = Subjects.RADIO_BAND,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.CELLULAR,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),

    /**
     * The serving cell's bandwidth, LTE only.
     *
     * Silent on 5G NR, and that is correct rather than a gap — `CellIdentityNr` carries no bandwidth
     * field at all, so there is nothing to report. It is deliberately **not** in
     * `unavailableSubjects()` for the same reason the nullable radio fields are not: the hardware is
     * present and the value is momentarily absent, which is a different thing from a phone without a
     * modem.
     */
    CELL_BANDWIDTH_DOWNLINK(
        subject = Subjects.RADIO_DOWNLINK_BANDWIDTH_MHZ,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.RADIO,
        fixedSourceId = RadioSources.CELLULAR,
        rateOwner = Subjects.RADIO_RSRP_DBM,
    ),

    /**
     * One sensor's pose on the rig, as `foxglove.FrameTransform`.
     *
     * The rate is a **republish interval**, not a sampling rate — the same reading of the control that
     * [AUDIO] gives it as a chunk length and [IMAGE_COMPRESSED] as a time-lapse interval. Nothing is
     * being measured; the geometry is already known, and the loop exists so a subscriber that joins
     * late gets it. 0.1 Hz is every ten seconds, which is what `platform-geometry2keelson.py` uses.
     *
     * **One message per sensor, all on the one key.** That is upstream's shape: the sensor is named by
     * `child_frame_id` inside the message rather than by the source id. It follows that Zenoh's
     * latest-value store holds only the last transform of each round — which is exactly why the loop
     * repeats rather than publishing once.
     *
     * Not buffered for replay: a transform superseded ten seconds later is worth nothing filled in
     * afterwards, and the next round covers the gap anyway.
     */
    FRAME_TRANSFORM(
        subject = Subjects.FRAME_TRANSFORM,
        defaultRate = SensorRate.Hz(0.1),
        source = SourceKind.CALIBRATION,
        bufferedForReplay = false,
    ),

    /**
     * Where the rig's zero point *was when it was surveyed*, as `foxglove.LocationFix`.
     *
     * The second entry publishing `location_fix`, and the reason this is safe is the key: it goes out
     * under the **rig's** entity and the `calibration` source, beside the transforms it anchors, while
     * the phone's live fix stays under the phone's entity and its own source. The two are never the
     * same key.
     *
     * It is a genuine observation rather than a plan — it carries an accuracy, and the protocol
     * specification's test for `LocationFix` versus `keelson.Coordinate` is exactly whether the
     * position has measurement context a consumer could act on. Its payload timestamp is therefore the
     * **survey time**, not the publish time: a consumer that checks how old the observation is gets a
     * straight answer, which is the one defence against reading a surveyed anchor as a live position.
     *
     * `rateOwner` is `frame_transform` and not `location_fix`: it rides the calibration loop, so its
     * screen must not offer a rate control that would change the phone's GNSS rate instead.
     */
    CALIBRATION_ZERO(
        subject = Subjects.LOCATION_FIX,
        defaultRate = SensorRate.Hz(0.1),
        source = SourceKind.CALIBRATION,
        rateOwner = Subjects.FRAME_TRANSFORM,
        bufferedForReplay = false,
    ),

    /**
     * The whole rig geometry as one JSON document, on the same ten-second loop.
     *
     * `rateOwner` because it rides [FRAME_TRANSFORM]'s ticker: they are two views of one calibration
     * and publishing them at different intervals would let a consumer see a document that disagrees
     * with the transforms beside it.
     */
    CONFIGURATION_JSON(
        subject = Subjects.CONFIGURATION_JSON,
        defaultRate = SensorRate.Hz(0.1),
        source = SourceKind.CALIBRATION,
        rateOwner = Subjects.FRAME_TRANSFORM,
        bufferedForReplay = false,
    ),
    ;

    /** True when the rate control belongs to another subject and this one just follows along. */
    val followsAnotherRate: Boolean get() = rateOwner != null

    /** What the main screen calls this entry — the subject, plus the link when one subject has two. */
    val label: String get() = fixedSourceId?.let { "$subject · $it" } ?: subject

    /**
     * The entry whose rate governs this one, or null where it owns its own.
     *
     * Matched on [source] as well as the subject, because a subject string does **not** identify an
     * entry: `location_fix` is published by both [LOCATION_FIX] and [CALIBRATION_ZERO], so
     * [forSubject] would answer with whichever of the two comes first in the enum. That happens to be
     * the right one today, which is precisely why it is worth pinning — a reordering of the entries
     * would silently point the rig's surveyed zero point at the phone's live fix.
     *
     * One hop always reaches the head: no entry names an owner that has an owner, and
     * `SubjectRegistryTest` holds that.
     */
    fun rateOwnerEntry(): PublishedSubject? = rateOwner?.let { owner ->
        entries.firstOrNull { it.subject == owner && it.source == source }
    }

    companion object {
        /**
         * The first entry publishing this subject.
         *
         * Subject-scoped settings — QoS profile, sampling rate, sensor capabilities — are shared by
         * every entry with the same subject, which is correct: a subject is supposed to travel
         * identically wherever it comes from, and the duplicate entries here are one poll anyway.
         * For entry identity (status, navigation) use [forName].
         */
        fun forSubject(subject: String): PublishedSubject? = entries.firstOrNull { it.subject == subject }

        /** Lookup by entry name, which is unique where the subject name is not. */
        fun forName(name: String): PublishedSubject? = entries.firstOrNull { it.name == name }
    }
}
