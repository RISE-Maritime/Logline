package se.rise.logline.keelson

/**
 * Wire names, transcribed from `messages/subjects.yaml` upstream.
 *
 * A subject only means something if a consumer agrees on its name *and* its payload type, so these are
 * never invented here — see [PublishedSubject] for which ones this app actually publishes.
 *
 * Note the units are keelson's, not Android's: gauss not microtesla, pascals not hectopascals, knots
 * not metres per second, volts not millivolts. Every one of those needs a conversion on the way out
 * (`sensors/Units.kt`).
 */
object Subjects {
    const val LOCATION_FIX = "location_fix"
    const val SPEED_OVER_GROUND_KNOTS = "speed_over_ground_knots"
    const val COURSE_OVER_GROUND_DEG = "course_over_ground_deg"
    const val LINEAR_ACCELERATION_MPSS = "linear_acceleration_mpss"
    const val ANGULAR_VELOCITY_RADPS = "angular_velocity_radps"
    const val ORIENTATION_QUATERNION = "orientation_quaternion"
    const val MAGNETIC_FIELD_GAUSS = "magnetic_field_gauss"

    // The compass. Heading is where the phone points; `course_over_ground_deg` above is where it is
    // travelling, and on the water those are different numbers — which is the reason to log both.
    const val HEADING_MAGNETIC_DEG = "heading_magnetic_deg"
    const val HEADING_TRUE_NORTH_DEG = "heading_true_north_deg"
    const val HEADING_ACCURACY_DEG = "heading_accuracy_deg"
    const val MAGNETIC_VARIATION_DEG = "magnetic_variation_deg"
    const val RAW_NMEA0183 = "raw_nmea0183"
    const val LOCATION_FIX_SATELLITES_USED = "location_fix_satellites_used"
    const val LOCATION_FIX_SATELLITES_VISIBLE = "location_fix_satellites_visible"
    const val LOCATION_FIX_QUALITY = "location_fix_quality"
    const val LOCATION_FIX_ACCURACY_HORIZONTAL_M = "location_fix_accuracy_horizontal_m"
    const val LOCATION_FIX_ACCURACY_VERTICAL_M = "location_fix_accuracy_vertical_m"
    const val ALTITUDE_ABOVE_MSL_M = "altitude_above_msl_m"
    const val LOCATION_FIX_UNDULATION_M = "location_fix_undulation_m"
    const val ROLL_DEG = "roll_deg"
    const val PITCH_DEG = "pitch_deg"
    const val YAW_DEG = "yaw_deg"
    const val ROLL_RATE_DEGPS = "roll_rate_degps"
    const val PITCH_RATE_DEGPS = "pitch_rate_degps"
    const val YAW_RATE_DEGPS = "yaw_rate_degps"
    const val DEVICE_UPTIME_DURATION = "device_uptime_duration"
    const val IMU_TEMPERATURE_CELSIUS = "imu_temperature_celsius"
    const val AIR_PRESSURE_PA = "air_pressure_pa"
    const val ILLUMINANCE_LUX = "illuminance_lux"

    // Sound. Upstream types this as `keelson.Audio`, whose encodings are MP3 and WAV only.
    const val AUDIO = "audio"

    // Camera frames. `foxglove.CompressedImage` carries the bytes plus a media-type string, so the
    // encoding travels with the frame; upstream's `image_raw` is the uncompressed sibling and is not
    // what a phone should be putting on a bus.
    const val IMAGE_COMPRESSED = "image_compressed"

    // Continuous video, as an H.264 bitstream rather than a stream of stills. One message per access
    // unit; `foxglove.CompressedVideo` requires **Annex B** framing and a SPS NAL alongside every
    // keyframe, which is what `sensors/VideoEncoder.kt` exists to guarantee.
    const val VIDEO_COMPRESSED = "video_compressed"

    // Operator annotations. `foxglove.Log` is the one upstream payload carrying free text with a
    // severity, and Foxglove's Log panel reads it natively — filtering on `level` and on `name`, which
    // is why a mark's category goes in `name` rather than being spelled into the message.
    const val LOG_MESSAGE = "log_message"

    // Platform calibration. `frame_transform` carries one sensor's pose relative to the platform's reference
    // frame, and `configuration_json` the whole geometry document — the same pair, and the same
    // document shape, that keelson's own `connectors/platform` publishes. See `docs/calibration.md`.
    const val FRAME_TRANSFORM = "frame_transform"
    const val CONFIGURATION_JSON = "configuration_json"
    const val BATTERY_STATE_OF_CHARGE_PCT = "battery_state_of_charge_pct"
    const val BATTERY_VOLTAGE_V = "battery_voltage_v"
    const val BATTERY_CURRENT_A = "battery_current_a"
    const val BATTERY_TEMPERATURE_CELSIUS = "battery_temperature_celsius"
    const val BATTERY_IS_CHARGING = "battery_is_charging"

    // Radio link quality. Upstream models these per *link*, distinguished by source_id rather than by
    // separate subject names — so `radio_rssi_dbm` is published once for cellular and once for wifi.
    // Note the legacy unitless `radio_rssi` is a different, older subject; do not use it.
    const val RADIO_RSRP_DBM = "radio_rsrp_dbm"
    const val RADIO_RSRQ_DB = "radio_rsrq_db"
    const val RADIO_SINR_DB = "radio_sinr_db"
    const val RADIO_RSSI_DBM = "radio_rssi_dbm"
    const val RADIO_ACCESS_TECHNOLOGY = "radio_access_technology"
    const val RADIO_DOWNLINK_BITRATE_BPS = "radio_downlink_bitrate_bps"
    const val RADIO_UPLINK_BITRATE_BPS = "radio_uplink_bitrate_bps"

    // Serving cell identity. Upstream's note is the reason these exist: "A change in any of these means
    // the link handed over, and measurements either side of it are not comparable."
    const val RADIO_CELL_ID = "radio_cell_id"
    const val RADIO_PHYSICAL_CELL_ID = "radio_physical_cell_id"
    const val RADIO_EARFCN = "radio_earfcn"
    const val RADIO_BAND = "radio_band"

    // How much spectrum the serving cell has, which is what makes an RSRP reading interpretable.
    // Note the *uplink* counterpart exists upstream and is deliberately not published here: Android
    // exposes it only through `PhysicalChannelConfig`, which needs `READ_PRECISE_PHONE_STATE` — a
    // `signature|privileged` permission no ordinary app can hold. See `RadioProvider`.
    const val RADIO_DOWNLINK_BANDWIDTH_MHZ = "radio_downlink_bandwidth_mhz"

    // Collaborative checklists. Deliberately **not** in `PublishedSubject`: that registry is the
    // *sensor* publishing set, and every derived thing — sampling rates, `SensorManager` types, MCAP
    // channels, live-view rings, the main screen's rows, the per-subject switches — reads it. A
    // checklist subject there would grow a phantom sensor card with a rate control for a thing a
    // person taps. These four are published and subscribed by `checklist/ChecklistSync`, which owns
    // its own session and its own lifetime.
    const val CHECKLIST_EVENT = "checklist_event"
    const val CHECKLIST_STATE = "checklist_state"
    const val CHECKLIST_PRESENCE = "checklist_presence"
    const val CHECKLIST_PROCEDURE = "checklist_procedure"
}

/** The fixed source ids for the radio links, which are hardware facts rather than user settings. */
object RadioSources {
    const val CELLULAR = "cellular"
    const val WIFI = "wifi"
}

/**
 * The level *beneath* `locationSource` that each unfused position solution publishes on.
 *
 * `.../location_fix/{locationSource}` is the fused fix; `.../location_fix/{locationSource}/gnss` and
 * `.../location_fix/{locationSource}/network` are the solutions it is made from. The specification
 * allows the extra level — "`source_id` may contain any number of additional levels (i.e. forward
 * slashes), ei. camera/rbg/0" — and upstream's own parser returns `phone/gnss` as one `source_id`.
 *
 * Unlike [RadioSources] these are **not** replacements for the configured id. A fixed `gnss` would
 * collide with a phone whose `locationSource` was itself set to `gnss`, publishing two different
 * solutions on one key; nesting makes that impossible whatever is typed into a free-text field.
 *
 * There is no `wifi` and no `cell` here, and there cannot be: Android exposes `gps`, `network`,
 * `fused` and `passive`, and `network` is wifi *and* cell together with nothing saying which
 * contributed. Verified against a Pixel 6's `dumpsys location`. Separating them would mean doing the
 * geolocation ourselves against a database.
 */
object FixSources {
    /** `LocationManager.GPS_PROVIDER` — the satellites alone. */
    const val GNSS = "gnss"

    /** `LocationManager.NETWORK_PROVIDER` — wifi and cell, inseparably. */
    const val NETWORK = "network"
}

fun pubsubKey(realm: String, entityId: String, subject: String, sourceId: String): String =
    "$realm/@v0/$entityId/pubsub/$subject/$sourceId"

/**
 * Key for a source's liveliness token, per the Keelson protocol specification §5.1.
 *
 * The `*` is literal, not a placeholder, and it sits in the **category** slot — where `pubsub` or
 * `@rpc` would be — because source-level presence is category-agnostic. That position is load-bearing:
 * §5.5 classifies a received token by inspecting the chunk after the entity, so moving this one chunk
 * along produces a token indistinguishable from [legacyLivelinessKey] and silently mis-tiered.
 *
 * One token per producing *identity* — the `(entity_id, source_id)` pair — declaring that the producer
 * is present, and nothing about what it publishes. Which subjects it claims is the separate
 * subject-level tier: those tokens are the publisher keys themselves, so there is no function for them
 * here (see `SensorPublisher.declareLiveliness`).
 */
fun sourceLivelinessKey(realm: String, entityId: String, sourceId: String): String =
    "$realm/@v0/$entityId/*/$sourceId"

/**
 * The coarse token this app declared before the three-tier structure — specification §5.7.
 *
 * Still declared alongside [sourceLivelinessKey], and deliberately: §5.7 asks aggregators to subscribe
 * to both shapes during the transition window, so an aggregator that has not migrated needs this one
 * to see the phone at all. It costs one token per source.
 *
 * **Delete it once the consumers of operational interest read the three-tier tokens** — for this fleet
 * that means keelson's `entity_health` connector (which already classifies both) and crowsnest. Note
 * what upstream does with it in the meantime: `entity_health2keelson.py` counts a `*` subject chunk as
 * *presence but not advertisement*, so this token alone leaves every subject `NOT_ADVERTISED`. It is a
 * fallback, not a substitute.
 */
fun legacyLivelinessKey(realm: String, entityId: String, sourceId: String): String =
    "$realm/@v0/$entityId/pubsub/*/$sourceId"

/**
 * Key for a request/reply procedure, per the Keelson protocol specification §3.1:
 * `{realm}/@v0/{entity_id}/@rpc/{interface}/{version}/{procedure}/{responder_id}`.
 *
 * Note that a wildcard never crosses `@rpc` any more than it crosses `@v0`, so RPC discovery needs a
 * selector that spells both out. Nothing in this app discovers RPCs, but the same rule is why the
 * chunk positions are pinned by `KeysTest` rather than spelled inline at the call site.
 */
fun rpcKey(
    realm: String,
    entityId: String,
    interfaceName: String,
    version: String,
    procedure: String,
    responderId: String,
): String = "$realm/@v0/$entityId/@rpc/$interfaceName/$version/$procedure/$responderId"

/**
 * Key for an RPC interface's liveliness token, per the protocol specification §3.5 and §5.3.
 *
 * One token per `(interface, version)` a source serves, and the `*` is literal — "any procedure in
 * this scope". Note this is the **third** distinct wildcard position in the three liveliness tiers:
 * the category slot for [sourceLivelinessKey], the subject slot for [legacyLivelinessKey], the
 * *procedure* slot here. They are three different facts and a key that puts the wildcard one chunk
 * out is a different, wrong claim rather than an error.
 *
 * Because no wildcard crosses `@rpc` any more than it crosses `@v0`, this token is invisible to every
 * pattern that finds the other two — a discovery client needs a second subscription spelling `@rpc`
 * out. That is the specification's own note, and the reason a consumer seeing no RPC on this phone
 * has usually asked the wrong question.
 *
 * **Declaring this is a commitment.** §3.6's full-interface rule says a source holding the token must
 * answer *every* procedure in the interface — with a typed refusal where it cannot comply, but never
 * with silence. See `PlatformSync` for what that means for `configurable/v1`.
 */
fun rpcInterfaceLivelinessKey(
    realm: String,
    entityId: String,
    interfaceName: String,
    version: String,
    sourceId: String,
): String = "$realm/@v0/$entityId/@rpc/$interfaceName/$version/*/$sourceId"

/**
 * The `{entity_id}` chunk of a keelson key, or null when the key is not one.
 *
 * Split here rather than at the call site for the same reason the builders live here: the position is
 * protocol, not formatting, and a discovery path that guessed it would report entity ids that are
 * really subjects. `@v0` is verbatim, so a key not carrying it is not a keelson key at all.
 */
/**
 * A pubsub key's subject and source, or null when it is not one.
 *
 * **The source is everything after the subject, not the last chunk.** A `source_id` may carry further
 * levels — the specification's own example is `camera/rbg/0`, and this app publishes
 * `location_fix/{locationSource}/gnss` — so anything reading the source as `chunks.last()` and the
 * subject as the one before it silently answers `gnss` for the subject the moment a source is nested.
 * That is how two screens came to disagree about what a topic was called.
 *
 * Anchored on the verbatim `pubsub` chunk rather than counted from either end, which is the only
 * position that cannot move.
 */
fun pubsubSubjectAndSource(key: String): Pair<String, String>? {
    val chunks = key.split('/')
    // realm / @v0 / entity / pubsub / subject / source…
    if (chunks.size < 6 || chunks[1] != "@v0" || chunks[3] != "pubsub") return null
    val subject = chunks[4].takeIf { it.isNotBlank() } ?: return null
    val source = chunks.drop(5).joinToString("/").takeIf { it.isNotBlank() } ?: return null
    return subject to source
}

fun entityIdFromKey(key: String): String? {
    val chunks = key.split('/')
    if (chunks.size < 3 || chunks[1] != "@v0") return null
    return chunks[2].takeIf { it.isNotBlank() && it != "*" && it != "**" }
}
