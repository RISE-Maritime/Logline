package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.rise.logline.publish.formatElapsed
import se.rise.logline.record.SavedRecording
import se.rise.logline.ui.components.ConfirmDialog
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.EmptyState

/**
 * What this phone has saved, and the two things anyone wants to do with it.
 *
 * Recordings are copied to `Downloads/Logline` and were then out of the app's reach: the last step of
 * every field session was a file manager or a USB cable. Sharing is the point of the screen; deleting
 * is here because the other reason to go looking for these files is that the disk is full.
 *
 * The file being written right now is deliberately absent — it lives in app-private storage until it
 * is closed, and the session card on the start screen reports it live.
 *
 * A tab rather than a pushed screen since the files are this app's output, not its configuration —
 * which is what let the session card stop naming the folder every recording had gone to.
 */
@Composable
fun RecordingsScreen(
    files: List<SavedRecording>,
    /** Null until the listing has been read once, so "none yet" is not shown over "not looked yet". */
    loaded: Boolean,
    onShare: (SavedRecording) -> Unit,
    onDelete: (SavedRecording) -> Unit,
    /** The navigation bar, supplied by `MainActivity`. See `TopLevel`. */
    bottomBar: @Composable () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf<SavedRecording?>(null) }

    confirmDelete?.let { file ->
        ConfirmDialog(
            title = "Delete ${file.name}?",
            body = "This phone is the only copy unless it has been shared. There is no undo.",
            confirmLabel = "Delete",
            dismissLabel = "Keep",
            onConfirm = {
                onDelete(file)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }

    ScreenScaffold(title = "Recordings", bottomBar = bottomBar) { padding ->
        if (files.isEmpty()) {
            // "Looking…" and "none yet" are different states and must not be conflated: a listing
            // that has not been read yet has nothing to say about whether there are files.
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (loaded) {
                    EmptyState(
                        title = "Nothing saved yet",
                        body = "Finished recordings are copied to Downloads/Logline and appear here.\n\n" +
                            "Files saved by an earlier install of this app are not listed — Android " +
                            "ties them to the install that wrote them. They are still in " +
                            "Downloads/Logline and any file manager can see them.",
                    )
                } else {
                    Text("Looking…", style = MaterialTheme.typography.bodyLarge)
                }
            }
            return@ScreenScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            items(files, key = { it.uri.toString() }) { file ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            detailOf(file),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onShare(file) }) { Text("Share…") }
                            TextButton(onClick = { confirmDelete = file }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Size first, then what the file says about itself.
 *
 * A file with no summary shows its size and stops. That is a recording rescued from a killed process —
 * every message present, statistics gone — or the rig calibration's JSON export, which shares this
 * folder. Neither should be made to claim a message count it does not have.
 */
private fun detailOf(file: SavedRecording): String = buildString {
    append(formatBytes(file.sizeBytes))
    val summary = file.summary
    if (summary == null) {
        append(" · no summary")
        return@buildString
    }
    append(" · ")
    append(formatCounted(summary.messages, "message"))
    if (summary.durationMillis >= 1_000L) {
        append(" over ")
        append(formatElapsed(summary.durationMillis))
    }
}
