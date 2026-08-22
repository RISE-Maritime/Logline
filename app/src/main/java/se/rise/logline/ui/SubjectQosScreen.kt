package se.rise.logline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import se.rise.logline.ui.components.InfoDialog
import se.rise.logline.ui.components.NavRow
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.sensors.slowerOf
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
    /**
     * What the *bus* was **asked for**, unclamped — `Settings.requestedPublishRate`.
     *
     * Not `publishRate`, which is the clamped result: a field bound to that cannot be typed into,
     * because 10 Hz against a 1 Hz ceiling redraws as 1.0 and saving then stores the clamp, so opening
     * the screen would destroy the request.
     */
    rate: SensorRate,
    /**
     * The fastest this subject may go on the wire, and what will be published if [rate] exceeds it.
     *
     * For a subject with its own listener that is its record rate; for one riding another's samples it
     * is the owner's publish rate. See `Settings.publishCeiling`.
     */
    publishCeiling: SensorRate,
    /**
     * Whether a publish rate is stored for this subject at all.
     *
     * Only meaningful for a subject that rides another: absent means it follows the owner, which is a
     * state the screen offers as a choice rather than leaving as a hidden fallback.
     */
    rateIsOverridden: Boolean = false,
    /** What the *file* is asked for. Equal to [rate] on subjects where the two cannot differ. */
    recordRate: SensorRate,
    /**
     * Whether this subject can record and publish at different rates at all.
     *
     * False for a polled subject, a chunk length, a capture interval and an on-change sensor — see
     * `Settings.ratesCanDiffer`. Those get one control, because two would imply a choice that does not
     * exist.
     */
    ratesCanDiffer: Boolean,
    capabilities: SensorCapabilities,
    /** The fastest this source can go and where that number came from. See `rateCeilings()`. */
    ceiling: RateCeiling?,
    /**
     * The human name of the subject whose rate governs this one, where one does — `Position`, not
     * `location_fix`. Resolved by the caller with `rateOwnerEntry()`, which matches on `SourceKind`
     * as well as the subject because `location_fix` is published by two entries.
     */
    rateOwnerLabel: String? = null,
    /** Opens that subject's own page, so an inherited rate is one tap from where it is set. */
    onOpenRateOwner: (() -> Unit)? = null,
    achievedHz: Double?,
    /**
     * Quality of service, the publish rate, and the record rate.
     *
     * A **null** publish rate means "store nothing" — the subject follows the one it rides. That is
     * distinct from any rate it could carry, the same way an absent annotation-button list means
     * "never configured" rather than "empty".
     */
    onSave: (SubjectQos, SensorRate?, SensorRate) -> Unit,
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
    var showRateHelp by remember { mutableStateOf(false) }
    var showFrameHelp by remember { mutableStateOf(false) }
    // Derived here rather than at the section below, because the dialog above needs it too.
    val frame = PublishedSubject.forSubject(subject)?.let(::frameOf) ?: SensorFrame.None

    val edited = SubjectQos(priority, congestion, reliability, express)
    var useMaxRate by remember { mutableStateOf(rate is SensorRate.Max) }
    var rateText by remember {
        mutableStateOf(formatHz((rate as? SensorRate.Hz)?.hz ?: capabilities.maxRateHz ?: 50.0))
    }
    var useMaxRecord by remember { mutableStateOf(recordRate is SensorRate.Max) }
    var recordText by remember {
        mutableStateOf(formatHz((recordRate as? SensorRate.Hz)?.hz ?: capabilities.maxRateHz ?: 50.0))
    }
    val parsedRate = parseRateHz(rateText)
    val parsedRecord = parseRateHz(recordText)
    // **Never null, so Save is never disabled.** A field that will not parse falls back to what is
    // already stored and says so on the field itself — the alternative was a greyed-out Save with the
    // reason a scroll away, which reads as the screen being broken rather than as one field being wrong.
    val editedRate: SensorRate =
        if (useMaxRate) SensorRate.Max else parsedRate?.let { SensorRate.Hz(it) } ?: rate
    val editedRecord: SensorRate = when {
        !ratesCanDiffer -> editedRate
        useMaxRecord -> SensorRate.Max
        else -> parsedRecord?.let { SensorRate.Hz(it) } ?: recordRate
    }
    val rateOwner = PublishedSubject.forSubject(subject)?.rateOwner
    // A derived subject with nothing stored follows the one it rides. Held as its own state rather
    // than inferred from the rate, because "0.2 Hz because I asked for it" and "0.2 Hz because that is
    // what Position is doing" are different settings that happen to read the same number.
    var followOwner by remember { mutableStateOf(rateOwner != null && !rateIsOverridden) }
    // Null is what `onSave` stores as "follow", and what makes the two states distinguishable.
    val savedRate: SensorRate? = if (followOwner) null else editedRate
    // What will actually go out, so the page can say when the number above it will not be honoured.
    val effectiveRate = slowerOf(editedRate, publishCeiling)
    val limited = !followOwner && effectiveRate != editedRate

    val dirty = edited != current ||
        (!followOwner && editedRate != rate) ||
        followOwner != (rateOwner != null && !rateIsOverridden) ||
        editedRecord != recordRate
    var confirmDiscard by remember { mutableStateOf(false) }
    // Where the discard was heading. Back leaves the screen; the rate-owner link goes to another
    // subject's page — both throw away the same unsaved edits, so both ask the same question rather
    // than one of them quietly taking the changes with it and losing them on the way.
    var afterDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }
    val leaveTo: (() -> Unit) -> Unit = { destination ->
        if (dirty) {
            afterDiscard = destination
            confirmDiscard = true
        } else {
            destination()
        }
    }
    val leave = { leaveTo(onCancel) }
    BackHandler(enabled = true) { leave() }

    if (showFrameHelp) {
        InfoDialog(
            title = "What the numbers are measured against",
            body = when (frame) {
                SensorFrame.WorldEnu ->
                    "This is the rotation from the phone's own axes to the world frame — x east, " +
                        "y north, z up. The axes it rotates from are the ones drawn below."
                SensorFrame.DeviceAngle ->
                    "An angle about one of the phone's own axes — pitch about +X, roll about +Y, yaw " +
                        "about +Z — not the vessel's. What it means for the boat is the platform " +
                        "calibration's frame transform."
                SensorFrame.Bearing ->
                    "Degrees clockwise from ${if (subject == Subjects.HEADING_TRUE_NORTH_DEG) "true" else "magnetic"} " +
                        "north, of the phone's +Y axis — the top edge, marked below. It is where the " +
                        "phone points, not where it is going; ${Subjects.COURSE_OVER_GROUND_DEG} is " +
                        "the latter, and on the water the two differ."
                else -> null
            },
            // The card itself, not a copy of it — the same one the live view shows.
            content = { AxisReferenceCard(compact = true) },
            onDismiss = { showFrameHelp = false },
        )
    }
    if (showRateHelp) {
        InfoDialog(
            title = "Sampling rate",
            body = buildString {
                append(
                    "Four numbers, answering four different questions. Reading one as an answer to " +
                        "another is the commonest confusion on this screen.\n\n"
                )
                append(
                    "Hardware is what the sensor advertises. It is not a hard cap: Android runs a " +
                        "shared sensor at the fastest rate any app asked for and delivers every sample " +
                        "to all of them, so another app on the phone can push this above the figure " +
                        "shown — and does.\n\n"
                )
                append(
                    "Recording is what the sensor is asked for and what reaches the file. The file is " +
                        "what analysis is run against, so it decides what exists at all.\n\n"
                )
                append(
                    "Publishing is what goes on the bus, which can only ever be a thinner copy of the " +
                        "recording — the bus is for watching a trial, not for analysing it. Asking to " +
                        "publish faster than you record changes nothing: there is no sample to send.\n\n"
                )
                append(
                    "Actual is measured over the run and averaged. A request is never a promise — the " +
                        "platform delivers what it can.\n\n"
                )
                append(
                    "Maximum is a zero delay rather than the advertised figure written out, which is " +
                        "why it can beat the number above it."
                )
                ceilingNote(ceiling)?.let { append("\n\n").append(it) }
            },
            onDismiss = { showRateHelp = false },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            body = "The rate and quality of service you changed here have not been saved.",
            confirmLabel = "Discard",
            onConfirm = {
                confirmDiscard = false
                val destination = afterDiscard ?: onCancel
                afterDiscard = null
                destination()
            },
            onDismiss = { confirmDiscard = false; afterDiscard = null },
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
                onSave = { onSave(edited, savedRate, editedRecord) },
                onCancel = leave,
                // Always. A rate that will not parse keeps its stored value rather than blocking every
                // other setting on the screen behind it.
                saveEnabled = true,
                hint = when {
                    !useMaxRate && parsedRate == null ->
                        "The publish rate is not a number — saving keeps its current value."
                    ratesCanDiffer && !useMaxRecord && parsedRecord == null ->
                        "The record rate is not a number — saving keeps its current value."
                    dirty -> "Saving restarts the run, which starts a new recording file."
                    else -> null
                },
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
            // The frame this subject's numbers are measured in — a header and an ⓘ, where it used to
            // be a header, a paragraph and a diagram inline. `LiveScreen` already puts this same card
            // behind an ⓘ; this is the other half of that.
            if (frame != SensorFrame.None) {
                SectionHeader(
                    "What the numbers are measured against",
                    trailing = frameSummary(frame),
                    onInfo = { showFrameHelp = true },
                )
            }

            // An event-driven subject has no stream to sample — `log_message` is published when a
            // person presses a button — so it gets the three-number card and no dial. A control there
            // would silently do nothing; saying it has no rate is the part that used to be missing,
            // since dropping the section entirely left the one source that states nothing at all. The
            // QoS controls above still apply: how it travels is a real question for it, how often it
            // is produced is not.
            val eventDriven = PublishedSubject.forSubject(subject)?.eventDriven == true
            run {
                SectionHeader("Sampling rate", onInfo = { showRateHelp = true })

                // The three numbers, before any control that changes one of them. They answer three
                // different questions — what the source could give, what this phone asked for, what is
                // actually going out — and the commonest confusion on this screen was reading one of
                // them as an answer to another.
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RateFact(
                            label = "Hardware",
                            value = ceiling?.let { c ->
                                c.hz?.let { "${formatHz(it)} Hz" } ?: "reports on change"
                            } ?: "not stated",
                            // No note: what "advertised" means, and that it can be exceeded, is the
                            // kind of thing you read once — it lives behind the ⓘ now.
                            note = null,
                        )
                        RateFact(
                            label = "Recording",
                            value = when {
                                eventDriven -> "not applicable"
                                recordRate is SensorRate.Max -> "maximum"
                                else -> "${formatHz((recordRate as SensorRate.Hz).hz)} Hz"
                            },
                            // Only what this particular subject's state makes true. "What the sensor
                            // is asked for" is the same sentence on every subject, every time — that is
                            // documentation, and it belongs behind the ⓘ.
                            note = rateOwnerLabel?.let { "Inherited from $it" },
                        )
                        RateFact(
                            label = "Publishing",
                            // **The effective rate, not the typed one.** This row is the answer to
                            // "what is going out", so on a subject held down by the one it rides it has
                            // to show the ceiling — the request is in the field below, where it can be
                            // edited, and the warning beside it explains the gap.
                            value = when {
                                eventDriven -> "on each mark"
                                rateOwner != null && followOwner -> rateLabel(publishCeiling)
                                else -> rateLabel(effectiveRate)
                            },
                            // Kept only where the two rates *differ from each other*, because that is
                            // the one thing the pair of figures does not make obvious on its own.
                            note = when {
                                rateOwnerLabel == null ->
                                    if (ratesCanDiffer && editedRecord != editedRate) {
                                        "Thinned from the recording"
                                    } else {
                                        null
                                    }
                                followOwner -> "Following $rateOwnerLabel"
                                limited -> "Limited by $rateOwnerLabel"
                                else -> "Thinned from $rateOwnerLabel"
                            },
                        )
                        RateFact(
                            label = "Actual",
                            value = achievedHz?.let { "${formatHz(it)} Hz" }
                                ?: if (eventDriven) "nothing marked" else "not publishing",
                            // Only when there is no figure: a blank needs explaining, a number does not.
                            note = if (achievedHz != null) {
                                null
                            } else if (eventDriven) {
                                "Marks are counted, not timed"
                            } else {
                                "Start a run to measure it"
                            },
                        )
                    }
                }

                // Some subjects ride another subject's sample stream — speed and course come off the same
                // Location callback as location_fix, and the battery scalars off one poll. Showing them a
                // rate control would be showing a control that silently does nothing, so say where it lives.
                if (eventDriven) {
                    // No control at all, and nothing more to say than the card already says.
                } else if (rateOwner != null) {
                    val ownerName = rateOwnerLabel ?: rateOwner
                    // **A publish rate of its own, capped by the one it rides.** These subjects used to
                    // get no control at all — the card below was the whole section — which pinned a
                    // declination that moves over a day's sailing to whatever the fix was publishing
                    // at. There is nothing to set on the *record* side, though: one listener serves the
                    // whole group, so that stays inherited and is stated as a fact above.
                    RateControl(
                        // "Maximum" would be a lie here. The ceiling is another subject's configured
                        // rate, not the hardware's, so the off-state is named after what it does.
                        title = "Follow $ownerName",
                        detail = "Publish every sample $ownerName does.",
                        fieldLabel = "Publish rate (Hz)",
                        useMax = followOwner,
                        onUseMaxChange = { followOwner = it },
                        text = rateText,
                        onTextChange = { rateText = it },
                        parsed = parsedRate,
                    )
                    // State, not documentation, so it stays on the page: it is the only thing saying
                    // why the number just typed will not be what goes out. Shown only while it binds.
                    if (limited) {
                        StatusLine(
                            text = "Limited to ${rateLabel(effectiveRate)} by $ownerName",
                            tone = StatusTone.Warning,
                            detail = "A subject cannot be published faster than the one it is " +
                                "derived from. Raise $ownerName first.",
                        )
                    }
                    Card(Modifier.fillMaxWidth()) {
                        NavRow(
                            title = "Rate is set on $ownerName",
                            subtitle = "Published from the same samples as $rateOwner",
                            // Through the same guard a back gesture uses: this leaves the screen just
                            // as finally, and unsaved QoS edits would go with it.
                            onClick = { onOpenRateOwner?.let { leaveTo(it) } },
                        )
                    }
                } else {
                    // The file first, because it decides what exists to publish at all.
                    if (ratesCanDiffer) {
                        RateControl(
                            title = "Record at maximum",
                            detail = "Everything the hardware gives, into the file.",
                            fieldLabel = "Record rate (Hz)",
                            useMax = useMaxRecord,
                            onUseMaxChange = { useMaxRecord = it },
                            text = recordText,
                            onTextChange = { recordText = it },
                            parsed = parsedRecord,
                        )
                    }
                    RateControl(
                        title = if (ratesCanDiffer) "Publish at maximum" else "Maximum",
                        detail = if (ratesCanDiffer) {
                            "Every recorded sample, unthinned, onto the bus."
                        } else {
                            "Everything the hardware gives."
                        },
                        fieldLabel = if (ratesCanDiffer) "Publish rate (Hz)" else "Requested rate (Hz)",
                        useMax = useMaxRate,
                        onUseMaxChange = { useMaxRate = it },
                        text = rateText,
                        onTextChange = { rateText = it },
                        parsed = parsedRate,
                    )
                    // Said where the choice is made rather than left to be discovered from the plots.
                    if (ratesCanDiffer && !useMaxRate && parsedRate != null) {
                        val recordHz = if (useMaxRecord) capabilities.maxRateHz else parsedRecord
                        if (recordHz != null && parsedRate > recordHz) {
                            StatusLine(
                                text = "Faster than the recording",
                                tone = StatusTone.Warning,
                                detail = "There is no sample to send between recordings, so this " +
                                    "publishes at ${formatHz(recordHz)} Hz.",
                            )
                        }
                    }
                    // What the card above does not say: that `Maximum` is a zero delay rather than the
                    // advertised figure, and that the two differ in practice.
                    capabilities.name?.let {
                        Text(
                            "$it${capabilities.vendor?.let { v -> " · $v" } ?: ""}",
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

/** One of the three rate numbers, with the sentence that says what kind of number it is. */
/**
 * One rate: a maximum switch, and a figure when it is off.
 *
 * Shared by the record and publish controls so the two cannot drift into looking like different kinds
 * of setting — they are the same question asked about the file and about the bus.
 *
 * The field keeps its text when it will not parse rather than being reverted under the cursor; what
 * that costs is stated on the field and again on the Save hint, because the value is silently left
 * alone rather than rejected.
 */
/** A word for the frame, so the collapsed header still says which one this subject uses. */
private fun frameSummary(frame: SensorFrame): String = when (frame) {
    SensorFrame.WorldEnu -> "world frame"
    SensorFrame.DeviceAngle -> "phone axes"
    SensorFrame.Bearing -> "from north"
    else -> ""
}

@Composable
private fun RateControl(
    title: String,
    detail: String,
    fieldLabel: String,
    useMax: Boolean,
    onUseMaxChange: (Boolean) -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    parsed: Double?,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = useMax, onCheckedChange = onUseMaxChange)
    }
    // **Always drawn, disabled while the switch is on**, rather than appearing only when it is off.
    // Hidden, it made a rate look unsettable: the record switch defaults to on, so its field was never
    // there to be found, and the only visible number on the screen was the publish one — which read as
    // "recording can only be maximum". A greyed field says the setting exists and is being overridden.
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        enabled = !useMax,
        label = { Text(fieldLabel) },
        singleLine = true,
        isError = !useMax && parsed == null,
        supportingText = when {
            useMax -> {
                { Text("Ignored while the switch above is on.") }
            }
            parsed == null -> {
                { Text("Enter a rate greater than 0 Hz — saving keeps the current value.") }
            }
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A rate as the page says it — "maximum" or "1.0 Hz". One spelling, used everywhere on this screen. */
private fun rateLabel(rate: SensorRate): String = when (rate) {
    SensorRate.Max -> "maximum"
    is SensorRate.Hz -> "${formatHz(rate.hz)} Hz"
}

@Composable
private fun RateFact(label: String, value: String, note: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodyLarge)
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Who is doing the limiting, which the number alone cannot say.
 *
 * `max 2` on the time-lapse row is this app's own half-second floor, not a camera that cannot go
 * faster — and reading it as the latter is exactly the wrong conclusion to draw from a ceiling.
 */
private fun ceilingNote(ceiling: RateCeiling?): String = when (ceiling?.basis) {
    CeilingBasis.Reported ->
        "Advertised by the sensor. Not a hard cap — Android delivers to every client at the " +
            "fastest rate any of them asked for, so this can be exceeded."
    CeilingBasis.Imposed -> "A floor this app holds the loop to, not a limit of the hardware"
    CeilingBasis.Estimated ->
        "An estimate: the fused location provider publishes no rate limit, so what arrives " +
            "depends on the GNSS chipset and the sky view"
    CeilingBasis.OnChange -> "An on-change sensor: it reports when the reading moves, so there is no rate"
    null -> "This source states no limit"
}
