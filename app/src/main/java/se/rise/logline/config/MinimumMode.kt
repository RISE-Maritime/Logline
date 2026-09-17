package se.rise.logline.config

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.sensors.SensorRate

/**
 * What a **Minimum** run keeps: where the phone was, whether it could say so, its power, and the marks.
 *
 * For a phone carried only to mark events. Its marks are worth little without knowing where it was, so
 * position stays; nearly everything else goes. The IMU goes on purpose, not to save space: somebody
 * picking the phone up would put a burst of acceleration in the file that looks exactly like an event.
 *
 * **`BATTERY_CURRENT` is here to make this mode's own cost measurable.** How long an event-marker phone
 * lasts is the question people ask of Minimum, and answering it from `battery_state_of_charge_pct`
 * alone means watching a gauge that moves in whole percentage points — an hour of recording bought four
 * of them. The current is a direct reading, it rides the battery poll that is already running
 * (`rateOwner = BATTERY_STATE_OF_CHARGE_PCT`, so no extra collector and no extra listener), and it costs
 * about 0.02 MB/h against the mode's measured 0.36.
 *
 * **A mode layered over the settings, never a write to them.** [Settings.offSubjects] adds everything
 * outside this set and [Settings.recordRate] caps position at [MINIMUM_POSITION_RATE]. Neither touches
 * `disabledSubjects` or the rate maps, so switching back to Full returns the tuned profile intact. That
 * is the argument `recordAllMax` makes, and the cap is checked **before** it, or Maximum would lift
 * position straight back to Max.
 *
 * **Entries, not subject names.** Four entries publish `location_fix`. Only the fused one belongs here;
 * the unfused `gnss` and `network` fixes and the platform's surveyed zero stay off.
 */
val MINIMUM_SUBJECTS: Set<PublishedSubject> = setOf(
    PublishedSubject.LOCATION_FIX,
    PublishedSubject.SPEED_OVER_GROUND,
    PublishedSubject.COURSE_OVER_GROUND,
    PublishedSubject.FIX_QUALITY,
    PublishedSubject.ACCURACY_HORIZONTAL,
    PublishedSubject.BATTERY_STATE_OF_CHARGE,
    PublishedSubject.BATTERY_CURRENT,
    PublishedSubject.BATTERY_IS_CHARGING,
    PublishedSubject.LOG_MESSAGE,
)

/** One position every five seconds: enough to place a mark, and far less than a track wants. */
val MINIMUM_POSITION_RATE: SensorRate = SensorRate.Hz(0.2)

/** Everything a Minimum run switches off, whatever the per-subject switches say. */
fun minimumForcedOff(): Set<PublishedSubject> = PublishedSubject.entries.toSet() - MINIMUM_SUBJECTS
