package se.rise.logline.checklist

import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.pubsubKey

/**
 * The checklist key tree, built through [pubsubKey] like every other key in the app.
 *
 * These are transcribed from crowsnest's `src/services/checklistSync.js`, which is the only other
 * implementation on this bus, and `ChecklistKeysTest` pins them against its literals. The layout is
 * the ordinary Keelson one — realm `crowsnest`, entity `checklist` — so nothing here is special-cased;
 * what *is* worth knowing is which of them the router persists:
 *
 * ```
 *   PERSISTED (storage_manager-backed, so a `get` answers)
 *     .../pubsub/checklist_procedure/{procedure_id}   -> keelson.ChecklistProcedure
 *     .../pubsub/checklist_state/{procedure_id}       -> keelson.ChecklistState
 *
 *   EPHEMERAL (pubsub only)
 *     .../pubsub/checklist_event/{roc_site}           -> keelson.ChecklistEvent
 *     .../pubsub/checklist_presence/{roc_site}/{operator_id} -> keelson.ChecklistPresence
 * ```
 *
 * The realm and entity are **not** the logger's own `realm`/`entityId`. Those name this phone as a
 * source of sensor data (`rise`/`pixel_6`); a checklist is a shared document that several sites work
 * on at once, and it lives where crowsnest already put it.
 */
class ChecklistKeys(private val realm: String, private val entityId: String) {

    fun event(rocSiteId: String): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_EVENT, rocSiteId)

    /** Every site's events, including this phone's own — the reducer drops its own by id. */
    fun eventSubscription(): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_EVENT, "*")

    fun state(procedureId: String): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_STATE, procedureId)

    /** Every procedure's snapshot in one query — the storage answers each key it holds. */
    fun stateQuery(): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_STATE, "*")

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

    /** The whole library, in one query against the router's storage. */
    fun procedureQuery(): String =
        pubsubKey(realm, entityId, Subjects.CHECKLIST_PROCEDURE, "*")
}
