package se.rise.logline.sensors

/** Roll, pitch and yaw rates in degrees per second. */
data class AttitudeRatesDeg(val roll: Float, val pitch: Float, val yaw: Float)

/**
 * The gyro's three axes, named and converted.
 *
 * The naming is Android's own, the same mapping `getOrientation` uses for the angles: **pitch turns
 * about +X, roll about +Y, yaw about +Z**. Split out as a function rather than written inline at the
 * publish site precisely because it is three assignments that would all look right if two of them were
 * swapped — a phone rolling at 12°/s and a phone pitching at 12°/s produce the same number under the
 * wrong name, and nothing downstream could tell.
 *
 * These are **body rates**, which is what the gyro measures and what a marine system means by "roll
 * rate". They are not the derivatives of `roll_deg`, `pitch_deg` and `yaw_deg`; Euler rates and body
 * rates agree only near level and diverge exactly where the motion is interesting.
 */
fun attitudeRatesOf(gyroX: Float, gyroY: Float, gyroZ: Float): AttitudeRatesDeg = AttitudeRatesDeg(
    roll = radiansToDegrees(gyroY),
    pitch = radiansToDegrees(gyroX),
    yaw = radiansToDegrees(gyroZ),
)
