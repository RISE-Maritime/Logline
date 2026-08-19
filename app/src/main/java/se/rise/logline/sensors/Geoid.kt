package se.rise.logline.sensors

import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "Geoid"

/** The first Android release with `Location.getMslAltitudeMeters()` and `AltitudeConverter`. */
private const val MSL_ALTITUDE_API = 34

/** What one attempt at deriving an MSL altitude came to. */
private enum class Outcome {
    Ok,

    /** The geoid model is unreadable on this device — the same answer for every fix, so ask once. */
    NoGeoidData,

    /** This particular fix cannot be converted. Says nothing about the next one. */
    NotThisFix,
}

/** Whether this device can produce an altitude above mean sea level at all. */
fun supportsMslAltitude(): Boolean = Build.VERSION.SDK_INT >= MSL_ALTITUDE_API

/**
 * Geoid undulation: how far the geoid sits above the ellipsoid, in metres.
 *
 * `h = H + N` — ellipsoidal height is mean-sea-level height plus the undulation — so **N = h − H**,
 * which is the standard geodetic sign and the one that makes the two altitudes convertible into each
 * other by a consumer. It is positive across northern Europe (the geoid is *above* the ellipsoid
 * there, by 30-35 m in Sweden) and negative over much of the Indian Ocean, so an `abs()` here would
 * be wrong in a way that only shows up on the other side of the world.
 */
internal fun undulationMetres(ellipsoidalMetres: Double, mslMetres: Double): Double =
    ellipsoidalMetres - mslMetres

/**
 * An altitude above mean sea level for a fix, reported if the platform has one and derived if not.
 *
 * `Location.getAltitude()` is height above the **WGS84 ellipsoid**, which in Sweden is 30-35 m from
 * the altitude anyone means by the word — a discrepancy that reads as a broken sensor rather than as
 * a different reference surface. MSL is an *optional* property of a fix, and Android's fused provider
 * commonly leaves it out, so reporting only what arrives would mean a subject that never publishes on
 * most phones. `AltitudeConverter` fills the gap from the same ellipsoidal altitude.
 *
 * **The consequence is that one series can change provenance between samples** — reported on one fix,
 * derived on the next — and `TimestampedFloat` has nowhere to say which. Accepted deliberately: both
 * numbers describe the same quantity to within the geoid model's own accuracy, and the alternative is
 * no series at all. It is written down in the README rather than left to be discovered.
 *
 * Stateful on purpose, one instance per run: the converter caches its geoid data, so the first call
 * pays for loading it and the rest are cheap. A device that cannot load it at all is asked once and
 * then left alone — retrying the I/O on every fix would spend a run's worth of disk on a question
 * already answered.
 */
class MslAltitudeResolver(context: Context) {

    private val appContext = context.applicationContext
    private var converter: Any? = null
    private var unavailable = false

    /**
     * The fix's MSL altitude in metres, or null when there is none to be had.
     *
     * Suspends because the derivation reads the geoid model from disk — see [Impl.derive]. Returns
     * null rather than a default: `0.0` metres above sea level is *plausible* on a vessel, which
     * makes a zeroed value more dangerous here than in most places, not less.
     */
    suspend fun metresFor(location: Location): Double? {
        if (!supportsMslAltitude()) return null
        if (Impl.reported(location)) return Impl.value(location)
        if (unavailable) return null
        val derived = withContext(Dispatchers.IO) { Impl.derive(appContext, location, converter) }
        converter = derived.converter
        when (derived.outcome) {
            Outcome.Ok -> Unit
            // The model itself could not be loaded, which will be just as true on the next fix.
            // Asked once per run and then left alone: retrying would spend a run's worth of disk on a
            // question already answered, and log a line a second while doing it.
            Outcome.NoGeoidData -> {
                unavailable = true
                Log.w(TAG, "no geoid data on this device; publishing no MSL altitude for this run")
                return null
            }
            // This fix only — no altitude to convert from, or a position the model does not cover.
            // The next fix may well be fine, so nothing is given up.
            Outcome.NotThisFix -> return null
        }
        return if (Impl.reported(location)) Impl.value(location) else null
    }

    /**
     * Everything that touches API 34 symbols, isolated so nothing older loads them.
     *
     * A class referencing `AltitudeConverter` is verified when it is first used; keeping those uses
     * behind their own `@RequiresApi` object means an Android 11 device never reaches the class at
     * all rather than relying on the verifier being lazy about it.
     */
    @RequiresApi(MSL_ALTITUDE_API)
    private object Impl {

        fun reported(location: Location): Boolean = location.hasMslAltitude()

        fun value(location: Location): Double = location.mslAltitudeMeters

        /** Result of one derivation, carrying the converter back so its cache survives the call. */
        data class Derived(val outcome: Outcome, val converter: Any?)

        /**
         * Fill in the fix's MSL altitude from its ellipsoidal one.
         *
         * `addMslAltitudeToLocation` mutates the `Location` in place, which is why the caller re-reads
         * `hasMslAltitude()` afterwards rather than trusting a return value — it has none. The
         * non-blocking `tryAddMslAltitudeToLocation` would be preferable and is **API 35**, one
         * release later than everything else here, so this takes the blocking path on a background
         * dispatcher and works the same on both.
         */
        fun derive(context: Context, location: Location, cached: Any?): Derived {
            val converter = (cached as? AltitudeConverter) ?: AltitudeConverter()
            return try {
                converter.addMslAltitudeToLocation(context, location)
                Derived(Outcome.Ok, converter)
            } catch (e: IOException) {
                // The geoid model could not be read. A property of the device, not of this fix.
                Derived(Outcome.NoGeoidData, converter)
            } catch (e: IllegalArgumentException) {
                // Thrown for a fix the converter cannot work from — no altitude to convert, or a
                // position the model does not cover. The next fix may be perfectly convertible.
                Log.i(TAG, "this fix cannot be converted to MSL: ${e.message}")
                Derived(Outcome.NotThisFix, converter)
            }
        }
    }
}
