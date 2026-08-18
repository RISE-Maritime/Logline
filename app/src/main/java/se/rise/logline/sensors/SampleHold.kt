package se.rise.logline.sensors

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Turn an **on-change** sensor into a steady series by holding its last reading.
 *
 * Android's ambient light sensor reports `minDelay=0`: it emits when the value changes and not
 * otherwise. Measured on a Pixel 6 in steady light, 8.5 seconds passed between two events. Published
 * raw that gives a series full of multi-second holes — awkward to resample, and indistinguishable from
 * a sensor that has died, which is exactly what the stalled detection would call it.
 *
 * So the reading is repeated on a ticker. **The held sample keeps its original timestamp**, which is
 * what makes this honest rather than fabricated: the payload still says when the light was actually
 * measured, and consecutive messages sharing one observation time are the signal that nothing new has
 * been reported. The radio subjects already behave this way and the README documents it.
 *
 * Nothing is emitted before the first upstream value. An absent sensor therefore publishes nothing at
 * all, rather than a made-up `0.0` that would read as pitch darkness.
 */
fun <T : Any> Flow<T>.heldAt(intervalMillis: Long): Flow<T> = flow {
    // Atomic for visibility, not for atomicity: the upstream collector and the ticker below are
    // separate coroutines and land on different threads of `Dispatchers.Default`, so a plain `var`
    // could hand the ticker a stale reading indefinitely. Same reasoning as the live store's locks.
    val latest = AtomicReference<T?>(null)

    coroutineScope {
        launch { collect { latest.set(it) } }

        val interval = intervalMillis.coerceAtLeast(MIN_HOLD_MILLIS)
        while (true) {
            // delay() is cancellable, so cancelling the publishing scope ends this loop and, with it,
            // the upstream collection above.
            delay(interval)
            latest.get()?.let { emit(it) }
        }
    }
}

/**
 * The floor on the ticker.
 *
 * `SensorRate.Max` means "no requested rate", which resolves to an interval of zero — and a zero-delay
 * loop republishing a value that changes over minutes would spin a core for nothing. 100 ms is far
 * faster than any light meter is interesting and still cheap.
 */
private const val MIN_HOLD_MILLIS = 100L
