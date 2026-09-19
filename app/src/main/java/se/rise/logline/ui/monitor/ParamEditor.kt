package se.rise.logline.ui.monitor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import se.rise.logline.monitor.MonitorCard
import se.rise.logline.monitor.MonitorSnapshot
import se.rise.logline.monitor.ParamSpec
import se.rise.logline.monitor.isKnownSubject
import se.rise.logline.monitor.shapeOf

/**
 * Every setting a card declares, rendered from its [ParamSpec]s — the one form for every card kind.
 *
 * Pickers offer what the entity has **actually published**, filtered to the shapes the input can
 * read, and stay editable so a subject can be named before it has been seen. That second part is the
 * point of a monitor set up ahead of a trial: the vessel is not publishing yet.
 */
@Composable
internal fun ParamEditor(card: MonitorCard, snapshot: MonitorSnapshot, onChange: (MonitorCard) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card.kind.specs.forEach { spec ->
            when (spec) {
                is ParamSpec.Subject -> {
                    val seen = snapshot.topics.map { it.topic.subject }.distinct()
                        .filter { shapeOf(it) in spec.shapes }
                    val options = (if (spec.optional) listOf("" to "None") else emptyList()) +
                        seen.map { it to it }
                    val value = card.string(spec.key)
                    PickerField(
                        label = spec.label,
                        value = value,
                        options = options,
                        editable = true,
                        supporting = when {
                            value.isEmpty() -> null
                            !isKnownSubject(value) -> "Not a subject keelson declares — nothing will decode."
                            value !in seen -> "Not seen from this entity yet."
                            else -> null
                        },
                        // Stored even when empty: removing the key would put the default back into
                        // the field mid-edit, the moment somebody cleared it to type another.
                        onChange = { onChange(card.copy(params = card.params + (spec.key to it))) },
                    )
                }
                is ParamSpec.Source -> {
                    val subject = spec.subject ?: spec.subjectKey?.let(card::string).orEmpty()
                    if (subject.isNotEmpty()) {
                        val sources = snapshot.topicsOf(subject).map { it.source }
                        PickerField(
                            label = spec.label,
                            value = card.string(spec.key),
                            options = listOf("" to "Auto — first found") + sources.map { it to it },
                            editable = true,
                            onChange = { onChange(card.with(spec.key, it)) },
                        )
                    }
                }
                is ParamSpec.Number -> {
                    // The raw text is what is stored, so a half-typed "0." survives until it is finished;
                    // `MonitorCard.number` parses and clamps on read. Blank is the kind's default.
                    val text = card.params[spec.key].orEmpty()
                    val parsed = text.trim().toDoubleOrNull()
                    val outOfRange = parsed != null && (parsed < spec.min || parsed > spec.max)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { onChange(card.with(spec.key, it.trim())) },
                        label = { Text(spec.label) },
                        placeholder = { spec.default?.let { Text(trimNumber(it)) } ?: Text("Auto") },
                        suffix = if (spec.unit.isNotEmpty()) ({ Text(spec.unit) }) else null,
                        isError = (text.isNotBlank() && parsed == null) || outOfRange,
                        supportingText = if (outOfRange) {
                            { Text("Limited to ${trimNumber(spec.min)}–${trimNumber(spec.max)}") }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is ParamSpec.Choice -> {
                    Text(spec.label, style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val selected = card.string(spec.key)
                        spec.options.forEach { (value, label) ->
                            FilterChip(
                                selected = selected == value,
                                onClick = { onChange(card.with(spec.key, value.ifEmpty { null })) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                is ParamSpec.Toggle -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = card.flag(spec.key),
                        onCheckedChange = { onChange(card.with(spec.key, it.toString())) },
                    )
                }
            }
        }
    }
}

/** `60`, not `60.0`; `0.75` stays `0.75`. */
internal fun trimNumber(v: Double): String =
    if (v == Math.floor(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString() else v.toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    editable: Boolean,
    onChange: (String) -> Unit,
    supporting: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (!editable) options.firstOrNull { it.first == value }?.second ?: value else value,
            onValueChange = { if (editable) onChange(it.trim()) },
            readOnly = !editable,
            label = { Text(label) },
            placeholder = { options.firstOrNull { it.first.isEmpty() }?.let { Text(it.second) } },
            supportingText = supporting?.let { { Text(it) } },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(
                    if (editable) ExposedDropdownMenuAnchorType.PrimaryEditable
                    else ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                ),
        )
        if (options.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (v, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onChange(v)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
