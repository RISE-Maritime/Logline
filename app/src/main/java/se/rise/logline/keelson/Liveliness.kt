package se.rise.logline.keelson

/**
 * Which subjects this phone currently *claims* to publish — protocol specification §5.2.
 *
 * The subject-level token's key is byte-identical to the key it advertises, so this takes the publisher
 * key maps `SensorPublisher` has already built rather than reconstructing them. A key that is
 * *constructed* the same way can drift from the publisher's; one that **is** the publisher's cannot,
 * and a token advertising a key nothing publishes on is worse than no token at all.
 *
 * Pure, and separate from the session for that reason: what belongs in the set is the part worth a test.
 *
 * Three exclusions, and the reasoning for each is §5.2's distinction between *capability* and
 * *activity*:
 *
 * - **Switched off** — excluded. A switch is a configuration change, not silence: the subject is not
 *   one this phone is currently wired to publish, and a health monitor should see it withdrawn rather
 *   than reported as a source that has gone quiet.
 * - **Absent hardware** — excluded, from [se.rise.logline.sensors.unavailableSubjects]. There is no
 *   capability to declare.
 * - **Silent but wired** — *included*, which is the case the spec is most explicit about. The token
 *   "MUST NOT be retracted because data is momentarily absent", so `heading_true_north_deg` holds one
 *   while it waits for the first fix, and `log_message` holds one through a run nobody annotates.
 *
 * @param keyMaps the phone's key map, then one per publishing rig — a rig's three calibration subjects
 *   publish under the *rig's* entity id, so the same registry entry appears once per rig with a
 *   different key, and all of them are claimed.
 */
fun subjectLivelinessKeys(
    keyMaps: List<Map<PublishedSubject, String>>,
    off: Set<PublishedSubject>,
    unavailable: Set<PublishedSubject>,
): Set<String> = keyMaps
    .flatMap { keys -> keys.entries }
    .filter { (entry, _) -> entry !in off && entry !in unavailable }
    .mapTo(mutableSetOf()) { (_, key) -> key }
