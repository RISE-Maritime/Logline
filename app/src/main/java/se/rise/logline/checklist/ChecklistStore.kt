package se.rise.logline.checklist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Whether checklist actions are reaching anyone else. */
enum class ChecklistLink {
    /** Not syncing — the feature is off, or no screen has asked for it yet. */
    Off,
    Opening,
    /** A session with a router. Ticks are reaching the bus. */
    Connected,
    /** Session open, router gone. Work continues locally and replays are the bus's problem, not ours. */
    Disconnected,
    /** Setup failed — a bad endpoint, missing TLS credentials. Carries the reason. */
    Failed,
}

data class ChecklistSyncStatus(
    val link: ChecklistLink = ChecklistLink.Off,
    val message: String? = null,
    /** True once the procedure library and any state snapshot have been fetched from the router. */
    val bootstrapped: Boolean = false,
)

data class ChecklistUiState(
    val procedures: List<Procedure> = emptyList(),
    val state: ChecklistState = ChecklistState(),
    /** Other operators' heartbeats, stale ones already pruned. */
    val presence: List<RemotePresence> = emptyList(),
    val reminders: List<ChecklistReminder> = emptyList(),
    val sync: ChecklistSyncStatus = ChecklistSyncStatus(),
) {
    fun procedure(procedureId: String): Procedure? =
        procedures.firstOrNull { it.procedureId == procedureId }

    /** The title an item id stands for, or the id when no definition has arrived for it. */
    fun itemTitle(procedureId: String, itemId: String): String =
        procedure(procedureId)?.items?.firstOrNull { it.itemId == itemId }?.title ?: itemId

    fun reminderFor(procedureId: String, itemId: String): ChecklistReminder? =
        reminders.firstOrNull { it.procedureId == procedureId && it.itemId == itemId }
}

/**
 * The checklist's state, for the screens.
 *
 * **This one is pushed, unlike [se.rise.logline.publish.LiveSampleStore] and
 * [se.rise.logline.publish.AnnotationLog], and the difference is what feeds it.** Those are fed by the
 * publish path — ~217 sensor samples a second across eight collectors — and the rule that nothing
 * there may drive recomposition is what keeps the UI's frame rate off the sensors' hands. Nothing on
 * the publish path touches this. It is fed by a person tapping an item and by other operators doing
 * the same at their own sites: a few events a minute, each of which the person who caused it expects
 * to see immediately. A ticker here would only add latency to a tap.
 *
 * Every mutation goes through [MutableStateFlow.update]. The reducer is pure, but the *state* is
 * shared between a Zenoh subscriber thread, a heartbeat coroutine and the UI, and a
 * read-modify-write on `.value` loses updates under exactly the contention that matters.
 */
class ChecklistStore {

    private val _state = MutableStateFlow(ChecklistUiState())
    val state: StateFlow<ChecklistUiState> = _state.asStateFlow()

    /** Apply one event, local or remote. De-duplication and conflict rules live in [applyEvent]. */
    fun apply(event: ChecklistEventRecord) = _state.update { current ->
        current.copy(state = applyEvent(current.state, event, current::itemTitle))
    }

    fun apply(snapshot: ProcedureSnapshot) = _state.update { current ->
        current.copy(state = applySnapshot(current.state, snapshot))
    }

    /**
     * Merge in procedure definitions, newest write winning per id.
     *
     * A merge rather than a replace because the definitions arrive from two places — the router's
     * storage and this phone's own cache — and neither is complete on its own.
     */
    fun mergeProcedures(procedures: List<Procedure>) = _state.update { current ->
        val merged = LinkedHashMap<String, Procedure>()
        current.procedures.forEach { merged[it.procedureId] = it }
        procedures.forEach { merged[it.procedureId] = it }
        current.copy(procedures = merged.values.sortedBy { it.title })
    }

    /**
     * Record a heartbeat, replacing that operator's previous one and dropping anyone gone quiet.
     *
     * Pruning happens on write rather than on a timer: a heartbeat arriving is the only moment the
     * list can change, and a timer would be a second mechanism to keep in step with the first.
     */
    fun presence(seen: RemotePresence, staleBeforeEpochMillis: Long) = _state.update { current ->
        val others = current.presence.filter {
            it.operatorId != seen.operatorId && it.seenAtEpochMillis >= staleBeforeEpochMillis
        }
        current.copy(presence = (others + seen).sortedBy { it.username })
    }

    fun prunePresence(staleBeforeEpochMillis: Long) = _state.update { current ->
        current.copy(presence = current.presence.filter { it.seenAtEpochMillis >= staleBeforeEpochMillis })
    }

    fun setReminders(reminders: List<ChecklistReminder>) = _state.update { current ->
        current.copy(reminders = reminders)
    }

    fun setLink(link: ChecklistLink, message: String? = null) = _state.update { current ->
        current.copy(sync = current.sync.copy(link = link, message = message))
    }

    fun setBootstrapped(bootstrapped: Boolean) = _state.update { current ->
        current.copy(sync = current.sync.copy(bootstrapped = bootstrapped))
    }
}
