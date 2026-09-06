package se.rise.logline.checklist

import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.pubsubKey

/**
 * The checklist key tree, built through [pubsubKey] like every other key in the app.
 *
 * These are transcribed from crowsnest's `src/services/checklistSync.js`, which is the only other
 * implementation on this bus, and `ChecklistKeysTest` pins them against its literals. The layout is
 * the ordinary Keelson one, so nothing here is special-cased; what *is* worth knowing is which of
 * them the router persists:
 *
 * ```
 *   PERSISTED (storage_manager-backed, so a `get` answers)
 *     .../pubsub/checklist_procedure/{procedure_id}   -> keelson.ChecklistProcedure
 *     .../pubsub/checklist_state/{run_id}             -> keelson.ChecklistState
 *     .../pubsub/checklist_evidence/{evidence_id}     -> foxglove.CompressedImage
 *
 *   EPHEMERAL (pubsub only)
 *     .../pubsub/checklist_event/{roc_site}           -> keelson.ChecklistEvent
 *     .../pubsub/checklist_presence/{roc_site}/{operator_id} -> keelson.ChecklistPresence
 * ```
 *
 * That table is protocol-specification.md §7.3, and it is not decoration: `checklist_state` exists
 * **only** so a late joiner can bootstrap, so with no storage behind it a station that joins between
 * two 30 s ticks sees nothing — and the failure is silent, looking exactly like an empty checklist.
 * Two deployment mistakes upstream records as having already cost real debugging time, both silent:
 * a `memory` volume answers a single-key `get` and returns nothing for a wildcard, which is what
 * bootstrap uses; and a storage whose key expression names the wrong realm or entity persists
 * nothing and says so nowhere.
 *
 * The realm and entity are **not** the logger's own `realm`/`entityId`. Those name this phone as a
 * source of sensor data (`rise`/`pixel_6`); a checklist is a shared document that several sites work
 * on at once, and it lives under the operations centre's own entity — `rise`/`roc1` by default,
 * moved there on 2026-08-26 from `crowsnest`/`checklist`, since `crowsnest` is an application rather
 * than a deployment.
 */
class ChecklistKeys(private val realm: String, private val entityId: String) {

    fun event(rocSiteId: String): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_EVENT, rocSiteId)

    /** Every site's events, including this phone's own — the reducer drops its own by id. */
    fun eventSubscription(): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_EVENT, "*")

    /**
     * One run's snapshot. §7.3: **one key per run, forever** — latest wins per run, but the key set
     * only grows, which upstream records as the accepted cost of keying by run rather than by
     * procedure (two runs of one procedure used to overwrite each other).
     */
    fun state(runId: String): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_STATE, requireSingleToken(runId, "run id"))

    /** The bytes of one photograph. §7.3: one key per photo, forever, immutable once written. */
    fun evidence(evidenceId: String): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_EVIDENCE, requireSingleToken(evidenceId, "evidence id"))

    fun evidenceSubscription(): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_EVIDENCE, "*")

    /**
     * Every run's snapshot, as a **subscription**.
     *
     * It was a query, against the router's storage, and that is what crashed the app — a query's reply
     * aborts the process on this binding. It works as a subscription because crowsnest republishes each
     * active run's snapshot periodically rather than only writing it once: measured on the live bus,
     * five concurrent runs each arrived more than once inside twelve seconds. The wildcard is the same
     * either way; only who answers it changes.
     */
    fun stateQuery(): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_STATE, "*")

    /**
     * A run id or an evidence id: **a single token by construction**, and checked rather than
     * trusted.
     *
     * §7.3 names this as one of its silent failures — "a composite id publishes without error and
     * never persists", because a storage's key expression ends in a single-token wildcard, which
     * matches one token, so a key with a slash in it simply never matches and nothing anywhere says
     * so. A crash on this app's own bug beats a key the router quietly declines to keep.
     *
     * (The wildcard is written out in words for the reason the presence KDoc below gives: Kotlin
     * block comments nest, so a literal slash-star in here opens a comment that stops the file
     * parsing somewhere with no apparent connection to it.)
     */
    private fun requireSingleToken(id: String, what: String): String {
        require(id.isNotEmpty()) { "a $what must not be empty" }
        require('/' !in id) { "a $what must be a single token, not \"$id\" — see spec §7.3" }
        return id
    }

    /**
     * Presence carries a **two-token source id**, `{roc_site}/{operator_id}`.
     *
     * That is what crowsnest publishes, and it is legal: the source id is the remainder of the key,
     * not a single chunk. It also means the subscription needs a double star rather than a single
     * one: `*` matches exactly one token, so it would silently match nothing here — the same failure
     * mode as a subscription that stops at the realm and expects a wildcard to cross `@v0`.
     *
     * (Written out in words on purpose. Kotlin block comments nest, so a literal slash-star-star in a
     * KDoc opens a comment that the next close-comment only half closes, and the file stops parsing
     * some way further down with an error that points nowhere near it.)
     */
    fun presence(rocSiteId: String, operatorId: String): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_PRESENCE, "$rocSiteId/$operatorId")

    fun presenceSubscription(): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_PRESENCE, "**")

    fun procedure(procedureId: String): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_PROCEDURE, procedureId)

    /**
     * The whole library — now a subscription, and a *best-effort* one.
     *
     * Unlike the run snapshots, procedures are not republished on a timer: crowsnest `put`s one when
     * somebody edits it, so this catches an edit made while the phone is listening and nothing else.
     * The library the phone actually renders from is its own, persisted by `ChecklistRepository` and
     * seeded from `STARTER_PROCEDURES` with crowsnest's own ids. A run whose procedure is in neither
     * renders by item id, and the screen says why.
     */
    fun procedureQuery(): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_PROCEDURE, "*")
}

/**
 * A fresh id for a run, a note, a flag or a piece of evidence.
 *
 * **A single token by construction** — no slash, so it cannot fall foul of the §7.3 failure where a
 * composite id publishes without error and never persists. The hyphens come out of the UUID for the
 * same reason they go into the prefix: what ends up in a key expression should be one plain word a
 * person can read back off the bus.
 */
fun checklistId(prefix: String): String =
    "${prefix}_${java.util.UUID.randomUUID().toString().replace("-", "")}"
