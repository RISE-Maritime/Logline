package se.rise.logline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import se.rise.logline.checklist.ChecklistLink
import se.rise.logline.checklist.ChecklistUiState
import se.rise.logline.checklist.ItemStatus
import se.rise.logline.checklist.ProcedureProgress
import se.rise.logline.checklist.RunStatus
import se.rise.logline.ui.components.EmptyState
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone

/**
 * What the ROC stations are working through, and this phone's part in it.
 *
 * **A simplified view, and query-free by design.** The full pair of screens beside this one bootstrap
 * by asking the router's storage for the library and the progress — and a Zenoh query's *reply* aborts
 * the process on this binding, which is what had the whole feature switched off. Everything here
 * arrives by subscription instead: crowsnest republishes each active run's snapshot periodically, so a
 * late joiner is caught up within one interval rather than by asking.
 *
 * The cost is item *text* for a procedure this phone has never held: that lives in
 * `checklist_procedure`, which is storage-only. Such a run still renders — the snapshot carries the
 * run's own title, and `ChecklistUiState.itemTitle` already falls back to the item id — and the row
 * says so rather than looking broken or being hidden. Progress is accurate either way, because it is
 * counted from ids.
 */
@Composable
fun ChecklistRunsScreen(
    state: ChecklistUiState,
    /** Null when this phone has no operator yet: without one it can watch but must not tick. */
    canTick: Boolean,
    onSetIdentity: () -> Unit,
    onToggleItem: (procedureId: String, itemId: String, done: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(title = "Checklists", onBack = onBack, modifier = modifier) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinkLine(state)

            if (!canTick) {
                // Stated rather than silently read-only: a screen with no tick controls and no reason
                // reads as a screen that failed to load them.
                StatusLine(
                    text = "Watching only",
                    tone = StatusTone.Neutral,
                    detail = "Set a name and site to tick items — a tick is published with both.",
                )
                TextButton(onClick = onSetIdentity) { Text("Set who is using this phone…") }
            }

            val runs = state.state.progress.entries.sortedBy { (id, progress) ->
                progress.title.ifEmpty { id }
            }

            if (runs.isEmpty()) {
                EmptyState(
                    title = "No runs on the bus",
                    // Three different silences, and the honest answer is that this cannot tell them
                    // apart from here.
                    body = if (state.sync.link == ChecklistLink.Connected) {
                        "Nothing is being worked through right now, or no station has republished " +
                            "since this screen opened. Snapshots arrive about every 30 seconds."
                    } else {
                        "Not connected to the bus yet."
                    },
                )
            }

            runs.forEach { (procedureId, progress) ->
                RunCard(
                    procedureId = procedureId,
                    progress = progress,
                    state = state,
                    canTick = canTick,
                    onToggleItem = onToggleItem,
                )
            }
        }
    }
}

@Composable
private fun LinkLine(state: ChecklistUiState) {
    val others = state.presence.size
    when (state.sync.link) {
        ChecklistLink.Connected -> StatusLine(
            text = "Listening",
            tone = StatusTone.Positive,
            detail = if (others == 0) {
                "No other operators on this checklist right now."
            } else {
                "Also here: " + state.presence.joinToString { "${it.username} (${it.rocSite})" }
            },
        )

        ChecklistLink.Disconnected -> StatusLine(
            text = "Working offline",
            tone = StatusTone.Warning,
            detail = "Ticks are kept on this phone and sent when the router comes back.",
        )

        ChecklistLink.Opening -> StatusLine("Connecting…", StatusTone.Neutral)
        ChecklistLink.Failed -> StatusLine(
            text = "Not syncing",
            tone = StatusTone.Error,
            detail = state.sync.message,
        )

        ChecklistLink.Off -> StatusLine("Not syncing", StatusTone.Neutral)
    }
}

@Composable
private fun RunCard(
    procedureId: String,
    progress: ProcedureProgress,
    state: ChecklistUiState,
    canTick: Boolean,
    onToggleItem: (String, String, Boolean) -> Unit,
) {
    var expanded by remember(procedureId) { mutableStateOf(false) }
    val known = state.procedures.any { it.procedureId == procedureId }
    // The run's own title first: it is the only name a run has when this phone holds no definition.
    val title = progress.title
        .ifEmpty { state.procedures.firstOrNull { it.procedureId == procedureId }?.title.orEmpty() }
        .ifEmpty { procedureId }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        progressLine(progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
            }

            if (expanded) {
                if (!known) {
                    // The whole of the "shown, not hidden" decision: progress counted from ids is
                    // true, and only the wording is missing — so say which.
                    Text(
                        "The item text for this procedure has not reached this phone, so items are " +
                            "listed by id. Progress is still accurate.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                SectionHeader("Items")
                progress.items.entries
                    .sortedBy { it.key }
                    .forEach { (itemId, item) ->
                        val done = item.status == ItemStatus.Completed
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (done) "✓ " else "□ ") + state.itemTitle(procedureId, itemId),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (canTick) {
                                TextButton(onClick = { onToggleItem(procedureId, itemId, done) }) {
                                    Text(if (done) "Undo" else "Tick")
                                }
                            }
                        }
                    }
            }
        }
    }
}

/** `4 of 11 · active`, or just the count where the publisher predates the run model. */
private fun progressLine(progress: ProcedureProgress): String {
    val counted = "${progress.completedCount()} of ${progress.items.size}"
    val flagged = progress.flaggedCount().takeIf { it > 0 }?.let { " · $it flagged" }.orEmpty()
    val status = when (progress.status) {
        RunStatus.Planned -> " · planned"
        RunStatus.Active -> " · active"
        RunStatus.Completed -> " · completed"
        RunStatus.Abandoned -> " · abandoned"
        RunStatus.Unknown -> ""
    }
    return counted + status + flagged
}
