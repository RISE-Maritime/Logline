package se.rise.logline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import se.rise.logline.config.AnnotationButton
import se.rise.logline.config.AnnotationSeverity
import se.rise.logline.config.isValidCategory
import se.rise.logline.ui.components.ConfirmDialog
import se.rise.logline.ui.components.FormActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import se.rise.logline.ui.components.InfoDialog
import se.rise.logline.ui.components.ScreenScaffold

/**
 * Editing the marker buttons.
 *
 * A plain form, and deliberately not part of Settings: saving Settings restarts the publisher so
 * publishers are redeclared with a new key or QoS, and none of that applies to a list of button
 * labels. Somebody adding a button halfway through a passage must not lose the run to do it.
 *
 * The category field is the one that needs explaining, so it says what it is for rather than only what
 * it will not accept — it becomes the `name` on the wire, which Foxglove turns into one filter toggle
 * per distinct value.
 */
@Composable
fun AnnotationButtonsScreen(
    initial: List<AnnotationButton>,
    onSave: (List<AnnotationButton>) -> Unit,
    onCancel: () -> Unit,
) {
    val labels = remember { mutableStateListOf<String>().apply { addAll(initial.map { it.label }) } }
    val severities = remember {
        mutableStateListOf<AnnotationSeverity>().apply { addAll(initial.map { it.severity }) }
    }
    val categories = remember {
        mutableStateListOf<String>().apply { addAll(initial.map { it.category }) }
    }
    var confirmDiscard by remember { mutableStateOf(false) }

    val edited = labels.indices.map { i ->
        AnnotationButton(labels[i].trim(), severities[i], categories[i].trim())
    }
    val dirty = edited != initial
    val saveable = edited.all { it.label.isNotEmpty() && isValidCategory(it.category) }

    val leave = { if (dirty) confirmDiscard = true else onCancel() }
    BackHandler(enabled = true) { leave() }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            body = "The buttons will go back to how they were.",
            confirmLabel = "Discard",
            onConfirm = {
                confirmDiscard = false
                onCancel()
            },
            onDismiss = { confirmDiscard = false },
        )
    }

    var showHelp by remember { mutableStateOf(false) }

    if (showHelp) {
        InfoDialog(
            title = "Annotation buttons",
            body = "Each button publishes one annotation on `log_message`.\n\n" +
                "Severity and category are what a reader filters by. In Foxglove's Log panel the " +
                "category appears as its own toggle, so a few shared categories are far more use than " +
                "one per button — a unique category each turns the filter into a legend.\n\n" +
                "Severity maps onto the log level, which the panel filters on regardless of anything " +
                "else, so it is the coarse control somebody scrubbing a six-hour recording reaches for.",
            onDismiss = { showHelp = false },
        )
    }

    ScreenScaffold(
        title = "Annotation buttons",
        onBack = leave,
        // No SectionHeader on this screen to carry an ⓘ, so it goes in the bar — the shape the live
        // view uses for the same job.
        actions = {
            IconButton(onClick = { showHelp = true }) {
                Icon(Icons.Default.Info, contentDescription = "About annotation buttons")
            }
        },
        bottomBar = {
            FormActions(
                onSave = { onSave(edited) },
                onCancel = leave,
                saveEnabled = saveable,
                hint = if (saveable) null else "Every button needs a label and a category.",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            labels.indices.forEach { index ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = labels[index],
                            onValueChange = { labels[index] = it.replace('\n', ' ').replace('\t', ' ') },
                            label = { Text("Label") },
                            singleLine = true,
                            isError = labels[index].isBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                            ),
                        )
                        SeverityField(
                            selected = severities[index],
                            onSelect = { severities[index] = it },
                        )
                        OutlinedTextField(
                            value = categories[index],
                            onValueChange = { categories[index] = it.trim().lowercase() },
                            label = { Text("Category") },
                            singleLine = true,
                            isError = !isValidCategory(categories[index].trim()),
                            supportingText = {
                                Text("Lower case, digits and underscores. Groups the mark for readers.")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    labels.removeAt(index)
                                    severities.removeAt(index)
                                    categories.removeAt(index)
                                },
                            ) { Text("Remove") }
                        }
                    }
                }
            }

            TextButton(
                onClick = {
                    labels.add("")
                    severities.add(AnnotationSeverity.Info)
                    categories.add("note")
                },
            ) { Text("Add a button") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeverityField(selected: AnnotationSeverity, onSelect: (AnnotationSeverity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Severity") },
            // Its only explanation used to be the paragraph now behind the ⓘ, and unlike Category it
            // had no supporting text of its own — so hiding that would have left it unlabelled.
            supportingText = { Text("How a reader filters by importance.") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AnnotationSeverity.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
