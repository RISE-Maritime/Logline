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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
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
import se.rise.logline.checklist.ItemProgress
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
    onToggleItem: (runId: String, procedureId: String, itemId: String, done: Boolean) -> Unit,
    onFlagItem: (runId: String, procedureId: String, itemId: String, reason: String) -> Unit,
    onResolveFlag: (runId: String, procedureId: String, itemId: String, resolution: String) -> Unit,
    onAttachPhoto: (runId: String, procedureId: String, itemId: String) -> Unit,
    onAbandonRun: (runId: String, procedureId: String, reason: String) -> Unit,
    /** Why the last thing somebody tried did not work. Null when nothing has gone wrong. */
    message: String? = null,
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

            // A picked photograph that would not decode, and the like. Stated rather than swallowed:
            // a tap that appears to do nothing is how somebody comes to believe the feature is
            // broken when one file was.
            message?.let { StatusLine(text = it, tone = StatusTone.Warning) }

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

            // Runs still going first, then the finished ones — and within each, by name. A run that
            // has ended is a record to look back at; a run in progress is what somebody is here for.
            val runs = state.state.progress.values.sortedWith(
                compareBy({ it.isTerminal() }, { it.title.ifEmpty { it.runId } }),
            )

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

            runs.forEach { progress ->
                RunCard(
                    progress = progress,
                    state = state,
                    canTick = canTick,
                    onToggleItem = onToggleItem,
                    onFlagItem = onFlagItem,
                    onResolveFlag = onResolveFlag,
                    onAttachPhoto = onAttachPhoto,
                    onAbandonRun = onAbandonRun,
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
    progress: ProcedureProgress,
    state: ChecklistUiState,
    canTick: Boolean,
    onToggleItem: (String, String, String, Boolean) -> Unit,
    onFlagItem: (String, String, String, String) -> Unit,
    onResolveFlag: (String, String, String, String) -> Unit,
    onAttachPhoto: (String, String, String) -> Unit,
    onAbandonRun: (String, String, String) -> Unit,
) {
    val runId = progress.runId
    val procedureId = progress.procedureId.ifEmpty { runId }
    var expanded by remember(runId) { mutableStateOf(false) }
    var prompt by remember(runId) { mutableStateOf<Prompt?>(null) }
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

            // Why a run was stopped, which lives in the snapshot precisely so it survives for a
            // station that was not listening when it happened.
            if (progress.status == RunStatus.Abandoned && progress.abandonReason.isNotEmpty()) {
                Text(
                    "Stopped: ${progress.abandonReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
                        ItemRow(
                            itemId = itemId,
                            item = item,
                            title = state.itemTitle(procedureId, itemId),
                            canTick = canTick,
                            onToggle = { onToggleItem(runId, procedureId, itemId, it) },
                            onFlag = { prompt = Prompt.Flag(itemId) },
                            onResolve = { prompt = Prompt.Resolve(itemId) },
                            onAttach = { onAttachPhoto(runId, procedureId, itemId) },
                        )
                    }

                if (canTick && !progress.isTerminal()) {
                    TextButton(onClick = { prompt = Prompt.Abandon }) { Text("Stop this run…") }
                }
            }
        }
    }

    prompt?.let { active ->
        ReasonDialog(
            prompt = active,
            onDismiss = { prompt = null },
            onConfirm = { text ->
                when (active) {
                    is Prompt.Flag -> onFlagItem(runId, procedureId, active.itemId, text)
                    is Prompt.Resolve -> onResolveFlag(runId, procedureId, active.itemId, text)
                    Prompt.Abandon -> onAbandonRun(runId, procedureId, text)
                }
                prompt = null
            },
        )
    }
}

@Composable
private fun ItemRow(
    itemId: String,
    item: ItemProgress,
    title: String,
    canTick: Boolean,
    onToggle: (Boolean) -> Unit,
    onFlag: () -> Unit,
    onResolve: () -> Unit,
    onAttach: () -> Unit,
) {
    val done = item.status == ItemStatus.Completed
    val open = item.openFlag()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (done) "✓ " else "□ ") + title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (canTick) {
                TextButton(onClick = { onToggle(done) }) { Text(if (done) "Undo" else "Tick") }
            }
        }

        // The open flag, from the list rather than the scalar cache. A resolved one is not shown
        // here — it is history, and the place for that is the timeline, not the row somebody is
        // working down.
        open?.let {
            Text(
                "⚑ ${it.reason.ifEmpty { "flagged" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // The closed ones are counted rather than listed, so an item that has had trouble before
        // says so without pushing the live work off the screen.
        val closed = item.flags.count { !it.open }
        if (closed > 0) {
            Text(
                if (closed == 1) "1 issue resolved earlier" else "$closed issues resolved earlier",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.evidence.isNotEmpty()) {
            // Metadata, and said so: fetching another station's photo needs a Zenoh query, whose
            // reply aborts the process on this binding. A tile that cannot be filled is worse than a
            // line that admits it.
            Text(
                item.evidence.joinToString { photo ->
                    val size = if (photo.width > 0) " ${photo.width}×${photo.height}" else ""
                    "📷 ${photo.caption.ifEmpty { "photo" }}$size"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (canTick) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (open == null) {
                    TextButton(onClick = onFlag) { Text("Flag") }
                } else {
                    TextButton(onClick = onResolve) { Text("Resolve") }
                }
                TextButton(onClick = onAttach) { Text("Photo") }
            }
        }
    }
}

/** What a [ReasonDialog] is being opened for. Each of these needs words, and none of them optional. */
private sealed interface Prompt {
    data class Flag(val itemId: String) : Prompt
    data class Resolve(val itemId: String) : Prompt
    data object Abandon : Prompt
}

/**
 * The three things on this screen that require a reason, asked for the same way.
 *
 * None of the three has a "skip" — an issue with no reason is a red mark nobody can act on, a
 * resolution with none is the half an auditor asks about, and a run stopped with none reaches a
 * station that was not listening as ABANDONED with nothing to explain it. Upstream makes the last
 * one required for exactly that reason.
 */
@Composable
private fun ReasonDialog(prompt: Prompt, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember(prompt) { mutableStateOf("") }
    val (title, label) = when (prompt) {
        is Prompt.Flag -> "Flag this item" to "What is wrong?"
        is Prompt.Resolve -> "Resolve the flag" to "How was it cleared?"
        Prompt.Abandon -> "Stop this run" to "Why is it being stopped?"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text("Confirm")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** `4 of 11 · active · 1 flagged`, or just the count where the publisher predates the run model. */
private fun progressLine(progress: ProcedureProgress): String {
    val counted = "${progress.completedCount()} of ${progress.items.size}"
    val flagged = progress.flaggedCount().takeIf { it > 0 }?.let { " · $it flagged" }.orEmpty()
    val photos = progress.evidenceCount().takeIf { it > 0 }?.let { " · $it photo" + if (it == 1) "" else "s" }.orEmpty()
    val status = when (progress.status) {
        RunStatus.Planned -> " · planned"
        RunStatus.Active -> " · active"
        RunStatus.Completed -> " · completed"
        RunStatus.Abandoned -> " · abandoned"
        RunStatus.Unknown -> ""
    }
    return counted + status + flagged + photos
}
