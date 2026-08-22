package se.rise.logline.calibrate

import android.Manifest
import android.content.Context
import android.hardware.GeomagneticField
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.withTimeoutOrNull
import se.rise.logline.sensors.ImuProvider
import se.rise.logline.sensors.LocationProvider
import se.rise.logline.sensors.normaliseHeadingDegrees

/**
 * The phone acting as a survey instrument: stand still, and answer where you are and which way you
 * are pointing.
 *
 * Deliberately thin, and deliberately not a `Flow`. A capture is a *bounded* act — the operator holds
 * the phone against a sensor for twenty seconds and gets one answer — so it is a suspending function
 * that returns a value, not a stream a screen has to subscribe to and unsubscribe from. All of the
 * maths it does is in `Geodesy.kt` and unit-tested there; what is left here is the two Android APIs.
 */
class CalibrationCapture(private val context: Context) {

    /**
     * Average the fix for [seconds], reporting each sample as it arrives so the UI can count down.
     *
     * Returns null when no fix arrived at all — indoors, or with the permission revoked between the
     * check and the call. **Averaging buys scatter, not truth**: GNSS multipath is a bias that holds
     * still for minutes, so a long average can be very repeatable and still be metres out. Both
     * numbers come back for that reason; see [AveragedFix].
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun averagePosition(seconds: Int, onSample: (Int) -> Unit = {}): AveragedFix? {
        val samples = mutableListOf<FixSample>()
        // The flow never completes on its own — it is a live sensor — so the timeout is what ends the
        // capture, and `awaitClose` in the provider unregisters the listener as the scope unwinds.
        withTimeoutOrNull(seconds * 1_000L) {
            LocationProvider(context).locations(intervalMillis = 1_000L).collect { loc ->
                samples += FixSample(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                    accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                    verticalAccuracyM = if (loc.hasVerticalAccuracy()) {
                        loc.verticalAccuracyMeters.toDouble()
                    } else {
                        null
                    },
                )
                onSample(samples.size)
            }
        }
        return averageFix(samples)
    }

    /**
     * The phone's own heading, averaged over [seconds].
     *
     * **The angle is the direction the phone's +Y axis points** — its top edge — which is why the
     * screen says to hold it flat with the top edge along the platform's centreline. Held any other way the
     * number is still a heading, just not the platform's.
     *
     * True north needs a position, because declination is a property of where you are. Without one the
     * reading comes back magnetic-only rather than silently passing off magnetic as true: on this coast
     * that is around 5° of error, and a platform's forward axis is exactly the thing that should not carry
     * an unannounced 5°.
     */
    /**
     * The sensor's rotation in the platform frame, read off the phone laid against its mounting face.
     *
     * The same shape as [heading] — collect for a few seconds, average, report what it is worth — and
     * it shares the sensor with it: `TYPE_ROTATION_VECTOR` carries the attitude the compass heading is
     * derived from, so this is the *whole* reading rather than one angle of it.
     *
     * The maths is in `SensorAttitude.kt` and is pure. Only the collecting is here.
     */
    suspend fun attitude(
        platformHeadingDeg: Double,
        near: LatLonAlt?,
        seconds: Int = 4,
        onSample: (Int) -> Unit = {},
    ): SensorAttitudeReading? {
        val samples = mutableListOf<Quat>()
        var accuracy: Double? = null
        withTimeoutOrNull(seconds * 1_000L) {
            ImuProvider(context).orientation().collect { sample ->
                samples += Quat(
                    x = sample.x.toDouble(),
                    y = sample.y.toDouble(),
                    z = sample.z.toDouble(),
                    w = sample.w.toDouble(),
                )
                accuracy = sample.headingAccuracyDegrees?.toDouble()
                onSample(samples.size)
            }
        }
        // The same declination [heading] computes, and needed for the same reason: the rotation vector
        // is referenced to magnetic north while a platform's heading is recorded as true. Null where
        // there is no position, which leaves yaw magnetic — stated rather than silently absorbed.
        val declination = near?.let {
            GeomagneticField(
                it.latitude.toFloat(),
                it.longitude.toFloat(),
                it.altitudeM.toFloat(),
                System.currentTimeMillis(),
            ).declination.toDouble()
        }
        return sensorAttitudeFrom(samples, platformHeadingDeg, declination, accuracy)
    }

    suspend fun heading(near: LatLonAlt?, seconds: Int = 4): HeadingReading? {
        val magnetic = mutableListOf<Double>()
        var accuracy: Double? = null
        withTimeoutOrNull(seconds * 1_000L) {
            ImuProvider(context).orientation().collect { sample ->
                magnetic += sample.headingMagneticDegrees.toDouble()
                accuracy = sample.headingAccuracyDegrees?.toDouble()
            }
        }
        val mean = circularMeanDegrees(magnetic) ?: return null
        val declination = near?.let {
            GeomagneticField(
                it.latitude.toFloat(),
                it.longitude.toFloat(),
                it.altitudeM.toFloat(),
                System.currentTimeMillis(),
            ).declination.toDouble()
        }
        return HeadingReading(
            magneticDegrees = mean,
            trueDegrees = declination?.let { normaliseHeadingDegrees((mean + it).toFloat()).toDouble() },
            accuracyDegrees = accuracy,
            declinationDegrees = declination,
            samples = magnetic.size,
        )
    }
}

/**
 * A compass reading, with the declination that turned it into a true one kept beside it.
 *
 * [trueDegrees] is null when there was no position to compute declination from — an answer, not a
 * failure: the screen offers the magnetic reading and says which it is.
 */
data class HeadingReading(
    val magneticDegrees: Double,
    val trueDegrees: Double?,
    val accuracyDegrees: Double?,
    val declinationDegrees: Double?,
    val samples: Int,
)
