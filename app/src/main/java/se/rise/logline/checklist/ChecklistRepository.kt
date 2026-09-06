package se.rise.logline.checklist

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Base64

private val Context.checklistStore: DataStore<Preferences> by preferencesDataStore(name = "logline_checklist")

/** What survives the app being killed. */
data class StoredChecklist(
    val procedures: List<Procedure> = emptyList(),
    val progress: Map<String, ProcedureProgress> = emptyMap(),
    val reminders: List<ChecklistReminder> = emptyList(),
    val activeProcedureId: String = "",
)

/**
 * Local persistence for the checklist.
 *
 * Three things are kept, for three different reasons:
 *
 * - **Procedures**, so the phone can show a checklist with no router in sight. The router's storage is
 *   where they come from, but a boat's connectivity is not a precondition for reading a list.
 * - **Progress**, because a run that loses its ticks when Android reclaims the process is not a
 *   checklist. The bus is not a substitute: a snapshot only exists if somebody published one, and the
 *   phone may have been offline for all of it.
 * - **Reminders**, because they must be re-armed after a reboot, and `AlarmManager` forgets.
 *
 * Procedures and progress are stored as the **protobuf bytes the wire already uses**, Base64-encoded,
 * rather than as a second hand-written format. Two encodings of the same data drift; this one cannot,
 * because [ChecklistCodec] is the only thing that writes either. The cost is an envelope's timestamp
 * per record, which is nothing, and the benefit is that a stored record and a received one decode
 * through exactly the same path — including the part where a corrupt one comes back null.
 */
class ChecklistRepository(private val context: Context) {

    val stored: Flow<StoredChecklist> = context.checklistStore.data.map { prefs ->
        StoredChecklist(
            procedures = prefs[Keys.PROCEDURES].decodeLines(ChecklistCodec::decodeProcedure),
            // Keyed on the run, like everything else now. A record written before the re-key
            // carries no run id and `decodeSnapshot` resolves it to the procedure id, so it lands
            // exactly where it always did — the migration is the fallback, and there is nothing to
            // run. Named arguments deliberately: this was a positional two-argument call, which is
            // the shape that keeps compiling while quietly meaning something else once fields move.
            progress = prefs[Keys.PROGRESS]
                .decodeLines(ChecklistCodec::decodeSnapshot)
                .associate {
                    it.runId to ProcedureProgress(
                        items = it.items,
                        eventCount = it.eventCount,
                        runId = it.runId,
                        procedureId = it.procedureId,
                        title = it.procedureTitle,
                        status = it.status,
                        startedAtEpochMillis = it.startedAtEpochMillis,
                        completedAtEpochMillis = it.completedAtEpochMillis,
                        scheduledForEpochMillis = it.scheduledForEpochMillis,
                        abandonReason = it.abandonReason,
                        createdBy = it.createdBy,
                        createdBySite = it.createdBySite,
                        createdAtEpochMillis = it.createdAtEpochMillis,
                        itemsSnapshot = it.itemsSnapshot,
                    )
                },
            reminders = prefs[Keys.REMINDERS].orEmpty()
                .lineSequence()
                .mapNotNull(::parseChecklistReminder)
                .toList(),
            activeProcedureId = prefs[Keys.ACTIVE_PROCEDURE].orEmpty(),
        )
    }

    suspend fun saveProcedures(procedures: List<Procedure>) {
        context.checklistStore.edit { prefs ->
            prefs[Keys.PROCEDURES] = procedures.encodeLines { ChecklistCodec.encodeProcedure(it, "") }
        }
    }

    suspend fun saveProgress(progress: Map<String, ProcedureProgress>, operator: Operator) {
        context.checklistStore.edit { prefs ->
            prefs[Keys.PROGRESS] = progress.entries.encodeLines { (_, run) ->
                // The run's own procedure id, not the map key — the key is the run now.
                ChecklistCodec.encodeSnapshot(run.procedureId, run, operator)
            }
        }
    }

    suspend fun saveReminders(reminders: List<ChecklistReminder>) {
        context.checklistStore.edit { prefs ->
            prefs[Keys.REMINDERS] = reminders.joinToString("\n") { it.serialise() }
        }
    }

    suspend fun setActiveProcedure(procedureId: String) {
        context.checklistStore.edit { prefs -> prefs[Keys.ACTIVE_PROCEDURE] = procedureId }
    }

    private object Keys {
        val PROCEDURES = stringPreferencesKey("procedures")
        val PROGRESS = stringPreferencesKey("progress")
        val REMINDERS = stringPreferencesKey("reminders")
        val ACTIVE_PROCEDURE = stringPreferencesKey("active_procedure")
    }
}

/**
 * Base64 is not decoration: DataStore preferences hold strings, and protobuf bytes are not text. The
 * URL-safe alphabet only so a stray record is not mistaken for something it can be pasted into.
 */
private fun <T> Iterable<T>.encodeLines(encode: (T) -> ByteArray): String =
    joinToString("\n") { Base64.getUrlEncoder().withoutPadding().encodeToString(encode(it)) }

/** A record that no longer decodes is dropped, the same way an unparseable preference line is. */
private fun <T : Any> String?.decodeLines(decode: (ByteArray) -> T?): List<T> =
    orEmpty().lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            runCatching { Base64.getUrlDecoder().decode(line) }.getOrNull()?.let(decode)
        }
        .toList()
