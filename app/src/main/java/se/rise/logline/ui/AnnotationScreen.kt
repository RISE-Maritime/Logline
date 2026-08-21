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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Surface
import se.rise.logline.publish.formatElapsed
import androidx.compose.foundation.clickable
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
    /**
     * Timers still running, label to start time.
     *
     * Held by the publisher rather than this screen: a timer has to survive leaving the tab, and the
     * run's teardown is the only place that can close one somebody forgot. Pulled on the caller's
     * ticker like the marks beside it.
     */
    runningTimers: Map<String, Long>,
    /** Hold a button: begin timing. Publishes a `started` mark — see `SensorPublisher.startTimed`. */
    onStartTimed: (AnnotationButton) -> Boolean,
    /** Tap a running button: close it, publishing how long it ran. */
    onStopTimed: (AnnotationButton) -> Boolean,
    onNote: (String, AnnotationSeverity) -> Boolean,
    onEditButtons: () -> Unit,
    onStart: () -> Unit,
    /**
     * Whether the history shows every mark or one summary line.
     *
     * Hoisted by the caller like the live view's own preferences: a `remember` inside a route dies with
     * the composable when it is popped, and this is a choice somebody makes once rather than every time
     * they open the tab.
     */
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
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
        // No `actions`: editing the buttons moved down beside the buttons themselves. An icon in the
        // top bar was as far from the thing it edits as the screen allows, and it competed for the one
        // place a glance goes for the run's status.
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

            // **The history first, and collapsed by default.** It is what somebody opens this tab for
            // when they are not marking anything — "what have I logged, and did that press register" —
            // and it used to sit below the buttons and the note field, further still once the keyboard
            // was up.
            //
            // Collapsed is what lets it come first without contradicting the note at the top of this
            // file: the buttons are near the top because the moment being marked is passing while you
            // look for them, and an expanded list above them would put the one control this screen
            // exists for out of a thumb's reach. One header and one line does not.
            SectionHeader(
                title = "Marked this run",
                trailing = if (totalMarks > 0) totalMarks.toString() else null,
                // The count carries the warning, so a fault is visible without expanding — the same
                // shape `groupBadge` uses to say "look in here" without spelling it out.
                trailingColor = worstSeverity(recent)?.let { severityColor(it) },
                expanded = expanded,
                onToggle = { onExpandedChange(!expanded) },
            )
            if (expanded) {
                if (recent.isEmpty()) {
                    EmptyState(
                        title = if (running) "Nothing marked yet" else "Nothing marked",
                        body = if (running) {
                            "Tap a button below the moment something happens — it goes onto the bus " +
                                "and into the recording, stamped with the time of the press."
                        } else {
                            "Marks belong to a run. Start one and the buttons below become live."
                        },
                    )
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        // **Bounded, and scrolling inside that bound.** A run can make a hundred marks
                        // and the page scrolls as one column, so an uncapped list pushed everything
                        // below it — the note field and the buttons — arbitrarily far down. Half the
                        // screen reads a dozen marks and leaves the other half for the controls.
                        //
                        // The `heightIn` is what makes the nested scroll legal at all: a scrollable
                        // measured inside another scrollable is handed an infinite maximum height and
                        // throws. Bounding it first is the fix, not a nicety.
                        Column(
                            Modifier
                                .heightIn(max = markListMaxHeight())
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 4.dp)
                        ) {
                            recent.asReversed().forEachIndexed { index, annotation ->
                                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                                MarkRow(annotation, nowMillis)
                            }
                        }
                    }
                }
            } else {
                MarkSummary(recent.lastOrNull(), running, nowMillis)
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

            // **Last, which on a phone is nearest the thumb.** These were first on the theory that the
            // moment being marked is passing while you look for them — true, and the bottom of the
            // screen is where a thumb already is, so being last serves that argument better than being
            // first did. The note field is what you reach for deliberately; these are what you hit
            // without looking.
            if (buttons.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No buttons configured", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add one to mark a recurring event with a single tap. The typed note above " +
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
                        MarkButton(
                            button = button,
                            enabled = running,
                            startedAtMillis = runningTimers[button.label],
                            nowMillis = nowMillis,
                            onTap = {
                                val startedAt = runningTimers[button.label]
                                if (startedAt == null) {
                                    confirm(onMark(button), button.label)
                                } else {
                                    confirm(onStopTimed(button), "${button.label} ended")
                                }
                            },
                            onHold = { confirm(onStartTimed(button), "${button.label} started") },
                        )
                    }
                    // Last in the same row, so it wraps with them and sits at the end of the set it
                    // edits. **Outlined rather than filled**: it is the same size and shape as the
                    // marks beside it and must not be mistaken for one, and the fill is what every
                    // other square here uses to mean "this makes a mark".
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .size(MARK_BUTTON_SIDE)
                            .clickable(onClick = onEditButtons)
                            .readAsOneItem("Edit the quick buttons"),
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(8.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Edit",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * How tall the expanded mark list is allowed to get: **a third of the screen**.
 *
 * Half was the first attempt and showed nine rows on a Pixel 6 — more history than the question this
 * list answers ("what have I logged, did that register") needs, and it left the note field and the
 * buttons only just on screen. A third is six rows, which is a run's recent past rather than its whole
 * log, and the list scrolls for the rest.
 *
 * Measured rather than hand-tuned — `MAP_HEIGHT` on the live view is a fixed dp and there is a note in
 * TODO.md about exactly that being wrong on a device whose bars differ. `BoxWithConstraints` is no help
 * here because the page is a scrolling column, so the height it would report is infinite; the screen's
 * own dimension is the honest thing to take a fraction of.
 */
@Composable
private fun markListMaxHeight(): Dp = (LocalConfiguration.current.screenHeightDp * 0.33f).dp

/** Big enough for a thumb that is not looking at it, and square so the label has two lines to use. */
private val MARK_BUTTON_SIDE = 104.dp

/**
 * One quick mark: tap for the instant, hold to time an interval.
 *
 * A square rather than the pill it was, because this is the control the screen exists for and a pill
 * sized to its text is as small as its shortest label. Three fit across a Pixel 6 at the page's own
 * padding.
 *
 * **Holding arms a timer and the face starts counting**, which is what turns a mark from an instant into
 * an interval — a manoeuvre or an engine run is not a point in time, and recording it as one loses the
 * half that matters. A tap then closes it. The haptic on the long press is not a nicety: the whole
 * purpose is arming it without looking, and the snackbar that follows confirms it a moment later than
 * the finger needs.
 *
 * While it runs the elapsed figure is the state — a ticking clock cannot be mistaken for anything else —
 * and the outline is reinforcement, not the signal.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkButton(
    button: AnnotationButton,
    enabled: Boolean,
    /** Non-null while this button is timing something. */
    startedAtMillis: Long?,
    nowMillis: Long,
    onTap: () -> Unit,
    onHold: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val timing = startedAtMillis != null
    val fill = severityColor(button.severity)
    Surface(
        color = if (enabled) fill else fill.copy(alpha = 0.30f),
        contentColor = if (enabled) contentColorFor(fill) else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = if (timing) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
        modifier = Modifier
            .size(MARK_BUTTON_SIDE)
            .combinedClickable(
                enabled = enabled,
                onClick = onTap,
                onLongClick = {
                    // Only when there is nothing to close: holding a running button would otherwise
                    // read as "restart", which would move the origin and shorten the interval.
                    if (!timing) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onHold()
                    }
                },
            )
            .readAsOneItem(
                when {
                    !enabled -> "${button.label}, nothing is running"
                    timing -> "${button.label}, running for " +
                        formatAge(startedAtMillis!!, nowMillis) + ", tap to stop"
                    else -> "${button.label}, tap to mark, hold to time"
                }
            ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                button.label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (timing) {
                Text(
                    formatElapsed(nowMillis - startedAtMillis!!),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * The compact form of the list: the newest mark, on one line.
 *
 * Enough to answer the question the list is there for — did that register, and what was it — without
 * the buttons moving down the screen. The snackbar already confirms the press itself; this is what
 * survives it.
 *
 * A burst of marks leaves this naming only the last of them. The count in the header beside it is what
 * says there were others.
 */
@Composable
private fun MarkSummary(newest: Annotation?, running: Boolean, nowMillis: Long) {
    if (newest == null) {
        // One line rather than the `EmptyState` card: the explanation of what a mark is belongs on the
        // expanded path, where there is room for it.
        Text(
            if (running) "Nothing marked yet" else "Nothing marked",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val time = clock.format(Date(newest.atEpochMillis))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .readAsOneItem(
                "Latest mark: ${newest.message}, $time, ${formatAge(newest.atEpochMillis, nowMillis)}"
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${newest.message} · $time · ${formatAge(newest.atEpochMillis, nowMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Same exception-based rule the rows follow: only a non-routine mark is coloured, so colour
        // still means "look at this".
        if (newest.severity != AnnotationSeverity.Info) {
            Text(
                newest.severity.label,
                style = MaterialTheme.typography.labelMedium,
                color = severityColor(newest.severity),
            )
        }
    }
}

/**
 * The most serious severity in the list, or null when everything in it is routine.
 *
 * What colours the collapsed header's count. Reads the whole list rather than the newest mark, because
 * a fault five marks ago is still the thing somebody must not miss — and collapsed, the newest line is
 * all they would otherwise see.
 */
private fun worstSeverity(marks: List<Annotation>): AnnotationSeverity? = when {
    marks.any { it.severity == AnnotationSeverity.Error } -> AnnotationSeverity.Error
    marks.any { it.severity == AnnotationSeverity.Warning } -> AnnotationSeverity.Warning
    else -> null
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
