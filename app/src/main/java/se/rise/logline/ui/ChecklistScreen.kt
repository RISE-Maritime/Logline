package se.rise.logline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.rise.logline.checklist.ChecklistReminder
import se.rise.logline.checklist.ChecklistUiState
import se.rise.logline.checklist.ItemProgress
import se.rise.logline.checklist.ItemStatus
import se.rise.logline.checklist.Procedure
import se.rise.logline.checklist.ProcedureItem
import se.rise.logline.checklist.TimelineKind
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone

/** What a text dialog is currently collecting, if anything. */
private sealed interface ItemPrompt {
    val itemId: String

    data class Note(override val itemId: String) : ItemPrompt
    data class Flag(override val itemId: String) : ItemPrompt
    data class ResolveFlag(override val itemId: String) : ItemPrompt
    data class Remind(override val itemId: String) : ItemPrompt
}

/**
 * One procedure, worked through.
 *
 * The whole screen is other people's state as much as this operator's: an item can go green because
 * somebody at a ROC ticked it a second ago, so every completed row says who and where. That is the
 * difference between this and a to-do list, and it is why the row is not just a checkbox.
 */
@Composable
fun ChecklistScreen(
    procedure: Procedure,
    state: ChecklistUiState,
    nowMillis: Long,
    onStartItem: (String) -> Unit,
    onCompleteItem: (String) -> Unit,
    onRevertItem: (String) -> Unit,
    onAddNote: (String, String) -> Unit,
    onFlagItem: (String, String) -> Unit,
    onResolveFlag: (String, String) -> Unit,
    onSetReminder: (itemId: String, minutes: Int, repeat: Boolean) -> Unit,
    onClearReminder: (String) -> Unit,
    onCompleteProcedure: () -> Unit,
    onFocusItem: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var prompt by remember { mutableStateOf<ItemPrompt?>(null) }
    val progress = state.state.progressFor(procedure.procedureId)
    val done = progress.completedCount()
    val requiredOutstanding = procedure.items
        .filter { it.required && progress.item(it.itemId).status != ItemStatus.Completed }

    ScreenScaffold(title = procedure.title, onBack = onBack, modifier = modifier) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(procedure, done, requiredOutstanding.size, state, onCompleteProcedure)

            SectionHeader("Items", trailing = "$done/${procedure.items.size}")
            procedure.items.forEach { item ->
                ItemCard(
                    item = item,
                    progress = progress.item(item.itemId),
                    reminder = state.reminderFor(procedure.procedureId, item.itemId),
                    nowMillis = nowMillis,
                    onToggle = {
                        onFocusItem(item.itemId)
                        if (progress.item(item.itemId).status == ItemStatus.Completed) {
                            onRevertItem(item.itemId)
                        } else {
                            onCompleteItem(item.itemId)
                        }
                    },
                    onStart = { onStartItem(item.itemId) },
                    onPrompt = { prompt = it },
                    onClearReminder = { onClearReminder(item.itemId) },
                )
            }

            if (state.state.timeline.isNotEmpty()) {
                SectionHeader("Activity")
                state.state.timeline
                    .filter { it.procedureId == procedure.procedureId }
                    .take(TIMELINE_ROWS)
                    .forEach { entry ->
                        Text(
                            buildString {
                                append(formatAge(entry.atEpochMillis, nowMillis))
                                append(" · ")
                                append(entry.operatorName.ifBlank { "Someone" })
                                append(" (").append(entry.rocSite).append(") ")
                                append(entry.kind.phrase())
                                if (entry.itemTitle.isNotBlank()) append(" ").append(entry.itemTitle)
                                if (entry.note.isNotBlank()) append(" — ").append(entry.note)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
    }

    when (val current = prompt) {
        is ItemPrompt.Note -> TextPrompt(
            title = "Add a note",
            label = "Note",
            onConfirm = { onAddNote(current.itemId, it); prompt = null },
            onDismiss = { prompt = null },
        )

        is ItemPrompt.Flag -> TextPrompt(
            title = "Flag this item",
            label = "What is wrong?",
            onConfirm = { onFlagItem(current.itemId, it); prompt = null },
            onDismiss = { prompt = null },
        )

        is ItemPrompt.ResolveFlag -> TextPrompt(
            title = "Resolve flag",
            label = "How was it resolved?",
            onConfirm = { onResolveFlag(current.itemId, it); prompt = null },
            onDismiss = { prompt = null },
        )

        is ItemPrompt.Remind -> ReminderPrompt(
            onConfirm = { minutes, repeat -> onSetReminder(current.itemId, minutes, repeat); prompt = null },
            onDismiss = { prompt = null },
        )

        null -> Unit
    }
}

@Composable
private fun Header(
    procedure: Procedure,
    done: Int,
    requiredOutstanding: Int,
    state: ChecklistUiState,
    onCompleteProcedure: () -> Unit,
) {
    val here = state.presence.filter { it.activeProcedureId == procedure.procedureId }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                procedure.items.isEmpty() -> StatusLine("Nothing in this procedure", StatusTone.Neutral)
                requiredOutstanding == 0 -> StatusLine(
                    text = "All required items done",
                    tone = StatusTone.Positive,
                    detail = "$done of ${procedure.items.size} complete.",
                )

                else -> StatusLine(
                    text = "$done of ${procedure.items.size} complete",
                    tone = StatusTone.Neutral,
                    detail = formatCounted(requiredOutstanding.toLong(), "required item") + " outstanding.",
                )
            }
            if (here.isNotEmpty()) {
                Text(
                    "Also on this procedure: " + here.joinToString { "${it.username} (${it.rocSite})" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (procedure.items.isNotEmpty() && requiredOutstanding == 0) {
                Button(onClick = onCompleteProcedure, modifier = Modifier.fillMaxWidth()) {
                    Text("Mark procedure complete")
                }
            }
        }
    }
}

@Composable
private fun ItemCard(
    item: ProcedureItem,
    progress: ItemProgress,
    reminder: ChecklistReminder?,
    nowMillis: Long,
    onToggle: () -> Unit,
    onStart: () -> Unit,
    onPrompt: (ItemPrompt) -> Unit,
    onClearReminder: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = when {
                        progress.flagged -> Icons.Default.Warning
                        progress.status == ItemStatus.Completed -> Icons.Default.CheckCircle
                        else -> Icons.Default.Info
                    },
                    contentDescription = when (progress.status) {
                        ItemStatus.Completed -> "Completed"
                        ItemStatus.InProgress -> "In progress"
                        ItemStatus.Pending -> "Not started"
                    },
                    tint = when {
                        progress.flagged -> MaterialTheme.colorScheme.error
                        progress.status == ItemStatus.Completed -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "${item.number}. ${item.title}" + if (item.required) "" else "  (optional)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (item.description.isNotBlank()) {
                        Text(
                            item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Who and where, always — an item can go green because somebody at another site
                    // ticked it, and "done" without "by whom" is not something to act on.
                    progress.completedAtEpochMillis?.let { at ->
                        Text(
                            "Done ${formatAge(at, nowMillis)} by ${progress.completedBy.ifBlank { "someone" }}" +
                                progress.completedBySite.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (progress.status == ItemStatus.InProgress) {
                        Text(
                            "Started by ${progress.startedBy.ifBlank { "someone" }}" +
                                progress.startedBySite.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (progress.flagged) {
                        Text(
                            "Flagged: ${progress.flagReason}" +
                                progress.flaggedBy.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    progress.notes.forEach { note ->
                        Text(
                            "“${note.text}” — ${note.author} (${note.authorSite})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    reminder?.let {
                        Text(
                            "Reminder " + formatDueIn(it.dueAtEpochMillis, nowMillis) +
                                if (it.repeatMinutes > 0) ", repeating every ${it.repeatMinutes} min" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (progress.status == ItemStatus.Pending) {
                    TextButton(onClick = onStart) { Text("Start") }
                }
                TextButton(onClick = { onPrompt(ItemPrompt.Note(item.itemId)) }) { Text("Note") }
                if (progress.flagged) {
                    TextButton(onClick = { onPrompt(ItemPrompt.ResolveFlag(item.itemId)) }) {
                        Text("Resolve flag")
                    }
                } else {
                    TextButton(onClick = { onPrompt(ItemPrompt.Flag(item.itemId)) }) { Text("Flag") }
                }
                if (reminder == null) {
                    TextButton(onClick = { onPrompt(ItemPrompt.Remind(item.itemId)) }) { Text("Remind me") }
                } else {
                    TextButton(onClick = onClearReminder) { Text("Clear reminder") }
                }
            }
        }
    }
}

@Composable
private fun TextPrompt(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Relative delays rather than a clock.
 *
 * The alarm is inexact anyway, and "in 20 minutes" is what somebody standing on a deck actually means.
 * A wall-clock picker would imply a precision the alarm does not have.
 */
@Composable
private fun ReminderPrompt(onConfirm: (minutes: Int, repeat: Boolean) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableIntStateOf(15) }
    var repeat by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remind me") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    REMINDER_CHOICES.forEach { choice ->
                        AssistChip(
                            onClick = { minutes = choice },
                            label = { Text(formatMinutes(choice)) },
                            enabled = choice != minutes,
                        )
                    }
                }
                Text("In ${formatMinutes(minutes)}", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { repeat = !repeat }) {
                    Text(if (repeat) "Repeating — tap to make it once" else "Once — tap to repeat")
                }
                Text(
                    "Reminders stay on this phone. Other sites see nothing until you tick the item.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(minutes, repeat) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val REMINDER_CHOICES = listOf(5, 15, 30, 60, 120)

private const val TIMELINE_ROWS = 20

private fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

private fun formatDueIn(dueAtEpochMillis: Long, nowMillis: Long): String {
    val remaining = dueAtEpochMillis - nowMillis
    if (remaining <= 0) return "due now"
    val minutes = (remaining / 60_000L).toInt()
    return "in " + formatMinutes(minutes.coerceAtLeast(1))
}

private fun TimelineKind.phrase(): String = when (this) {
    TimelineKind.Started -> "started"
    TimelineKind.Completed -> "completed"
    TimelineKind.Confirmed -> "confirmed"
    TimelineKind.Reverted -> "reopened"
    TimelineKind.NoteAdded -> "noted on"
    TimelineKind.Flagged -> "flagged"
    TimelineKind.FlagResolved -> "resolved the flag on"
    TimelineKind.ProcedureStarted -> "opened the procedure"
    TimelineKind.ProcedureCompleted -> "completed the procedure"
}
