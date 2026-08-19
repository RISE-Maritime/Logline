package se.rise.logline.keelson

import io.zenoh.qos.CongestionControl
import io.zenoh.qos.Priority
import io.zenoh.qos.Reliability

/**
 * QoS profiles transcribed from `keelson/messages/qos.yaml`.
 *
 * That file is the shared policy: `subjects.yaml` says *what* a subject carries, `qos.yaml` says *how*
 * it should travel, so a subject behaves the same on the wire no matter which connector publishes it.
 * These values are a **copy of upstream**, in the same spirit as the vendored `.proto` files — do not
 * tune them here. If a profile is wrong, it is wrong in the `keelson` repo first.
 *
 * All five profiles are transcribed even though this app only uses two, so the table can be checked
 * against upstream at a glance and a new subject can be assigned without re-deriving it.
 *
 * Note `qos.yaml` spells the top priority `REAL_TIME` while the Zenoh binding spells it `REALTIME`.
 */
enum class QosProfile(
    val priority: Priority,
    val congestionControl: CongestionControl,
    val reliability: Reliability,
    val express: Boolean,
) {
    /** Tele-operation hot path: operator inputs driving the vehicle right now. */
    REALTIME(Priority.REALTIME, CongestionControl.DROP, Reliability.RELIABLE, true),

    /** High-rate frames superseded by the next one — freshness beats completeness. */
    TRANSIENT(Priority.INTERACTIVE_HIGH, CongestionControl.DROP, Reliability.BEST_EFFORT, false),

    /** Live state operators and autopilots act on, plus safety-critical situational data. */
    ELEVATED(Priority.DATA_HIGH, CongestionControl.DROP, Reliability.RELIABLE, false),

    /** Baseline routine telemetry. Identical to Zenoh's own defaults. */
    DEFAULT(Priority.DATA, CongestionControl.DROP, Reliability.RELIABLE, false),

    /** Deferrable: raw passthrough, logging, audit trails. */
    BACKGROUND(Priority.DATA_LOW, CongestionControl.DROP, Reliability.RELIABLE, false),
}

/**
 * The profile upstream policy assigns to a subject, per the `subjects:` table in `qos.yaml`.
 *
 * Only the subjects this app publishes are listed; the other ~60 assignments upstream are noise here.
 * `DEFAULT` for anything else is correct by construction — `qos.yaml` sets `default: default` for
 * every unlisted subject.
 *
 * Of what this app publishes, only the three navigation subjects differ from Zenoh's defaults —
 * `qos.yaml` groups them under `elevated` as "live conning state". Everything else (IMU, magnetometer,
 * barometer, battery) is unlisted upstream and so inherits `default`: shipping the IMU as `TRANSIENT`
 * would make the same subject travel differently from this phone than from any other connector.
 */
fun policyQosForSubject(subject: String): QosProfile = when (subject) {
    Subjects.LOCATION_FIX,
    Subjects.SPEED_OVER_GROUND_KNOTS,
    Subjects.COURSE_OVER_GROUND_DEG,
    // Both headings are listed `elevated` in qos.yaml — upstream treats where a vessel points as
    // navigation data, same as where it is and how fast it is going.
    Subjects.HEADING_MAGNETIC_DEG,
    Subjects.HEADING_TRUE_NORTH_DEG -> QosProfile.ELEVATED
    // `audio: transient` upstream, the same profile the image subjects use: best-effort, so a chunk
    // that will not fit down a congested link is dropped rather than delaying live navigation data.
    // A dropped chunk is a hole in the sound and nothing retransmits it; the local MCAP is the
    // complete copy.
    //
    // `image_compressed` is listed under the same profile, and the comment above about radar spokes and
    // camera frames is upstream's own example of why: high-rate, short-lived, each superseded by the
    // next. A time-lapse frame that will not fit is worth less than the navigation data behind it.
    Subjects.AUDIO,
    Subjects.IMAGE_COMPRESSED -> QosProfile.TRANSIENT
    // `log_message: background` upstream — "raw passthrough, logging and audit trails. Correctness
    // over latency, lowest priority so it never crowds out live data." Reliable, so a mark that is
    // worth making is not shed; DATA_LOW, so making one costs the navigation stream nothing.
    // `raw_nmea0183: background` upstream, under the same heading and for the same reason — "raw
    // passthrough, logging and audit trails". Reliable, so a sentence is not shed; DATA_LOW, so a
    // receiver talking at eight messages a second never crowds out the live navigation data.
    Subjects.RAW_NMEA0183,
    Subjects.LOG_MESSAGE -> QosProfile.BACKGROUND
    else -> QosProfile.DEFAULT
}

/**
 * The four transport settings actually applied to a publisher.
 *
 * A [QosProfile] is one named combination of these; a user override is any combination at all, which
 * is why this is a separate type rather than the enum.
 */
data class SubjectQos(
    val priority: Priority,
    val congestionControl: CongestionControl,
    val reliability: Reliability,
    val express: Boolean,
)

fun QosProfile.toSubjectQos() = SubjectQos(priority, congestionControl, reliability, express)

/** The named profile whose settings match, or null for a combination `qos.yaml` does not define. */
fun SubjectQos.matchingProfile(): QosProfile? =
    QosProfile.entries.firstOrNull { it.toSubjectQos() == this }

/**
 * What a subject is actually published with: a per-subject override if the user set one, else the
 * upstream policy.
 *
 * `qos.yaml` allows overriding — *"A connector may still override per-publisher"* — but an override is
 * a local divergence from a policy whose whole point is cross-connector consistency, so the default is
 * always to follow upstream and overrides are opt-in per subject.
 */
fun qosForSubject(
    subject: String,
    overrides: Map<String, SubjectQos> = emptyMap(),
): SubjectQos = overrides[subject] ?: policyQosForSubject(subject).toSubjectQos()
