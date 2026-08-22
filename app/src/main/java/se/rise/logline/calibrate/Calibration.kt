package se.rise.logline.calibrate

/**
 * A sensor platform's geometry: where the platform's zero is, and where each sensor sits relative to it.
 *
 * This is the model behind the calibration screen and behind the two subjects that carry it —
 * `frame_transform` (one message per sensor) and `configuration_json` (the whole document). It is pure
 * data with no Android in it, so all of it is unit-testable.
 *
 * The shape is not invented here. It is `keelson/connectors/platform/config-schema.json`, so what this
 * app publishes and what `platform-geometry2keelson.py` publishes are the same document, and the
 * exported file can be handed to that connector unchanged. Fields upstream has that this screen does
 * not collect — `operational_limits`, `vessel_outlines`, MMSI/IMO/call sign — are simply omitted;
 * every one of them is optional.
 */
data class PlatformCalibration(
    /** Human name of the platform, e.g. `SSRS18`. Everything else defaults from this. */
    val name: String,
    /**
     * The `entity_id` the calibration publishes under.
     *
     * **The platform, not the phone.** `entity_id` names the physical thing the data is about, and the
     * geometry of a platform belongs to the platform even though a phone measured it. Defaults to the slugified
     * name; leaving it equal to the phone's own entity id is legal, just less useful.
     */
    val entityId: String,
    /** The frame every sensor hangs off. Upstream's example names it `<platform>-frame-ccrp`. */
    val parentFrameId: String,
    val platformType: PlatformType? = null,
    val description: String = "",
    val lengthOverAllM: Double? = null,
    val breadthOverAllM: Double? = null,
    /**
     * Where the CCRP sits in the platform frame.
     *
     * Zero by default, which says the CCRP *is* the zero point — the usual case, and the one that
     * makes [parentFrameId]'s name true. Upstream's note is worth repeating: the CCRP is the common
     * navigation point and does not have to be the origin of the local coordinate system.
     */
    val ccrp: Vec3M = Vec3M.ZERO,
    /**
     * The surveyed origin, when there is one.
     *
     * Null is a perfectly good calibration: a platform measured with a tape needs no position at all. The
     * zero only exists so that a *GNSS-captured* sensor offset has something to be relative to.
     */
    val zero: PlatformZero? = null,
    val sensors: List<SensorMount> = emptyList(),
    val updatedAtEpochMillis: Long = 0L,
) {
    /** Nothing to publish until at least one sensor has been placed. */
    val isPublishable: Boolean get() = sensors.isNotEmpty()

    companion object {
        fun forName(name: String, atEpochMillis: Long = 0L) = PlatformCalibration(
            name = name,
            entityId = defaultEntityId(name),
            parentFrameId = defaultParentFrameId(name),
            updatedAtEpochMillis = atEpochMillis,
        )
    }
}

/**
 * The platform's origin: a position, and which way the platform points.
 *
 * The heading is the part that turns a pair of positions into an offset — without it "10 m north" says
 * nothing about whether that is ahead or abeam. All three ways of establishing it are recorded rather
 * than reduced to a number, because they are worth very different amounts: a baseline over 20 m is
 * good to a degree or so, a phone compass is good to five or ten and worse near steel.
 */
data class PlatformZero(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    /** The platform's own horizontal accuracy estimate at capture, metres. Null when typed. */
    val accuracyM: Double?,
    /**
     * The vertical accuracy at capture, metres.
     *
     * Kept because publishing the zero as a `location_fix` needs *both* to state a covariance, and
     * because vertical GNSS error is roughly twice the horizontal — the axis a mast height is measured
     * along is the one the phone is worst at, and that is worth saying out loud rather than hiding
     * inside a single "accuracy".
     */
    val verticalAccuracyM: Double? = null,
    /** How far the averaged samples fell from their own mean. See [AveragedFix]. */
    val scatterM: Double?,
    /** True heading of the platform's +X (forward) axis, degrees. */
    val headingDeg: Double,
    val headingSource: HeadingSource,
    val capture: CaptureMethod,
    val samples: Int,
    val capturedAtEpochMillis: Long,
) {
    fun point(): LatLonAlt = LatLonAlt(latitude, longitude, altitudeM ?: 0.0)

    /**
     * Whether there is a surveyed position here, as opposed to only a forward axis.
     *
     * A heading typed before anything was captured is a legitimate half-calibration — it is what turns
     * "10 m north" into "10 m ahead" — and it is stored with a position of 0, 0. That is Null Island in
     * the Gulf of Guinea, which nothing is ever calibrated at, so it doubles as the absent marker: an
     * offset capture against it would measure the distance from West Africa.
     */
    val hasPosition: Boolean get() = latitude != 0.0 || longitude != 0.0
}

/**
 * One sensor's pose on the platform.
 *
 * Translation is in the platform frame — X forward, Y starboard, Z down, metres — and rotation is how the
 * sensor is aimed, in the yaw → pitch → roll order upstream applies them.
 *
 * The rotation is always typed. A phone held next to a radar can measure where the radar *is*; it
 * cannot measure where the radar is *looking*, and offering a "capture" button for it would invent a
 * measurement.
 */
data class SensorMount(
    val label: String,
    /** `child_frame_id` on the wire. */
    val frameId: String,
    val sensorType: SensorType,
    val translation: Vec3M,
    val rotation: EulerDeg = EulerDeg.ZERO,
    val capture: CaptureMethod = CaptureMethod.MANUAL,
    /** Fix accuracy at capture, metres. Null when the offset was typed. */
    val accuracyM: Double? = null,
    val capturedAtEpochMillis: Long = 0L,
) {
    /**
     * True when the fix was worth less than the offset it produced.
     *
     * The honest question about a GNSS-captured offset: a ±4 m fix that produced a 0.6 m offset has
     * measured noise, not geometry. The screen says so in the error colour rather than showing six
     * confident decimal places.
     */
    val accuracyExceedsOffset: Boolean
        get() = capture == CaptureMethod.GNSS_AVERAGE &&
            accuracyM != null &&
            accuracyM > translation.magnitude()
}

/** Upstream's `platform_type` vocabulary, verbatim. */
enum class PlatformType(val wire: String) {
    VESSEL("vessel"),
    LANDKRABBA("landkrabba"),
    ROC("roc"),
}

/** Upstream's `sensor_type` vocabulary, verbatim. Anything else is `other` by design. */
enum class SensorType(val wire: String, val label: String) {
    CAMERA("camera", "Camera"),
    LIDAR("lidar", "Lidar"),
    RADAR("radar", "Radar"),
    GNSS("gnss", "GNSS"),
    IMU("imu", "IMU"),
    OTHER("other", "Other"),
}

/** How the platform's forward axis was established. */
enum class HeadingSource(val label: String) {
    /** Bearing from the zero to a second point ahead on the centreline. */
    BASELINE("Baseline"),

    /** The phone's own true-north heading, held flat with its top edge forward. */
    COMPASS("Compass"),
    MANUAL("Typed"),
}

/** Where a number came from. Kept per measurement, because it is what says how much to trust it. */
enum class CaptureMethod(val label: String) {
    GNSS_AVERAGE("Averaged fix"),
    MANUAL("Typed"),
}

/**
 * Lowercase, hyphen-separated, no leading or trailing hyphen.
 *
 * Hyphens rather than the underscores `slugifyModel()` uses for the phone's entity id: keelson's own
 * examples spell platform entities and frame ids `vessel-example` and `ssrs18-demo-frame-ccrp`, and these
 * strings end up beside those.
 */
fun slugify(text: String): String =
    text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/**
 * What a keelson entity id may look like — crowsnest's `ENTITY_ID_RE`, transcribed.
 *
 * It is not cosmetic. The id is interpolated straight into `{realm}/@v0/{entity_id}/pubsub/...`, so a
 * `/` in it silently adds a chunk and the key stops being the key anybody subscribes to; it is also a
 * path segment in this app's own navigation routes, where an extra chunk matches no destination at
 * all. Enforced where a person types one and where an imported document supplies one.
 */
private val ENTITY_ID = Regex("^[a-z0-9][a-z0-9_-]*$")

fun isValidEntityId(entityId: String): Boolean = ENTITY_ID.matches(entityId)

fun defaultEntityId(platformName: String): String = slugify(platformName).ifEmpty { "platform" }

fun defaultParentFrameId(platformName: String): String = "${defaultEntityId(platformName)}-frame-ccrp"

/** `SSRS18` + `Ouster OS lidar` → `ssrs18-frame-ouster-os-lidar`. */
fun defaultFrameId(platformName: String, sensorLabel: String): String {
    val sensor = slugify(sensorLabel).ifEmpty { "sensor" }
    return "${defaultEntityId(platformName)}-frame-$sensor"
}
