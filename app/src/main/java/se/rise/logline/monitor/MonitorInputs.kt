package se.rise.logline.monitor

/**
 * One thing a card reads: which subject, a pinned source if any, and a slot if the card is per-unit.
 *
 * Declared per kind here rather than inside each composable, so the screen can ask every card what it
 * needs before drawing any of them — which is what lets [MonitorStore.snapshot] copy only the windows
 * something will actually plot.
 */
data class CardInput(val role: String, val subject: String, val source: String = "", val slot: Int? = null)

/** What this card reads, in its own terms. Empty subjects (an unused plot series) are left out. */
fun MonitorCard.inputs(): List<CardInput> {
    val slot = string("slot").toIntOrNull()
    val pinned = string("source")
    return when (kind) {
        CardKind.Plot -> listOf(
            CardInput("s1", string("subject"), pinned),
            CardInput("s2", string("subject2"), string("source2")),
            CardInput("s3", string("subject3"), string("source3")),
        )
        CardKind.Value -> listOf(CardInput("value", string("subject"), pinned))
        CardKind.Heading -> listOf(
            CardInput("heading", HEADING, pinned),
            CardInput("cog", COG),
            CardInput("rot", ROT),
            CardInput("yaw", YAW),
        )
        CardKind.Rudder -> listOf(CardInput("rudder", "rudder_angle_deg", slot = slot))
        CardKind.Engine -> listOf(
            CardInput("rpm", "engine_rate_rpm", slot = slot),
            CardInput("pitch", "propeller_pitch_pct", slot = slot),
        )
        CardKind.BowThruster -> listOf(CardInput("power", string("subject"), slot = slot))
        CardKind.Wind -> listOf(
            CardInput("angle", "apparent_wind_angle_deg"),
            CardInput("speed", "apparent_wind_speed_mps"),
        )
        CardKind.PitchRoll -> listOf(CardInput("pitch", "pitch_deg"), CardInput("roll", "roll_deg"))
        CardKind.ShipVelocity -> listOf(
            CardInput("heading", HEADING),
            CardInput("cog", COG),
            CardInput("sog", SOG),
            CardInput("sway", "sway_velocity_mps"),
            CardInput("rot", ROT),
        )
        CardKind.Imu -> listOf(
            CardInput("accel", string("accel"), string("accelSource")),
            CardInput("gyro", string("gyro"), string("gyroSource")),
        )
        CardKind.NavMap -> listOf(
            CardInput("position", string("position"), string("positionSource")),
            CardInput("heading", HEADING),
            CardInput("cog", COG),
            CardInput("sog", SOG),
        )
    }.filter { it.subject.isNotEmpty() }
}

internal const val HEADING = "heading_true_north_deg"
internal const val COG = "course_over_ground_deg"
internal const val SOG = "speed_over_ground_knots"
internal const val ROT = "yaw_rate_degps"
internal const val YAW = "yaw_deg"

/** The topic an input resolves to right now, given what the entity has published so far. */
fun MonitorSnapshot.resolve(input: CardInput): Topic? =
    resolveTopic(topics.map { it.topic }, input.subject, input.source, input.slot)

/** Every topic any of these cards will read — the `wanted` set for [MonitorStore.snapshot]. */
fun wantedTopics(cards: List<MonitorCard>, available: List<Topic>): Set<Topic> =
    cards.flatMap { it.inputs() }
        .mapNotNull { resolveTopic(available, it.subject, it.source, it.slot) }
        .toSet()

/** Whether upstream declares this subject at all — a card pointed at one it does not says so. */
fun isKnownSubject(subject: String): Boolean = subject in remoteSubjectTypes
