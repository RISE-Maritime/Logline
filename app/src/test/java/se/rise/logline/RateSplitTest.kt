package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Test
import se.rise.logline.config.Settings
import se.rise.logline.keelson.Subjects
import se.rise.logline.sensors.SensorRate

/**
 * Two rates per subject: what the sensor is asked for and what reaches the bus.
 *
 * The file is what analysis is run against, so it takes everything the hardware gives; the wire is for
 * watching a trial, so it is thinned. These pin the resolution between them — including the cases where
 * "everything the hardware gives" is a meaningless request.
 */
class RateSplitTest {

    private val settings = Settings(
        realm = "rise",
        entityId = "phone",
        routerEndpoints = listOf("tls/router.example.com:443"),
        locationSource = "phone",
        imuSource = "phone",
        // Explicit: a fresh install records at maximum, and these exercise the *configured* side.
        recordAllMax = false,
    )

    /** Out of the box the file takes everything the hardware gives. */
    @Test
    fun `a fresh install records at maximum`() {
        val fresh = settings.copy(recordAllMax = true)
        assertEquals(SensorRate.Max, fresh.recordRate(Subjects.ANGULAR_VELOCITY_RADPS))
        assertEquals(SensorRate.Max, fresh.recordRate(Subjects.AIR_PRESSURE_PA))
    }

    /**
     * With the mode off, *Configured* means each subject's own rate — not Max.
     *
     * It used to fall back to Max here, which made the Session screen's "Configured" chip and the
     * megabytes-per-hour printed beside it both disagree with what the phone actually recorded.
     */
    @Test
    fun `configured means the subject's own rate, not maximum`() {
        assertEquals(SensorRate.Hz(50.0), settings.recordRate(Subjects.ANGULAR_VELOCITY_RADPS))
        assertEquals(SensorRate.Hz(1.0), settings.recordRate(Subjects.AIR_PRESSURE_PA))
        assertEquals(SensorRate.Hz(50.0), settings.publishRate(Subjects.ANGULAR_VELOCITY_RADPS))
    }

    /**
     * The case that prompted the split: the barometer is asked for 1 Hz, another app on the phone
     * drives the sensor to ~12.5 Hz, and the file should keep all of it while the bus gets 1 Hz.
     */
    @Test
    fun `the barometer records everything and publishes once a second`() {
        val fresh = settings.copy(recordAllMax = true)
        assertEquals(SensorRate.Max, fresh.recordRate(Subjects.AIR_PRESSURE_PA))
        assertEquals(SensorRate.Hz(1.0), fresh.publishRate(Subjects.AIR_PRESSURE_PA))
    }

    /**
     * Max is a real request only where something samples on its own clock. A poll at "max" would spin
     * against the telephony and power APIs; a chunk length or a capture interval of zero is nonsense.
     */
    @Test
    fun `polled, chunked and on-change subjects keep their own default`() {
        listOf(
            Subjects.BATTERY_STATE_OF_CHARGE_PCT,   // polled
            Subjects.RADIO_RSRP_DBM,                // polled
            Subjects.AUDIO,                         // a chunk length
            Subjects.IMAGE_COMPRESSED,              // a capture interval
            Subjects.FRAME_TRANSFORM,               // a republish loop
            Subjects.ILLUMINANCE_LUX,               // on-change, held on a ticker
            Subjects.IMU_TEMPERATURE_CELSIUS,       // on-change, held on a ticker
        ).forEach { subject ->
            assertEquals(
                "$subject should not default to Max",
                Settings.defaultRate(subject),
                settings.recordRate(subject),
            )
        }
    }

    /**
     * A subject that rides another's stream takes the owner's **record** rate, always — there is one
     * listener for the group and nothing per-subject to set.
     */
    @Test
    fun `a rate owner governs what the subjects riding it record`() {
        val tuned = settings.copy(recordRates = mapOf(Subjects.LOCATION_FIX to SensorRate.Hz(5.0)))
        assertEquals(SensorRate.Hz(5.0), tuned.recordRate(Subjects.SPEED_OVER_GROUND_KNOTS))
        assertEquals(SensorRate.Hz(5.0), tuned.recordRate(Subjects.COURSE_OVER_GROUND_DEG))
    }

    /**
     * **The upgrade-safety property, and the one most likely to be broken by a later tidy-up.**
     *
     * A derived subject with nothing set follows its owner on the wire too. Every derived entry happens
     * to carry a `defaultRate` equal to its owner's, so a fresh install cannot tell the difference — but
     * an install that had tuned `location_fix` down would find speed and course silently jumping back to
     * their own defaults if this fell back to `defaultRate(subject)` instead. One `?:` away from wrong,
     * and invisible everywhere except here.
     */
    @Test
    fun `an untouched derived subject follows a tuned owner onto the wire`() {
        val tuned = settings.copy(sensorRates = mapOf(Subjects.LOCATION_FIX to SensorRate.Hz(0.2)))

        assertEquals(SensorRate.Hz(0.2), tuned.publishRate(Subjects.LOCATION_FIX))
        assertEquals(SensorRate.Hz(0.2), tuned.publishRate(Subjects.SPEED_OVER_GROUND_KNOTS))
        assertEquals(SensorRate.Hz(0.2), tuned.publishRate(Subjects.COURSE_OVER_GROUND_DEG))
        assertEquals(SensorRate.Hz(0.2), tuned.publishRate(Subjects.MAGNETIC_VARIATION_DEG))
    }

    /** Slower than the owner is the point of the whole thing: a declination does not need 1 Hz. */
    @Test
    fun `a derived subject may be published slower than the one it rides`() {
        val tuned = settings.copy(
            sensorRates = mapOf(
                Subjects.LOCATION_FIX to SensorRate.Hz(1.0),
                Subjects.MAGNETIC_VARIATION_DEG to SensorRate.Hz(0.05),
            ),
        )

        assertEquals(SensorRate.Hz(0.05), tuned.publishRate(Subjects.MAGNETIC_VARIATION_DEG))
        // ...and it does not drag the rest of the group down with it.
        assertEquals(SensorRate.Hz(1.0), tuned.publishRate(Subjects.LOCATION_FIX))
        assertEquals(SensorRate.Hz(1.0), tuned.publishRate(Subjects.SPEED_OVER_GROUND_KNOTS))
    }

    /**
     * Faster is refused, and the ceiling is the owner's **publish** rate rather than its record rate.
     *
     * The samples are physically there — the listener runs at the owner's record rate — so this is a
     * policy cap, chosen so that an owner's row reads as the ceiling for everything under it.
     */
    @Test
    fun `a derived subject cannot be published faster than the one it rides`() {
        val tuned = settings.copy(
            recordRates = mapOf(Subjects.LOCATION_FIX to SensorRate.Max),
            sensorRates = mapOf(
                Subjects.LOCATION_FIX to SensorRate.Hz(1.0),
                Subjects.COURSE_OVER_GROUND_DEG to SensorRate.Hz(10.0),
            ),
        )

        assertEquals(SensorRate.Hz(1.0), tuned.publishCeiling(Subjects.COURSE_OVER_GROUND_DEG))
        assertEquals(SensorRate.Hz(1.0), tuned.publishRate(Subjects.COURSE_OVER_GROUND_DEG))
        // The head of the chain is capped by physics instead, which here is not a cap at all.
        assertEquals(SensorRate.Max, tuned.publishCeiling(Subjects.LOCATION_FIX))
    }

    /**
     * **The clamp must not eat the stored value.** An owner lowered for one trial and raised again has
     * to bring the group's tuning back with it — the same property `recordAllMax` has, and the reason
     * the clamp happens on read rather than on save.
     */
    @Test
    fun `lowering an owner clamps a derived subject without erasing it`() {
        val tuned = settings.copy(
            recordRates = mapOf(Subjects.LOCATION_FIX to SensorRate.Max),
            sensorRates = mapOf(
                Subjects.LOCATION_FIX to SensorRate.Hz(0.5),
                Subjects.COURSE_OVER_GROUND_DEG to SensorRate.Hz(5.0),
            ),
        )
        assertEquals(SensorRate.Hz(0.5), tuned.publishRate(Subjects.COURSE_OVER_GROUND_DEG))
        // The request is still 5 Hz underneath, which is what an editor has to show.
        assertEquals(SensorRate.Hz(5.0), tuned.requestedPublishRate(Subjects.COURSE_OVER_GROUND_DEG))

        val raised = tuned.copy(
            sensorRates = tuned.sensorRates + (Subjects.LOCATION_FIX to SensorRate.Hz(10.0)),
        )
        assertEquals(SensorRate.Hz(5.0), raised.publishRate(Subjects.COURSE_OVER_GROUND_DEG))
    }

    /** The mode still wins over the maps, on both sides of a derived subject. */
    @Test
    fun `full rate overrides a derived subject's own limit`() {
        val tuned = settings.copy(
            sensorRates = mapOf(
                Subjects.LOCATION_FIX to SensorRate.Hz(1.0),
                Subjects.COURSE_OVER_GROUND_DEG to SensorRate.Hz(0.1),
            ),
            recordAllMax = true,
            publishAllMax = true,
        )

        assertEquals(SensorRate.Max, tuned.publishRate(Subjects.COURSE_OVER_GROUND_DEG))
        // ...and the limit is still there when the mode goes off again.
        assertEquals(
            SensorRate.Hz(0.1),
            tuned.copy(publishAllMax = false).publishRate(Subjects.COURSE_OVER_GROUND_DEG),
        )
    }

    /**
     * The request and the result are different questions, and the editor asks the first one.
     *
     * A field bound to `publishRate` cannot be typed into: 10 against a 1 Hz ceiling redraws as 1.0, and
     * saving then stores the clamp — so looking at the screen destroys the request.
     */
    @Test
    fun `the requested rate is the raw one, before any clamp`() {
        val tuned = settings.copy(
            recordRates = mapOf(Subjects.AIR_PRESSURE_PA to SensorRate.Hz(1.0)),
            sensorRates = mapOf(Subjects.AIR_PRESSURE_PA to SensorRate.Hz(10.0)),
        )

        assertEquals(SensorRate.Hz(10.0), tuned.requestedPublishRate(Subjects.AIR_PRESSURE_PA))
        assertEquals(SensorRate.Hz(1.0), tuned.publishRate(Subjects.AIR_PRESSURE_PA))
    }

    /**
     * Not an error: no sample exists to send faster than it is sampled, and it is a combination nobody
     * can see is impossible, so it is clamped rather than rejected.
     */
    @Test
    fun `publishing cannot outrun recording`() {
        val tuned = settings.copy(
            recordRates = mapOf(Subjects.AIR_PRESSURE_PA to SensorRate.Hz(1.0)),
            sensorRates = mapOf(Subjects.AIR_PRESSURE_PA to SensorRate.Hz(10.0)),
        )
        assertEquals(SensorRate.Hz(1.0), tuned.publishRate(Subjects.AIR_PRESSURE_PA))
    }

    /** The switches are a mode over the maps. Flipping back must return the tuned profile intact. */
    @Test
    fun `the full-rate switches override the tuning without erasing it`() {
        val tuned = settings.copy(
            recordRates = mapOf(Subjects.ANGULAR_VELOCITY_RADPS to SensorRate.Hz(10.0)),
            sensorRates = mapOf(Subjects.ANGULAR_VELOCITY_RADPS to SensorRate.Hz(2.0)),
        )
        assertEquals(SensorRate.Hz(10.0), tuned.recordRate(Subjects.ANGULAR_VELOCITY_RADPS))
        assertEquals(SensorRate.Hz(2.0), tuned.publishRate(Subjects.ANGULAR_VELOCITY_RADPS))

        val full = tuned.copy(recordAllMax = true, publishAllMax = true)
        assertEquals(SensorRate.Max, full.recordRate(Subjects.ANGULAR_VELOCITY_RADPS))
        assertEquals(SensorRate.Max, full.publishRate(Subjects.ANGULAR_VELOCITY_RADPS))

        // ...and the tuned values are still there underneath.
        assertEquals(SensorRate.Hz(10.0), full.copy(recordAllMax = false).recordRate(Subjects.ANGULAR_VELOCITY_RADPS))
        assertEquals(SensorRate.Hz(2.0), full.copy(publishAllMax = false).publishRate(Subjects.ANGULAR_VELOCITY_RADPS))
    }

    /** `rate()` is what the old single-rate callers used; it must mean the wire now. */
    @Test
    fun `the legacy accessor is the publish rate`() {
        assertEquals(
            settings.publishRate(Subjects.AIR_PRESSURE_PA),
            settings.rate(Subjects.AIR_PRESSURE_PA),
        )
    }
}
