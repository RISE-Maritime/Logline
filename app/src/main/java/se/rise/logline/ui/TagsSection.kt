package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.rise.logline.record.normaliseTag
import se.rise.logline.ui.components.InfoDialog
import se.rise.logline.ui.components.SectionHeader

/**
 * The tags this run will be filed under.
 *
 * **A tag is a state, not an event**, which is what makes it a row of switches rather than more
 * buttons — and what puts it here, on the screen where a run is set up, rather than on the Events tab
 * where it first landed. Events is for marking moments as they pass; what the whole run *is* belongs
 * with the other things chosen before Start, beside the sampling rates.
 *
 * Whatever is switched on when a file closes is written into it as MCAP metadata, so the label is
 * composed while the run happens rather than remembered afterwards. The vocabulary and the selection
 * both persist between runs.
 */
@Composable
fun TagsSection(
    tags: List<String>,
    activeTags: Set<String>,
    onToggleTag: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    if (showHelp) {
        InfoDialog(
            title = "Tags",
            body = "A tag says what a run is, where a mark says what happened in it.\n\n" +
                "Whatever is switched on when a recording closes is written into the file itself, so " +
                "it travels with the recording rather than staying on this phone — a copy on a " +
                "laptop still says what it was.\n\n" +
                "The words and which of them are on both persist between runs, so a rig that is " +
                "always the same needs telling once. A run that rotates through several files gives " +
                "each file the tags that were on as it closed.",
            onDismiss = { showHelp = false },
        )
    }

    SectionHeader(
        title = "Tags",
        onInfo = { showHelp = true },
        // A gear rather than a word: the chips are the control, and this is the control *for* them.
        action = {
            IconButton(onClick = { editing = !editing }) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = if (editing) "Finish editing tags" else "Edit the tags",
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tags.isEmpty()) {
            Text(
                "No tags yet. A tag is written into the recording when it closes, so it travels with " +
                    "the file. Add one with the gear above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    FilterChip(
                        selected = tag in activeTags,
                        // While editing the chip removes rather than toggles — one control, with the
                        // trailing cross saying which job it is doing.
                        onClick = { if (editing) onRemoveTag(tag) else onToggleTag(tag) },
                        label = { Text(tag) },
                        trailingIcon = if (editing) {
                            {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Remove the tag $tag",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        if (editing) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    // A newline would split one tag into two on the way out of the file, which is a
                    // quiet way to invent a tag nobody typed.
                    onValueChange = { draft = it.replace('\n', ' ') },
                    label = { Text("New tag") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        normaliseTag(draft)?.let(onAddTag)
                        draft = ""
                    },
                    enabled = normaliseTag(draft) != null,
                ) { Text("Add") }
            }
        }
    }
}
