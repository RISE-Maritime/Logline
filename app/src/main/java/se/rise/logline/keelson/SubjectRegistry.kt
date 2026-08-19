package se.rise.logline.keelson

import android.hardware.Sensor
import se.rise.logline.sensors.SensorRate

/**
 * Which configured source id a subject's key is built from.
 *
 * The protocol's `source_id` names *what produced the data*, so subjects that come off different
 * hardware get different ids. All three default to `phone`, which is why the usual case is still a
 * single liveliness token — that is per source, not per subject (specification §5.1).
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
) {
    LOCATION_FIX(
        subject = Subjects.LOCATION_FIX,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
    ),
    SPEED_OVER_GROUND(
        subject = Subjects.SPEED_OVER_GROUND_KNOTS,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
    ),
    COURSE_OVER_GROUND(
        subject = Subjects.COURSE_OVER_GROUND_DEG,
        defaultRate = SensorRate.Hz(1.0),
        source = SourceKind.LOCATION,
        rateOwner = Subjects.LOCATION_FIX,
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
    ),
    /**
     * The platform's own 1-sigma estimate, straight off the rotation vector's fifth component.
     *
     * Published beside the heading rather than used to gate it: a heading with a stated 60° uncertainty
     * is information, a heading silently withheld is not.
     */
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
    ),
    // The battery subjects are polled rather than event-driven, so they fit the same rate model as
    // everything else. 0.2 Hz is one sample every five seconds — the values change far slower.
    BATTERY_STATE_OF_CHARGE(
        subject = Subjects.BATTERY_STATE_OF_CHARGE_PCT,
        defaultRate = SensorRate.Hz(0.2),
        source = SourceKind.DEVICE,
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
