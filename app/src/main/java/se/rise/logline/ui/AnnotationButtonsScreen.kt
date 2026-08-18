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

    ScreenScaffold(
        title = "Annotation buttons",
        onBack = leave,
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
            Text(
                "Each button publishes one annotation on `log_message`. The severity and the category " +
                    "are what a reader filters by — in Foxglove's Log panel the category appears as its " +
                    "own toggle, so a few shared categories are more use than one per button.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
