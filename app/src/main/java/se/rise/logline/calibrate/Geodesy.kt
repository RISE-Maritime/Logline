package se.rise.logline.calibrate

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The coordinate maths behind a platform calibration, and the only place any of it lives.
 *
 * Everything here is pure and unit-tested against physical constants, for the same reason
 * `sensors/Units.kt` is: the failure mode of a wrong sign or a wrong radius is a plausible number that
 * is simply wrong, and nothing downstream can tell.
 *
 * Two frames are involved and they are not the same:
 *
 * - **ENU** — the local tangent plane at the platform zero: east, north, up. This is what the difference
 *   between two WGS84 positions naturally produces.
 * - **The platform frame** — X forward, Y to starboard, **Z down**, per
 *   `keelson/connectors/platform/README.md`. Maritime convention, not ROS: getting Z's sign wrong puts
 *   every mast below the waterline.
 *
 * [bodyOffsetMetres] is the crossing between them and takes the platform's heading to do it.
 */

/** WGS84, the datum every GNSS fix on this phone is already in. */
private const val SEMI_MAJOR_AXIS_M = 6_378_137.0
private const val FLATTENING = 1.0 / 298.257223563
private const val ECCENTRICITY_SQUARED = FLATTENING * (2.0 - FLATTENING)

/** A geodetic position. Altitude is metres above the ellipsoid, as Android reports it. */
data class LatLonAlt(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double = 0.0,
)

/** A local tangent-plane offset in metres. East, north, up — not the platform frame. */
data class Enu(val eastM: Double, val northM: Double, val upM: Double)

/** A point in the platform frame: X forward, Y starboard, Z down, metres. */
data class Vec3M(val x: Double, val y: Double, val z: Double) {
    companion object {
        val ZERO = Vec3M(0.0, 0.0, 0.0)
    }

    /** Distance from the platform origin — what the fix accuracy has to be compared against. */
    fun magnitude(): Double = sqrt(x * x + y * y + z * z)
}

/**
 * Radius of curvature in the meridian, metres.
 *
 * One degree of latitude is this times one degree in radians: 110 574 m at the equator, 111 694 m at
 * the pole. A sphere would give one number for both, and the 1 km difference is exactly the error a
 * spherical earth introduces into a north-south offset.
 */
fun meridianRadiusM(latitudeDeg: Double): Double {
    val sinLat = sin(Math.toRadians(latitudeDeg))
    return SEMI_MAJOR_AXIS_M * (1.0 - ECCENTRICITY_SQUARED) /
        (1.0 - ECCENTRICITY_SQUARED * sinLat * sinLat).pow(1.5)
}

/** Radius of curvature in the prime vertical, metres — the east-west counterpart. */
fun primeVerticalRadiusM(latitudeDeg: Double): Double {
    val sinLat = sin(Math.toRadians(latitudeDeg))
    return SEMI_MAJOR_AXIS_M / sqrt(1.0 - ECCENTRICITY_SQUARED * sinLat * sinLat)
}

/**
 * The offset from [from] to [to] on the local tangent plane, in metres.
 *
 * Flat-earth on purpose, and honest about it: over the tens of metres a platform spans, the curvature error
 * is sub-millimetre, and the alternative — full ECEF round-tripping — would trade that for a lot of
 * arithmetic nobody can check by hand. The radii are evaluated at the *mean* latitude of the two
 * points, which is what keeps a long baseline symmetric.
 *
 * Not valid across the antimeridian; a platform that spans ±180° longitude is not a platform.
 */
fun enuOffsetMetres(from: LatLonAlt, to: LatLonAlt): Enu {
    val meanLat = (from.latitude + to.latitude) / 2.0
    val dLat = Math.toRadians(to.latitude - from.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)
    return Enu(
        eastM = dLon * primeVerticalRadiusM(meanLat) * cos(Math.toRadians(meanLat)),
        northM = dLat * meridianRadiusM(meanLat),
        upM = to.altitudeM - from.altitudeM,
    )
}

/**
 * Initial great-circle bearing from [from] to [to], degrees true, in `[0, 360)`.
 *
 * This is how the two-point baseline establishes the platform's forward axis: stand at the zero, walk to a
 * point ahead on the centreline, and the bearing between them is the heading of +X.
 */
fun initialBearingDegrees(from: LatLonAlt, to: LatLonAlt): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * A forward axis taken from two points: which way, and over how far.
 *
 * The length is not decoration — it is what says how much the bearing is worth. Angular error is
 * position error divided by baseline length, so a metre of uncertainty is about thirty degrees over
 * two metres and under three over twenty. Returning both together is what stops a caller recording
 * one without the other.
 */
data class Baseline(val bearingDegrees: Double, val lengthM: Double)

fun headingFromBaseline(from: LatLonAlt, to: LatLonAlt): Baseline {
    val enu = enuOffsetMetres(from, to)
    return Baseline(
        bearingDegrees = initialBearingDegrees(from, to),
        lengthM = hypot(enu.eastM, enu.northM),
    )
}

/**
 * The angle a metre of position error subtends over a baseline of [lengthM].
 *
 * The whole "walk further" instruction in one number, so a screen can state it at the moment
 * somebody is choosing where to put the far point rather than in a paragraph they read once.
 * Degenerate below a metre or so, where the answer is "this baseline is not worth having".
 */
fun degreesPerMetreOfError(lengthM: Double): Double =
    if (lengthM <= 0.0) Double.POSITIVE_INFINITY else Math.toDegrees(atan2(1.0, lengthM))

/**
 * Rotate a local ENU offset into the platform frame, given the true heading of the platform's +X axis.
 *
 * ```
 * x =  dN·cos h + dE·sin h      forward
 * y = -dN·sin h + dE·cos h      starboard
 * z = -dUp                      down
 * ```
 *
 * The sign flip on Z is the whole difference between ENU and the maritime frame, and it is the easiest
 * thing here to get silently wrong — a mast measured 3 m up would be published 3 m below the keel.
 */
fun bodyOffsetMetres(enu: Enu, platformHeadingDeg: Double): Vec3M {
    val h = Math.toRadians(platformHeadingDeg)
    return Vec3M(
        x = enu.northM * cos(h) + enu.eastM * sin(h),
        y = -enu.northM * sin(h) + enu.eastM * cos(h),
        z = -enu.upM,
    )
}

/**
 * The inverse of [bodyOffsetMetres]: where a platform-frame offset lands in local ENU.
 *
 * Needed to draw a sensor that has already been measured — the model stores forward/starboard/down,
 * and a map needs a latitude and a longitude. Rotation by −h, and the same sign flip on Z:
 *
 * ```
 * n = x·cos h − y·sin h
 * e = x·sin h + y·cos h
 * u = −z
 * ```
 */
fun enuFromBodyOffset(body: Vec3M, platformHeadingDeg: Double): Enu {
    val h = Math.toRadians(platformHeadingDeg)
    return Enu(
        eastM = body.x * sin(h) + body.y * cos(h),
        northM = body.x * cos(h) - body.y * sin(h),
        upM = -body.z,
    )
}

/**
 * The inverse of [enuOffsetMetres]: the point a local ENU offset reaches from [from].
 *
 * **Uses [from]'s own latitude for the radii rather than the mean of the two**, which the forward
 * direction can compute because it knows both ends. The cost grows with distance and is measured
 * rather than asserted: **sub-millimetre within about a hundred metres, and about three centimetres
 * at five hundred** (`GeodesyInverseTest`). Platform-scale offsets are the first of those, and three
 * centimetres is well inside what the offset itself is known to.
 *
 * The first draft of this comment claimed sub-millimetre out to "a few hundred metres" and the round
 * trip disagreed, which is the reason the figure here is a measurement.
 */
fun pointFromEnuOffset(from: LatLonAlt, enu: Enu): LatLonAlt {
    val lat = Math.toRadians(from.latitude)
    return LatLonAlt(
        latitude = from.latitude + Math.toDegrees(enu.northM / meridianRadiusM(from.latitude)),
        longitude = from.longitude +
            Math.toDegrees(enu.eastM / (primeVerticalRadiusM(from.latitude) * cos(lat))),
        altitudeM = from.altitudeM + enu.upM,
    )
}

/**
 * How far, and which way, one bearing is from another — **the short way round**.
 *
 * Positive is clockwise, i.e. to starboard. 350° to 10° is `+20`, not `-340`: the long way round is
 * arithmetically true and is not what anybody means by "it turned", which is the same reason
 * `circularMeanDegrees` exists a few lines down. Exactly ±180 is a reversal and comes back positive
 * rather than arbitrarily; nothing downstream distinguishes the two and a sign that flipped on the
 * last bit would be worse than one that is simply stated.
 */
fun turnDegrees(from: Double, to: Double): Double {
    val delta = ((to - from) % 360.0 + 540.0) % 360.0 - 180.0
    return if (delta == -180.0) 180.0 else delta
}

/**
 * The mean of a set of headings, degrees in `[0, 360)`.
 *
 * A plain average is wrong at north and wrong in a way that looks right: 359° and 1° average to 180°,
 * which is due south. Summing unit vectors and taking the angle back is the only mean an angle has.
 * The same reasoning as `unwrapAngles()` in `ui/LiveSignals.kt`, which exists so a heading crossing
 * north does not draw a cliff.
 */
fun circularMeanDegrees(degrees: List<Double>): Double? {
    if (degrees.isEmpty()) return null
    val east = degrees.sumOf { sin(Math.toRadians(it)) }
    val north = degrees.sumOf { cos(Math.toRadians(it)) }
    // Every direction equally represented: the vectors cancel and there is no mean to report. Vanishing
    // rare in practice and still not something to answer with an arbitrary angle.
    if (hypot(east, north) < 1e-9) return null
    return (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
}

/** One fix as the averager sees it. Nullable altitude and accuracies, because Android's are. */
data class FixSample(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val accuracyM: Double?,
    /**
     * The platform's vertical 1-sigma estimate, metres.
     *
     * Carried separately from [accuracyM] because `foxglove.LocationFix` wants both to state a
     * covariance at all: east and north share the horizontal radius, up needs its own, and a zero in
     * the up slot would claim the altitude was known perfectly.
     */
    val verticalAccuracyM: Double? = null,
)

/**
 * The result of standing still for a while.
 *
 * [accuracyM] is the platform's own estimate — what it thinks the *truth* is worth. [scatterM] is how
 * far the samples fell from their own mean. **They are different numbers and the difference matters**:
 * scatter is repeatability, and GNSS multipath is a bias that sits still for minutes, so a run can
 * report centimetres of scatter while being metres from the truth. The UI shows both for that reason.
 */
data class AveragedFix(
    val point: LatLonAlt,
    val hasAltitude: Boolean,
    val accuracyM: Double?,
    val verticalAccuracyM: Double?,
    val scatterM: Double,
    val samples: Int,
)

/**
 * Mean position of a set of fixes, with the scatter about that mean.
 *
 * Altitude is averaged over only the samples that carry one, and [AveragedFix.hasAltitude] says
 * whether any did — proto3 cannot tell an absent altitude from sea level, so "no altitude" has to
 * survive as its own answer rather than becoming `0.0`.
 */
fun averageFix(samples: List<FixSample>): AveragedFix? {
    if (samples.isEmpty()) return null
    val meanLat = samples.sumOf { it.latitude } / samples.size
    val meanLon = samples.sumOf { it.longitude } / samples.size
    val withAltitude = samples.mapNotNull { it.altitudeM }
    val accuracies = samples.mapNotNull { it.accuracyM }
    val verticalAccuracies = samples.mapNotNull { it.verticalAccuracyM }
    val mean = LatLonAlt(
        latitude = meanLat,
        longitude = meanLon,
        altitudeM = if (withAltitude.isEmpty()) 0.0 else withAltitude.average(),
    )
    val scatter = sqrt(
        samples.sumOf { s ->
            val enu = enuOffsetMetres(mean, LatLonAlt(s.latitude, s.longitude))
            hypot(enu.eastM, enu.northM).pow(2)
        } / samples.size
    )
    return AveragedFix(
        point = mean,
        hasAltitude = withAltitude.isNotEmpty(),
        accuracyM = if (accuracies.isEmpty()) null else accuracies.average(),
        verticalAccuracyM = if (verticalAccuracies.isEmpty()) null else verticalAccuracies.average(),
        scatterM = scatter,
        samples = samples.size,
    )
}
