package se.rise.logline.monitor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * One tunable setting on a card.
 *
 * Cards declare their settings as data rather than as a screen each, so one settings sheet renders
 * every card and adding a card kind never means writing a form. Values are stored as strings in
 * [MonitorCard.params]; an absent or unparseable value falls back to [default], so a card saved by an
 * older build — or edited by hand in a profile — still opens.
 */
sealed interface ParamSpec {
    val key: String
    val label: String

    /** A keelson subject on the watched entity. [shapes] filters the picker; the default is the subject used when unset. */
    data class Subject(
        override val key: String,
        override val label: String,
        val default: String,
        val shapes: Set<PayloadShape> = setOf(PayloadShape.Scalar),
        /** Optional: an empty value means "not shown" rather than "use the default". */
        val optional: Boolean = false,
    ) : ParamSpec

    /**
     * Which source of [subjectKey]'s subject to read. Empty is **auto** — the first source seen, or
     * the one matching the card's slot — which is Foxglove's `resolveInput` rule.
     */
    data class Source(
        override val key: String,
        override val label: String,
        /** The card setting naming the subject, where the subject is itself a setting. */
        val subjectKey: String? = null,
        /** The subject, where the card reads a fixed one. */
        val subject: String? = null,
    ) : ParamSpec

    data class Number(
        override val key: String,
        override val label: String,
        val default: Double?,
        val min: Double,
        val max: Double,
        val unit: String = "",
    ) : ParamSpec

    data class Choice(
        override val key: String,
        override val label: String,
        val default: String,
        val options: List<Pair<String, String>>,
    ) : ParamSpec

    data class Toggle(override val key: String, override val label: String, val default: Boolean) : ParamSpec
}

private fun stale() = ParamSpec.Number("staleAfterS", "Stale after", 5.0, 0.5, 600.0, "s")

/** Slot 0–3, or auto. Read from the last chunk of a source id — see [slotFromSourceId]. */
private fun slot() = ParamSpec.Choice(
    "slot", "Unit",
    "",
    listOf("" to "First found", "0" to "0 · port", "1" to "1 · starboard", "2" to "2", "3" to "3"),
)

private fun source(forSubjectKey: String = "subject") = ParamSpec.Source("source", "Source", subjectKey = forSubjectKey)

/**
 * Every kind of card, and the settings each one takes.
 *
 * The ported ones take their defaults from the matching panel in `foxglove-custom-panels`, so a card
 * and the Foxglove panel it came from read the same data the same way out of the box.
 * **New kinds are appended**, for the reason `MapLayer` gives: the add-card list keeps its order.
 */
enum class CardKind(val label: String, val blurb: String, val specs: List<ParamSpec>) {
    Plot(
        "Plot", "Up to three numeric subjects over time",
        listOf(
            ParamSpec.Subject("subject", "Series 1", "speed_over_ground_knots"),
            source("subject"),
            ParamSpec.Subject("subject2", "Series 2", "", optional = true),
            ParamSpec.Source("source2", "Source 2", subjectKey = "subject2"),
            ParamSpec.Subject("subject3", "Series 3", "", optional = true),
            ParamSpec.Source("source3", "Source 3", subjectKey = "subject3"),
            ParamSpec.Number("windowS", "Window", 60.0, 10.0, 3600.0, "s"),
            ParamSpec.Number("yMin", "Y minimum (blank = auto)", null, -1e9, 1e9),
            ParamSpec.Number("yMax", "Y maximum (blank = auto)", null, -1e9, 1e9),
            stale(),
        ),
    ),
    Value(
        "Value", "One reading, large, with its unit",
        listOf(
            ParamSpec.Subject("subject", "Subject", "speed_over_ground_knots"),
            source(),
            ParamSpec.Number("decimals", "Decimals", 1.0, 0.0, 6.0),
            stale(),
        ),
    ),
    Heading(
        "Heading", "Heading, course and rate of turn",
        listOf(
            ParamSpec.Source("source", "Source", subject = "heading_true_north_deg"),
            ParamSpec.Choice("centre", "Up", "heading", listOf("heading" to "Heading up", "course" to "Course up")),
            ParamSpec.Number("angleSmoothingS", "Angle smoothing", 0.0, 0.0, 30.0, "s"),
            ParamSpec.Number("rotSmoothingS", "ROT smoothing", 3.0, 0.0, 60.0, "s"),
            stale(),
        ),
    ),
    Rudder(
        "Rudder", "Rudder angle",
        listOf(slot(), ParamSpec.Number("maxAngle", "Max angle", 60.0, 10.0, 70.0, "°"), stale()),
    ),
    Engine(
        "Engine", "Engine RPM and propeller pitch",
        listOf(
            slot(),
            ParamSpec.Choice(
                "barSource", "Bar shows", "auto",
                listOf("auto" to "Auto", "pitch" to "Pitch", "rpm" to "RPM"),
            ),
            ParamSpec.Number("maxRpm", "Max RPM", 2000.0, 100.0, 20000.0),
            stale(),
        ),
    ),
    /**
     * The Foxglove panel reads `thruster_power_pct`, which is **not in keelson's `subjects.yaml`** —
     * checked at `0.6.0-pre.18` — so nothing a conforming publisher sends will ever match it. The
     * subject is a setting here rather than a constant for that reason; the card says so when it is
     * pointed at a name upstream does not declare.
     */
    BowThruster(
        "Bow thruster", "Thruster power, port to starboard",
        listOf(ParamSpec.Subject("subject", "Subject", "thruster_power_pct"), slot(), stale()),
    ),
    Wind(
        "Wind", "Apparent wind angle and speed",
        listOf(ParamSpec.Number("trailS", "Trail", 60.0, 0.0, 600.0, "s"), stale()),
    ),
    PitchRoll(
        "Pitch & roll", "Attitude with its range over a window",
        listOf(
            ParamSpec.Number("windowS", "Window", 60.0, 5.0, 3600.0, "s"),
            ParamSpec.Number("maxPitchAdvice", "Pitch advice", 25.0, 1.0, 90.0, "°"),
            ParamSpec.Number("maxRollAdvice", "Roll advice", 30.0, 1.0, 90.0, "°"),
            stale(),
        ),
    ),
    ShipVelocity(
        "Ship velocity", "Speed, drift and bow/stern sway",
        listOf(
            ParamSpec.Number("loa", "Length overall", 100.0, 1.0, 500.0, "m"),
            ParamSpec.Number("predMinutes", "Prediction", 0.75, 0.25, 10.0, "min"),
            stale(),
        ),
    ),
    Imu(
        "IMU", "Acceleration and rotation per axis, G-G diagram",
        listOf(
            ParamSpec.Subject("accel", "Acceleration", "linear_acceleration_mpss", setOf(PayloadShape.Vector)),
            ParamSpec.Source("accelSource", "Acceleration source", subjectKey = "accel"),
            ParamSpec.Subject("gyro", "Rotation", "angular_velocity_radps", setOf(PayloadShape.Vector)),
            ParamSpec.Source("gyroSource", "Rotation source", subjectKey = "gyro"),
            ParamSpec.Choice(
                "gravityMode", "Gravity", "auto",
                listOf("auto" to "Auto", "included" to "Included", "removed" to "Removed"),
            ),
            ParamSpec.Choice(
                "mountPreset", "Mounting", "logline",
                listOf("logline" to "Logline phone (fwd +y)", "frd" to "Forward-right-down (fwd +x)"),
            ),
            ParamSpec.Number("peakMinutes", "Peaks over", 30.0, 1.0, 240.0, "min"),
            ParamSpec.Number("sparklineSeconds", "Sparkline", 30.0, 5.0, 600.0, "s"),
            ParamSpec.Toggle("ggEnabled", "G-G diagram", true),
            stale(),
        ),
    ),
    /**
     * The live tab's own chart (`TrackMap`), so the halo, attribution and layer rules come with it.
     * The Foxglove panel's `vectorMinutes` is deliberately absent: the course vector here is a fixed
     * length on purpose — it says which way, not how far — and a setting it ignored would be a lie.
     */
    NavMap(
        "Chart", "Position, heading and course on a chart",
        listOf(
            ParamSpec.Subject("position", "Position", "location_fix", setOf(PayloadShape.Position)),
            ParamSpec.Source("positionSource", "Position source", subjectKey = "position"),
            ParamSpec.Toggle("follow", "Follow", true),
            ParamSpec.Number("trackMinutes", "Track", 3.0, 0.0, 240.0, "min"),
            stale(),
        ),
    ),
    ;

    fun spec(key: String): ParamSpec? = specs.firstOrNull { it.key == key }
}

/** One card on the Monitor tab: what kind, and whatever settings differ from that kind's defaults. */
data class MonitorCard(
    val id: String,
    val kind: CardKind,
    val params: Map<String, String> = emptyMap(),
) {
    fun string(key: String): String {
        params[key]?.let { return it }
        return when (val spec = kind.spec(key)) {
            is ParamSpec.Subject -> if (spec.optional) "" else spec.default
            is ParamSpec.Choice -> spec.default
            is ParamSpec.Number -> spec.default?.toString().orEmpty()
            is ParamSpec.Toggle -> spec.default.toString()
            is ParamSpec.Source, null -> ""
        }
    }

    /** A number setting, clamped to its declared range; null only where the spec allows blank. */
    fun number(key: String): Double? {
        val spec = kind.spec(key) as? ParamSpec.Number
        val raw = params[key]?.trim()?.toDoubleOrNull() ?: spec?.default ?: return null
        return spec?.let { raw.coerceIn(it.min, it.max) } ?: raw
    }

    fun num(key: String, fallback: Double): Double = number(key) ?: fallback

    fun flag(key: String): Boolean = string(key).toBooleanStrictOrNull() ?: false

    fun with(key: String, value: String?): MonitorCard =
        copy(params = if (value.isNullOrEmpty()) params - key else params + (key to value))

    companion object {
        fun new(kind: CardKind, params: Map<String, String> = emptyMap()) =
            MonitorCard(UUID.randomUUID().toString().take(8), kind, params)
    }
}

/** One card as the JSON stored under one DataStore key and inside a settings profile. */
fun MonitorCard.toJson(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("kind", JsonPrimitive(kind.name))
    put("params", buildJsonObject { params.toSortedMap().forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
}

/** Null for an unknown kind — a card from a newer build is dropped, never shown as something else. */
fun parseMonitorCard(obj: JsonObject): MonitorCard? {
    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val kindName = (obj["kind"] as? JsonPrimitive)?.contentOrNull ?: return null
    val kind = CardKind.entries.firstOrNull { it.name == kindName } ?: return null
    val params = (obj["params"] as? JsonObject)
        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
        ?.toMap()
        .orEmpty()
    return MonitorCard(id, kind, params)
}

fun List<MonitorCard>.toJsonArray(): JsonArray = buildJsonArray { forEach { add(it.toJson()) } }

fun parseMonitorCards(array: JsonArray): List<MonitorCard> =
    array.mapNotNull { (it as? JsonObject)?.let(::parseMonitorCard) }
