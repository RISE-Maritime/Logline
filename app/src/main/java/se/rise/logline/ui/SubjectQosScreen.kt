package se.rise.logline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.zenoh.qos.CongestionControl
import io.zenoh.qos.Priority
import io.zenoh.qos.Reliability
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SubjectQos
import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.matchingProfile
import se.rise.logline.keelson.policyQosForSubject
import se.rise.logline.keelson.toSubjectQos
import se.rise.logline.sensors.SensorCapabilities
import se.rise.logline.sensors.SensorRate
import se.rise.logline.sensors.parseRateHz
import se.rise.logline.ui.components.ConfirmDialog
import se.rise.logline.ui.components.FormActions
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone

/**
 * Everything configurable about one subject: how fast it samples, and how it travels.
 *
 * Opens pre-filled with what the subject currently publishes with — the `qos.yaml` policy unless it
 * has already been overridden — so every field starts from a working value rather than blank. The rate
 * is the thing anyone comes here to change; the four QoS controls are behind a disclosure, because
 * changing them makes this phone behave unlike every other connector and that should take a deliberate
 * tap.
 */
@Composable
fun SubjectQosScreen(
    subject: String,
    /** The link this publisher covers, where one subject has more than one. Null otherwise. */
    sourceId: String? = null,
    current: SubjectQos,
    isOverridden: Boolean,
    rate: SensorRate,
    capabilities: SensorCapabilities,
    achievedHz: Double?,
    onSave: (SubjectQos, SensorRate) -> Unit,
    onResetToPolicy: () -> Unit,
    onCancel: () -> Unit,
) {
    val policy = policyQosForSubject(subject)
    var priority by remember { mutableStateOf(current.priority) }
    var congestion by remember { mutableStateOf(current.congestionControl) }
    var reliability by remember { mutableStateOf(current.reliability) }
    var express by remember { mutableStateOf(current.express) }
    // Open when there is already an override, so an existing one is never hidden behind a tap.
    var showQos by remember { mutableStateOf(isOverridden) }

    val edited = SubjectQos(priority, congestion, reliability, express)
    var useMaxRate by remember { mutableStateOf(rate is SensorRate.Max) }
    var rateText by remember {
        mutableStateOf(formatHz((rate as? SensorRate.Hz)?.hz ?: capabilities.maxRateHz ?: 50.0))
    }
    val parsedRate = parseRateHz(rateText)
    val editedRate: SensorRate? = if (useMaxRate) SensorRate.Max else parsedRate?.let { SensorRate.Hz(it) }
    val rateOwner = PublishedSubject.forSubject(subject)?.rateOwner

    val dirty = edited != current || (editedRate != null && editedRate != rate)
    var confirmDiscard by remember { mutableStateOf(false) }
    val leave = { if (dirty) confirmDiscard = true else onCancel() }
    BackHandler(enabled = true) { leave() }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            body = "The rate and quality of service you changed here have not been saved.",
            confirmLabel = "Discard",
            onConfirm = { confirmDiscard = false; onCancel() },
            onDismiss = { confirmDiscard = false },
        )
    }

    val label = PublishedSubject.forSubject(subject)?.let(::labelOf)

    ScreenScaffold(
        // The human name in the bar, the wire name in the body below: this is the screen where the
        // subject string actually matters, because it is what a subscriber has to spell.
        title = label?.name ?: subject,
        onBack = leave,
        bottomBar = {
            FormActions(
                onSave = { editedRate?.let { onSave(edited, it) } },
                onCancel = leave,
                saveEnabled = editedRate != null,
                hint = if (editedRate == null) "Enter a rate greater than 0 Hz to save." else null,
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
                buildString {
                    append(subject)
                    label?.unit?.let { append("  ·  $it") }
                    sourceId?.let { append("  ·  link $it") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusLine(
                        text = if (isOverridden) "Overridden for this phone" else "Following keelson qos.yaml",
                        tone = if (isOverridden) StatusTone.Warning else StatusTone.Neutral,
                        detail = "Policy is ${policy.name.lowercase()} — " +
                            "${policy.priority.name.lowercase()}, " +
                            "${policy.reliability.name.lowercase().replace('_', '-')}, " +
                            if (policy.express) "immediate." else "batched.",
                    )
                    // Beside the badge it undoes, rather than at the bottom of the screen.
                    if (isOverridden || edited != policy.toSubjectQos()) {
                        TextButton(onClick = onResetToPolicy) { Text("Reset to qos.yaml policy") }
                    }
                }
            }

            // Only for the subjects that have a direction at all — on a scalar it would be noise.
            val frame = PublishedSubject.forSubject(subject)?.let(::frameOf) ?: SensorFrame.None
            if (frame != SensorFrame.None) {
                SectionHeader("What the numbers are measured against")
                when (frame) {
                    SensorFrame.WorldEnu -> Text(
                        "This is the rotation from the phone's own axes to the world frame — x east, " +
                            "y north, z up. The axes it rotates *from* are these:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SensorFrame.Bearing -> Text(
                        "Degrees clockwise from ${if (subject == Subjects.HEADING_TRUE_NORTH_DEG) "true" else "magnetic"} " +
                            "north, of the phone's +Y axis — the top edge, marked below. It is where the " +
                            "phone points, not where it is going; ${Subjects.COURSE_OVER_GROUND_DEG} is " +
                            "the latter, and on the water the two differ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Unit
                }
                AxisReferenceCard(compact = true)
            }

            // An event-driven subject has no stream to sample — `log_message` is published when a
            // person presses a button — so the whole section is dropped rather than shown with a dial
            // that would do nothing. The QoS controls above still apply: how it travels is a real
            // question for it, how often it is produced is not.
            val eventDriven = PublishedSubject.forSubject(subject)?.eventDriven == true
            if (!eventDriven) {
                SectionHeader("Sampling rate")

                // Some subjects ride another subject's sample stream — speed and course come off the same
                // Location callback as location_fix, and the battery scalars off one poll. Showing them a
                // rate control would be showing a control that silently does nothing, so say where it lives.
                if (rateOwner != null) {
                    Text(
                        "Published from the same reading as $rateOwner, so it shares that subject's rate. " +
                            "Change it there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    achievedHz?.let {
                        Text(
                            "Currently achieving ${formatHz(it)} Hz.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Maximum", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Ask for everything the hardware will give, with no requested rate at all.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = useMaxRate, onCheckedChange = { useMaxRate = it })
                    }

                    if (!useMaxRate) {
                        OutlinedTextField(
                            value = rateText,
                            onValueChange = { rateText = it },
                            label = { Text("Requested rate (Hz)") },
                            singleLine = true,
                            isError = parsedRate == null,
                            supportingText = if (parsedRate == null) {
                                { Text("Enter a rate greater than 0 Hz.") }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        when {
                            useMaxRate && capabilities.maxRateHz != null ->
                                "The hardware is the only limit. This sensor advertises " +
                                    "${formatHz(capabilities.maxRateHz)} Hz, though it may deliver more — " +
                                    "asking for 400 Hz on this device yields around 442 Hz."
                            useMaxRate ->
                                "The hardware is the only limit. The fused location provider publishes " +
                                    "none, so what you get depends on the GNSS chipset and the sky view."
                            capabilities.maxRateHz != null ->
                                "This sensor supports up to ${formatHz(capabilities.maxRateHz)} Hz " +
                                    "(min delay ${capabilities.minDelayUs} µs)."
                            else ->
                                "The fused location provider publishes no rate limit — what you get " +
                                    "depends on the GNSS chipset and the sky view."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    capabilities.name?.let {
                        Text(
                            "$it${capabilities.vendor?.let { v -> " · $v" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    achievedHz?.let {
                        Text(
                            "Currently achieving ${formatHz(it)} Hz — the rate is a request, and the " +
                                "hardware delivers what it can.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val max = capabilities.maxRateHz
                    if (!useMaxRate && parsedRate != null && max != null && parsedRate > max) {
                        StatusLine(
                            text = "Above this sensor's advertised maximum",
                            tone = StatusTone.Warning,
                            detail = "Android will simply deliver slower.",
                        )
                    }
                    if (useMaxRate || (parsedRate != null && parsedRate > 200.0)) {
                        StatusLine(
                            text = "Very high rate",
                            tone = StatusTone.Warning,
                            detail = "Above 200 Hz the publish path and battery become the limit before " +
                                "the sensor does; the IMU subjects together at this rate is well over a " +
                                "thousand messages a second.",
                        )
                    }
                }
            }

            // Progressive disclosure: the rate is the common edit, this is the rare one.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showQos = !showQos }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("How this travels", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (showQos) "Priority, congestion, reliability, express" else "Advanced — tap to open",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (showQos) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (showQos) "Hide quality of service" else "Show quality of service",
                )
            }

            if (showQos) {
                Text(
                    "Changing these makes this subject travel differently from this phone than from any " +
                        "other connector publishing it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                EnumField(
                    label = "Priority",
                    options = Priority.entries,
                    selected = priority,
                    describe = { it.name.lowercase() },
                    onSelect = { priority = it },
                )
                Text(
                    "Higher priority is served first when the link is congested.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                EnumField(
                    label = "Congestion control",
                    options = CongestionControl.entries,
                    selected = congestion,
                    describe = { if (it == CongestionControl.DROP) "drop" else "block" },
                    onSelect = { congestion = it },
                )
                if (congestion == CongestionControl.BLOCK) {
                    StatusLine(
                        text = "BLOCK applies back-pressure",
                        tone = StatusTone.Warning,
                        detail = "If the egress queue fills, publishing waits instead of shedding " +
                            "samples. Every profile in qos.yaml uses DROP for this reason.",
                    )
                } else {
                    Text(
                        "DROP sheds samples on a full egress queue rather than stalling the publisher.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                EnumField(
                    label = "Reliability",
                    options = Reliability.entries,
                    selected = reliability,
                    describe = { it.name.lowercase().replace('_', '-') },
                    onSelect = { reliability = it },
                )
                Text(
                    "Reliable retransmits lost fragments; best-effort does not, which suits data " +
                        "superseded by the next sample. Hop-by-hop, not an end-to-end delivery guarantee.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Express", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Flush immediately for lowest latency, instead of batching for throughput.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = express, onCheckedChange = { express = it })
                }

                edited.matchingProfile()?.let {
                    Text(
                        "This matches the ${it.name.lowercase()} profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumField(
    label: String,
    options: List<T>,
    selected: T,
    describe: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = describe(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(describe(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * A rate a human can read: 50, 0.2, 415.97 — never 415.97337770382694.
 *
 * Two decimals is well past what any of these numbers mean; a sensor's advertised maximum and a
 * measured average are both approximations.
 */
private fun formatHz(hz: Double): String =
    if (hz == hz.toLong().toDouble()) hz.toLong().toString() else "%.2f".fmt(hz).trimEnd('0').trimEnd('.')
