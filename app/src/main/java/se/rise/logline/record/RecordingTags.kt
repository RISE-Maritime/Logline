package se.rise.logline.record

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tagStore: DataStore<Preferences> by preferencesDataStore(name = "logline_recording_tags")

/**
 * Words the operator put on a recording, so a list of timestamps can be read as a list of runs.
 *
 * **Keyed on the recording's name, never on its MediaStore id.** The name is the run's start time and
 * is what the file is actually called; the id is a row number in a database this app does not own, and
 * on this phone alone it has been observed to change under a reinstall and to go missing entirely for
 * files an earlier install wrote. A tag surviving that is the whole point of writing it down.
 *
 * Its own DataStore rather than a corner of the settings, because these are not settings: they belong
 * to files rather than to the phone, and putting them in `Settings` would carry them into every
 * exported profile and onto every phone that imported one.
 */
class RecordingTags(private val context: Context) {

    /** Every tagged recording, by file name. Recordings with no tags are simply absent. */
    val tags: Flow<Map<String, Set<String>>> = context.tagStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val stored = value as? String ?: return@mapNotNull null
            val parsed = parseTags(stored)
            if (parsed.isEmpty()) null else key.name to parsed
        }.toMap()
    }

    /** Replace a recording's tags. An empty set removes the entry rather than storing a blank. */
    suspend fun set(recordingName: String, tags: Set<String>) {
        val key = stringPreferencesKey(recordingName)
        context.tagStore.edit { prefs ->
            if (tags.isEmpty()) prefs.remove(key) else prefs[key] = encodeTags(tags)
        }
    }

    /**
     * Forget tags for recordings that are no longer there.
     *
     * Cheap to skip and cheap to do — a tag is a few bytes — but a deleted run's words reappearing on a
     * later recording that happened to reuse its name would be worse than either.
     */
    suspend fun prune(keep: Set<String>) {
        context.tagStore.edit { prefs ->
            prefs.asMap().keys.map { it.name }.filterNot { it in keep }.forEach {
                prefs.remove(stringPreferencesKey(it))
            }
        }
    }
}

/**
 * A tag as it will be stored, or null if there is nothing left of it.
 *
 * Whitespace is collapsed and the separator is stripped: a tag carrying a newline would split into two
 * on the way back out, which is a quiet way to invent a tag nobody typed.
 */
fun normaliseTag(raw: String): String? = raw
    .replace(SEPARATOR, ' ')
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(MAX_TAG_LENGTH)
    .trim()
    .ifBlank { null }

/** Tags out of their stored form, in the order they were written. */
fun parseTags(stored: String): Set<String> =
    stored.split(SEPARATOR).mapNotNull(::normaliseTag).toSet()

/** Tags into their stored form. */
fun encodeTags(tags: Set<String>): String =
    tags.mapNotNull(::normaliseTag).joinToString(SEPARATOR.toString())

/**
 * A newline, which `normaliseTag` guarantees no tag contains.
 *
 * The same choice the endpoint list makes for the same reason — a delimiter that cannot appear in a
 * value needs no escaping, and escaping is where round trips go wrong.
 */
private const val SEPARATOR = '\n'

/** Long enough for a phrase, short enough that a row stays a row. */
private const val MAX_TAG_LENGTH = 40
