package se.rise.logline.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One NMEA 0183 sentence as the GNSS chip emitted it, with the callback's own timestamp. */
data class NmeaSentence(val sentence: String, val timestampMillis: Long)

/**
 * The GNSS chip's own sentences, unfiltered.
 *
 * Everything else this app publishes about position comes from the *fused* provider, which blends
 * GNSS with wifi and cell and hands back a `Location` with most of the detail already thrown away.
 * These are what the receiver actually said: fix quality, DOP, satellites in view, the constellations
 * used — and they are what most of the rest of the fleet speaks, so a phone publishing them is a
 * plain NMEA source like any other box on the boat.
 *
 * **This listener does not start the GNSS engine, it only listens to one that is running.** Something
 * has to be requesting position for sentences to arrive, which on this app is the location collector
 * — so switching every GNSS subject off stops this one too, a few seconds later. That coupling is why
 * this subject rides `location_fix`'s rate rather than having one of its own: the chip decides, and
 * the fused request is what sets the chip going.
 */
class NmeaProvider(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * Sentences as they arrive, several per fix — a 1 Hz receiver typically emits GGA, RMC, GSA, VTG
     * and a handful of GSV every second, each its own message.
     *
     * A `Handler` on the main looper rather than an executor, matching how the rest of the app's
     * callbacks are delivered; the work done per sentence is a `trySend` of a string.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun sentences(): Flow<NmeaSentence> = callbackFlow {
        val locationManager = manager
        if (locationManager == null) {
            close(); return@callbackFlow
        }
        val listener = OnNmeaMessageListener { message, timestamp ->
            // Blank and malformed lines are dropped here rather than on the bus. A consumer parsing
            // NMEA has enough to deal with without this app inventing empty sentences; anything that
            // starts with $ or ! is the real thing and is passed through byte for byte.
            val sentence = message?.trim().orEmpty()
            if (sentence.startsWith("$") || sentence.startsWith("!")) {
                trySend(NmeaSentence(sentence, timestamp))
            }
        }
        locationManager.addNmeaListener(listener, Handler(Looper.getMainLooper()))
        awaitClose { locationManager.removeNmeaListener(listener) }
    }
}

/**
 * When a sentence was observed, in epoch nanoseconds.
 *
 * `OnNmeaMessageListener` documents its timestamp as milliseconds since the epoch, and that is what is
 * used when it looks like one. It is checked rather than trusted because the cost of being wrong is
 * the bug this project has already had once: a boot-clock value published raw puts the whole stream
 * decades in the past, where it is worthless and not obviously broken. Anything before [EPOCH_FLOOR]
 * is far too small to be a real date and is read as the boot clock instead, which is what the older
 * `GpsStatus.NmeaListener` supplied.
 *
 * @param timestampMillis the callback's value
 * @param elapsedNanosNow the boot clock now, for the fallback conversion
 */
internal fun nmeaEpochNanos(
    timestampMillis: Long,
    elapsedNanosNow: Long,
    wallMillisNow: Long,
): Long = if (timestampMillis >= EPOCH_FLOOR_MILLIS) {
    timestampMillis * 1_000_000L
} else {
    SensorClock.epochNanos(
        eventElapsedNanos = timestampMillis * 1_000_000L,
        wallMillis = wallMillisNow,
        elapsedNowNanos = elapsedNanosNow,
    )
}

/**
 * 2001-09-09, the round billion seconds. Far enough past any plausible uptime — a phone would have to
 * have been running for 31 years — and far enough before now to catch a device with a wrong year.
 */
private const val EPOCH_FLOOR_MILLIS = 1_000_000_000_000L
