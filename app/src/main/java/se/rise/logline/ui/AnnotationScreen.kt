package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.rise.logline.config.AnnotationButton
import se.rise.logline.config.AnnotationSeverity
import se.rise.logline.publish.Annotation
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.EmptyState
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone
import se.rise.logline.ui.components.readAsOneItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Marking an event while it is happening.
 *
 * Designed for one tap with one hand on a moving boat: the buttons are the first thing on the screen
 * and they are large, because the moment being marked is passing while you look for them. Everything
 * else — the note field, the list of what has been marked — sits below.
 *
 * The list underneath is not a second copy of the recording. It is the answer to "did that register",
 * which is the only question somebody has after pressing a button that gives no other feedback.
 */
@Composable
fun AnnotationScreen(
    buttons: List<AnnotationButton>,
    running: Boolean,
    /** Newest last, as the store holds them; reversed for display. */
    recent: List<Annotation>,
    totalMarks: Int,
    nowMillis: Long,
    /**
     * Publish a mark. Returns false when there was nothing for it to land in — see
     * `SensorPublisher.mark`, which is where that decision is made.
     */
    onMark: (AnnotationButton) -> Boolean,
    onNote: (String, AnnotationSeverity) -> Boolean,
    onEditButtons: () -> Unit,
    onStart: () -> Unit,
    /** Null while this is a tab — see the note on `LiveScreen`. */
    onBack: (() -> Unit)? = null,
    /** The navigation bar, supplied by `MainActivity`. See `TopLevel`. */
    bottomBar: @Composable () -> Unit = {},
) {
    var note by remember { mutableStateOf("") }
    var noteSeverity by remember { mutableStateOf(AnnotationSeverity.Info) }
    // A mark's only other evidence is a row appearing in the list below, a second later, off the
    // bottom of the screen once the keyboard is up — which is no feedback at all at the moment the
    // button is pressed. `mark()` already returns whether the mark landed; this is what shows it.
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val confirm: (Boolean, String) -> Unit = { landed, what ->
        scope.launch {
            // Dismissed rather than queued: several marks in quick succession is the normal case, and
            // a backlog of stale confirmations would still be arriving after the moment had passed.
            snackbars.currentSnackbarData?.dismiss()
            snackbars.showSnackbar(
                message = if (landed) "Marked \u2014 $what" else "Not marked \u2014 nothing is running",
                duration = SnackbarDuration.Short,
            )
        }
    }

    ScreenScaffold(
        title = "Mark event",
        onBack = onBack,
        actions = {
            IconButton(onClick = onEditButtons) {
                Icon(Icons.Default.Edit, contentDescription = "Edit buttons")
            }
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!running) {
                // Said plainly rather than left to the greyed-out buttons: a disabled control explains
                // that it cannot be used, never why. There is no session and no open file for a mark
                // to land in, and the fix is one tap away, so it is offered here.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        StatusLine(
                            text = "Not logging",
                            tone = StatusTone.Warning,
                            detail = "A mark goes onto the bus and into the recording, so there has " +
                                "to be a run for it to join.",
                            action = { TextButton(onClick = onStart) { Text("Start") } },
                        )
                    }
                }
            }

            if (buttons.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No buttons configured", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add one to mark a recurring event with a single tap. A typed note below " +
                                "works without them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onEditButtons) { Text("Add a button") }
                    }
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    buttons.forEach { button ->
                        Button(
                            onClick = { confirm(onMark(button), button.label) },
                            enabled = running,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = severityColor(button.severity),
                            ),
                            contentPadding = ButtonDefaults.ContentPadding,
                        ) {
                            Text(button.label)
                        }
                    }
                }
            }

            SectionHeader(title = "Note")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.replace('\n', ' ').replace('\t', ' ') },
                        label = { Text("What happened") },
                        enabled = running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnnotationSeverity.entries.forEach { severity ->
                            FilterChip(
                                selected = noteSeverity == severity,
                                onClick = { noteSeverity = severity },
                                enabled = running,
                                label = { Text(severity.label) },
                            )
                        }
                    }
                    Button(
                        onClick = {
                            confirm(onNote(note, noteSeverity), "note")
                            note = ""
                        },
                        enabled = running && note.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Send note") }
                }
            }

            SectionHeader(
                title = "Marked this run",
                trailing = if (totalMarks > 0) totalMarks.toString() else null,
            )
            if (recent.isEmpty()) {
                EmptyState(
                    title = if (running) "Nothing marked yet" else "Nothing marked",
                    body = if (running) {
                        "Tap a button above the moment something happens — it goes onto the bus and " +
                            "into the recording, stamped with the time of the press."
                    } else {
                        "Marks belong to a run. Start one and the buttons above become live."
                    },
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        recent.asReversed().forEachIndexed { index, annotation ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                            MarkRow(annotation, nowMillis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkRow(annotation: Annotation, nowMillis: Long) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val time = clock.format(Date(annotation.atEpochMillis))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .readAsOneItem("$time, ${annotation.category}, ${annotation.message}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(annotation.message, style = MaterialTheme.typography.bodyLarge)
            Text(
                "$time · ${annotation.category} · ${formatAge(annotation.atEpochMillis, nowMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Only a non-routine mark is coloured, so colour still means "look at this" — the same
        // exception-based rule the subject rows follow.
        if (annotation.severity != AnnotationSeverity.Info) {
            Text(
                annotation.severity.label,
                style = MaterialTheme.typography.labelMedium,
                color = severityColor(annotation.severity),
            )
        }
    }
}

/**
 * Severity as colour, borrowed from [StatusTone] so a warning reads the same here as anywhere else:
 * amber tertiary for a warning, red for an error, and the ordinary primary for a routine mark.
 */
@Composable
private fun severityColor(severity: AnnotationSeverity): Color = when (severity) {
    AnnotationSeverity.Info -> MaterialTheme.colorScheme.primary
    AnnotationSeverity.Warning -> MaterialTheme.colorScheme.tertiary
    AnnotationSeverity.Error -> MaterialTheme.colorScheme.error
}
