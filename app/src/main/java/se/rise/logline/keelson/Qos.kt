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
 * All six profiles are transcribed even though this app publishes in only four, so the table can be
 * checked against upstream at a glance and a new subject can be assigned without re-deriving it. A
 * profile left out is how the enum quietly stops being a transcription.
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

    /**
     * The one profile that blocks rather than drops. **Nothing in this app publishes in it**, and
     * it is transcribed so the table stays a copy of upstream rather than a subset of it.
     *
     * Upstream's only member is `scenario_tick_ack`: a barrier participant acknowledging a tick to
     * the clock authority, where a lost message is not a degraded reading but a broken guarantee.
     * One per tick per participant, which is what makes back-pressure affordable there and wrong
     * nearly everywhere else.
     *
     * Two things it is **not**, both stated at length in `qos.yaml` because both are easy to read
     * into the name. It stops the *local egress queue* shedding a message; it is not an end-to-end
     * delivery guarantee, since reliability is hop-by-hop with no backfill and a message with no
     * subscriber attached is still gone. And it is not a durability lever — whether a record
     * survives a restart is a router `storage_manager` responsibility.
     *
     * `express` because something is blocked waiting on it: batching would add milliseconds to
     * every tick, multiplied by every blocking participant.
     *
     * If a subject here is ever assigned to it, read [policyQosForSubject]'s warning first — BLOCK
     * stalls the publishing thread, and every publish in this app happens on a sensor collector.
     */
    NO_DROP(Priority.DATA_HIGH, CongestionControl.BLOCK, Reliability.RELIABLE, true),
}

/**
 * The profile upstream policy assigns to a subject, per the `subjects:` table in `qos.yaml`.
 *
 * Only the subjects this app publishes are listed; the other ~60 assignments upstream are noise here.
 * `DEFAULT` for anything else is correct by construction — `qos.yaml` sets `default: default` for
 * every unlisted subject.
 *
 * Of the *sensor* subjects this app publishes, only the navigation ones differ from Zenoh's defaults —
 * `qos.yaml` groups them under `elevated` as "live conning state". Everything else (IMU, magnetometer,
 * barometer, battery) is unlisted upstream and so inherits `default`: shipping the IMU as `TRANSIENT`
 * would make the same subject travel differently from this phone than from any other connector.
 *
 * The checklist subjects are the exception to that shape, and they arrived late: `0.6.0-pre.15`
 * assigned three of the four, where every earlier release left all of them unlisted.
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
    // `video_compressed: transient` upstream too, and the argument is strongest here: a video frame
    // that will not fit down a congested link is superseded by the next one within a tenth of a
    // second, and the keyframe after it re-synchronises the decoder anyway.
    Subjects.AUDIO,
    Subjects.VIDEO_COMPRESSED,
    Subjects.IMAGE_COMPRESSED -> QosProfile.TRANSIENT
    // `log_message: background` upstream — "raw passthrough, logging and audit trails. Correctness
    // over latency, lowest priority so it never crowds out live data." Reliable, so a mark that is
    // worth making is not shed; DATA_LOW, so making one costs the navigation stream nothing.
    // `raw_nmea0183: background` upstream, under the same heading and for the same reason — "raw
    // passthrough, logging and audit trails". Reliable, so a sentence is not shed; DATA_LOW, so a
    // receiver talking at eight messages a second never crowds out the live navigation data.
    Subjects.RAW_NMEA0183,
    Subjects.LOG_MESSAGE -> QosProfile.BACKGROUND
    // The checklist subjects, assigned upstream in `0.6.0-pre.15` and unlisted before it. All four
    // used to fall through to `default`; three of them no longer should.
    //
    // `checklist_event: elevated` — "one operator ticking an item is on another operator's screen.
    // Small, infrequent and latency-sensitive". Deliberately *not* `background` despite being an
    // append-only stream, because an audit trail nobody is waiting on can afford latency and
    // somebody is waiting on this one. Note what the profile does not buy: `elevated` is still
    // `DROP`, and RELIABLE is hop-by-hop rather than an end-to-end ack — what makes a lost event
    // survivable is the `checklist_state` snapshot, not this.
    Subjects.CHECKLIST_EVENT -> QosProfile.ELEVATED
    // `checklist_presence: transient` — not high-rate, but the same stance for the same reason:
    // each beat supersedes the last, so retransmitting a lost one delivers a claim about where an
    // operator's cursor *was*. BEST_EFFORT is the point rather than a concession.
    Subjects.CHECKLIST_PRESENCE -> QosProfile.TRANSIENT
    // `checklist_state: background` — republished every 30 s into a durable store and read by a
    // late joiner as a query against that store, not as a live subscription. Nothing waits on it in
    // real time, so correctness over latency at the lowest priority is exactly right.
    Subjects.CHECKLIST_STATE -> QosProfile.BACKGROUND
    // Two subjects below are `default` **by decision upstream, not by omission**, and both are
    // spelled out in `qos.yaml` precisely so nobody promotes them here.
    //
    // `checklist_procedure` is a template edited at human pace and read out of storage on mount, so
    // it has no stance beyond "must arrive" — the same reason `route` and `voyage` are absent.
    //
    // `checklist_evidence` is the one worth reading twice, because it carries the **same**
    // `foxglove.CompressedImage` as `image_compressed` two branches up, which is `transient`. The
    // payload is the same; the stance is not. A camera frame is one of thirty this second and the
    // next one corrects it, so BEST_EFFORT costs nothing. An evidence photo is a one-shot write of
    // a safety record that nothing will ever republish, so retransmitting a lost fragment on the
    // hops that offer it is worth paying for. Grouping it with the image subjects would be the
    // obvious tidy-up and would silently make a safety photo the cheapest thing on the link.
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
