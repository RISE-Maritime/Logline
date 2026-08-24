package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.rise.logline.publish.formatElapsed
import se.rise.logline.record.SavedRecording
import se.rise.logline.record.TrackFix
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
    /** Open the recording's own page: its topics, its figures and its track. */
    onOpen: (SavedRecording) -> Unit,
    /**
     * The search box and the two orderings, hoisted into `MainActivity` like the live view's own
     * preferences — a `remember` here dies when the screen is popped, so opening a recording and
     * coming back would clear a search somebody had just typed.
     */
    query: String,
    onQueryChange: (String) -> Unit,
    sort: RecordingSort,
    onSortChange: (RecordingSort) -> Unit,
    filter: RecordingFilter,
    onFilterChange: (RecordingFilter) -> Unit,
    /**
     * Whether the folder has been granted, so recordings written by an earlier install are listed too.
     *
     * MediaStore ties a file to the install that wrote it, so without this the list silently holds only
     * part of the folder — measured, a file written under another package was absent from a listing
     * that returned all fifteen of this install's own. The offer is a *line*, not a prompt: a folder
     * grant asked for unbidden at launch is one people dismiss without reading, and nothing here is
     * broken until somebody is looking for a recording that is not in the list.
     */
    folderGranted: Boolean = false,
    onGrantFolder: () -> Unit = {},
    /**
     * Delete every recording in the list, answering how many actually went.
     *
     * A count rather than a boolean because MediaStore refuses a delete from a package that did not
     * write the file, so a sweep can partly fail and the screen has to be able to say which.
     */
    onDeleteAll: suspend (List<SavedRecording>) -> Int,
    /**
     * The recording's track for its thumbnail, or null when there is none to draw.
     *
     * Suspending and per row, because extracting one means decompressing the recording's whole data
     * section — there is no chunk index to seek with. The caller caches and bounds how many run at
     * once; see `MainActivity`.
     */
    onLoadTrack: suspend (SavedRecording) -> List<TrackFix>?,
    /** The navigation bar, supplied by `MainActivity`. See `TopLevel`. */
    bottomBar: @Composable () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf<SavedRecording?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val shown = remember(files, query, sort, filter) {
        visibleRecordings(files, query, sort, filter)
    }
    val listState = rememberLazyListState()
    // **A reorder has to bring the top of the list with it.**
    //
    // `items(key = …)` makes a `LazyColumn` keep the item it was showing in view when the list is
    // reordered, which is right for a delete and wrong for a sort: choosing "Largest first" scrolled to
    // wherever the previously-visible file had moved to, so the 479 MB recording sat at the top of a
    // list still showing 2 MB ones — the control read as broken while working perfectly.
    //
    // Guarded against firing on first composition, because `rememberLazyListState` restores through the
    // tab's `saveState`, and jumping to the top every time somebody came back from opening a recording
    // would be its own small wrongness.
    var lastOrder by remember { mutableStateOf(Triple(query, sort, filter)) }
    LaunchedEffect(query, sort, filter) {
        val order = Triple(query, sort, filter)
        if (order != lastOrder) {
            lastOrder = order
            listState.scrollToItem(0)
        }
    }

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

    if (confirmDeleteAll) {
        val bytes = shown.sumOf { it.sizeBytes }
        ConfirmDialog(
            title = "Delete ${formatCounted(shown.size.toLong(), "incomplete recording")}?",
            // **These files are not junk, and the word "incomplete" invites exactly that reading.** A
            // run interrupted by a killed process keeps every message it captured; only the closing
            // figures were never written, and this app reads them perfectly well — one of them draws a
            // 47-fix track. So the dialog states what is lost, in figures rather than adjectives.
            body = "Every message in these recordings is still there; only the closing figures are " +
                "missing, and Logline can still read them.\n\n" +
                "This frees ${formatBytes(bytes)}. The phone is the only copy unless they have been " +
                "shared. There is no undo.",
            confirmLabel = "Delete ${shown.size}",
            dismissLabel = "Keep",
            onConfirm = {
                val doomed = shown
                confirmDeleteAll = false
                scope.launch {
                    val deleted = onDeleteAll(doomed)
                    val failed = doomed.size - deleted
                    snackbars.showSnackbar(
                        if (failed == 0) {
                            "Deleted ${formatCounted(deleted.toLong(), "recording")}"
                        } else {
                            // Named rather than swallowed: a file that outlived the install that wrote
                            // it cannot be deleted from here, and silence would read as a clean sweep.
                            "Deleted $deleted · $failed could not be deleted"
                        }
                    )
                }
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }

    ScreenScaffold(
        title = "Recordings",
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        if (files.isEmpty()) {
            // "Looking…" and "none yet" are different states and must not be conflated: a listing
            // that has not been read yet has nothing to say about whether there are files.
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (loaded) {
                    EmptyState(
                        title = "Nothing saved yet",
                        body = "Finished recordings are copied to Downloads/Logline and appear here. " +
                            "Exported settings and platform geometry go to Downloads/Logline/config " +
                            "instead, so they do not clutter this list.\n\n" +
                            "Files saved by an earlier install of this app are not listed — Android " +
                            "ties them to the install that wrote them. They are still in " +
                            "Downloads/Logline and any file manager can see them.",
                    )
                    if (!folderGranted) {
                        OutlinedButton(onClick = onGrantFolder) { Text("Show the whole folder…") }
                    }
                } else {
                    Text("Looking…", style = MaterialTheme.typography.bodyLarge)
                }
            }
            return@ScreenScaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // **Pinned, not scrolled with the list.** The controls are what somebody reaches for while
            // looking through a hundred and nineteen files, and a search box that has scrolled off is a
            // search box they have to scroll back for.
            RecordingsToolbar(
                query = query,
                onQueryChange = onQueryChange,
                sort = sort,
                onSortChange = onSortChange,
                filter = filter,
                onFilterChange = onFilterChange,
                shown = shown.size,
                total = files.size,
            )

            // **The list's own admission that it may be partial.** This is the whole complaint the
            // folder grant answers: the empty state said it in words, but a list with fifteen rows in
            // it and three more sitting unlisted in the folder gave no hint at all, so the missing ones
            // looked like data loss. One quiet line rather than a banner — nothing is broken, and it
            // disappears for good the moment the folder is granted.
            if (!folderGranted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                ) {
                    Text(
                        "Recordings from an earlier install are not listed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onGrantFolder) { Text("Show all") }
                }
            }

            // **Offered only under the Incomplete filter**, so the set it deletes is exactly the one
            // named on the button and it can never become a one-tap way to destroy good recordings.
            // Exports are not in that set — see `RecordingFacts.isComplete`.
            if (filter == RecordingFilter.Incomplete && shown.isNotEmpty()) {
                OutlinedButton(
                    onClick = { confirmDeleteAll = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        // Error-coloured content, not a filled red block: the one large red block in
                        // this app is Stop, deliberately, and this is a step up from the per-row
                        // `TextButton` without taking that over.
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("Delete all ${shown.size} · ${formatBytes(shown.sumOf { it.sizeBytes })}")
                }
            }

            if (shown.isEmpty()) {
                // **A third empty state.** "Nothing saved yet" would be a lie with a hundred files on
                // the phone, and the difference matters: one is a phone that has never recorded, this
                // is a search that excluded everything.
                Column(Modifier.padding(horizontal = 16.dp)) {
                    EmptyState(
                        title = "No recordings match",
                        body = if (filter == RecordingFilter.All) {
                            "Nothing here is named or tagged like that. A recording's name is the date " +
                                "and time it started, so 2026-08-21 or 0821 will find one."
                        } else {
                            "Nothing matches with the list set to ${filter.label.lowercase()}."
                        },
                    )
                }
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
            items(shown, key = { it.uri.toString() }) { file ->
                // The whole card opens it, with Details spelled out as well: a tappable card gives no
                // sign it is tappable, and a row whose only controls send a file away or destroy it
                // should not have a third, safer action hidden in the background.
                Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpen(file) }) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(file.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                recordingSubtitle(file),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // The words somebody put on this run, which is the thing a list of
                            // timestamps cannot otherwise tell you.
                            if (file.tags.isNotEmpty()) {
                                Text(
                                    file.tags.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onOpen(file) }) { Text("Details") }
                                OutlinedButton(onClick = { onShare(file) }) { Text("Share…") }
                                TextButton(onClick = { confirmDelete = file }) { Text("Delete") }
                            }
                        }
                        // **Loaded from inside the row, so only rows on screen cost anything.** A
                        // `LazyColumn` composes a handful at a time, and `produceState` keyed on the
                        // file cancels with the row when it scrolls away.
                        val track by produceState<ThumbnailState>(ThumbnailState.Unread, file.uri) {
                            value = ThumbnailState.Reading
                            val fixes = onLoadTrack(file)
                            value = when {
                                fixes == null || fixes.size < 2 -> ThumbnailState.NoTrack
                                else -> ThumbnailState.Ready(fixes)
                            }
                        }
                        TrackThumbnail(track, Modifier.padding(start = 8.dp))
                    }
                }
            }
            }
        }
    }
}

/**
 * Search, order and completeness, in two rows above the list.
 *
 * The count sits with the controls rather than under the title, because it is *their* readout: it is
 * how somebody sees that a filter is on when the thing it hid is off screen.
 */
@Composable
private fun RecordingsToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: RecordingSort,
    onSortChange: (RecordingSort) -> Unit,
    filter: RecordingFilter,
    onFilterChange: (RecordingFilter) -> Unit,
    shown: Int,
    total: Int,
) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Search by date, name or tag") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear the search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChoiceMenu(
                current = sort.label,
                options = RecordingSort.entries,
                describe = { it.label },
                onChoose = onSortChange,
            )
            ChoiceMenu(
                current = filter.label,
                options = RecordingFilter.entries,
                describe = { it.label },
                onChoose = onFilterChange,
            )
            Spacer(Modifier.weight(1f))
            Text(
                recordingsCount(shown, total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()
    }
}

/** A compact text button that drops a menu — lighter than the form screens' full-width field. */
@Composable
private fun <T> ChoiceMenu(
    current: String,
    options: List<T>,
    describe: (T) -> String,
    onChoose: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(current, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(describe(option)) },
                    onClick = {
                        onChoose(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
