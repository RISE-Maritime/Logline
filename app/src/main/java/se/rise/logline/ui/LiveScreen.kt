package se.rise.logline.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import se.rise.logline.keelson.SourceKind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import se.rise.logline.publish.ConnectionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.FramePreview
import se.rise.logline.publish.LiveSnapshot
import se.rise.logline.publish.PublisherStatus
import se.rise.logline.publish.SampleWindow
import se.rise.logline.record.RecordingStatus
import se.rise.logline.sensors.achievedHz
import se.rise.logline.ui.components.InfoDialog
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone
import se.rise.logline.ui.components.readAsOneItem

/** The spans the window control offers. Bounded by what the store holds — see [LiveScreen]. */
val WINDOW_CHOICES = listOf(30 to "30 s", 120 to "2 min", 600 to "10 min")

/**
 * What is actually going on the bus, arranged to answer four questions in order: where am I, is the
 * data healthy, what is it doing now, is anything abnormal.
 *
 * The first screenful is the dashboard — map, position, the three readings, health per group. Below it
 * the plots, folded into the same sections the main screen uses, so a subject is in the same place on
 * both. Documentation that used to sit in the middle of the flow is behind the ⓘ buttons.
 *
 * Takes a plain [LiveSnapshot] and lambdas like every other screen, and the snapshot is *pulled* on a
 * ticker rather than pushed — the publish path runs at hundreds of samples a second and must never
 * drive recomposition.
 */
/** Tall enough to navigate by, against the 240dp it started at. */
private val MAP_HEIGHT = 400.dp


@Composable
fun LiveScreen(
    snapshot: LiveSnapshot,
    status: PublisherStatus,
    recording: RecordingStatus,
    running: Boolean,
    unavailableSubjects: Set<PublishedSubject>,
    disabledSubjects: Set<PublishedSubject>,
    followFix: Boolean,
    onFollowFixChange: (Boolean) -> Unit,
    windowSeconds: Int,
    onWindowSecondsChange: (Int) -> Unit,
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
    collapsedGroups: List<String>,
    onToggleGroup: (String) -> Unit,
    mapView: @Composable (Modifier) -> Unit,
    /** Which base layer the chart draws, and the seamark overlay. */
    layer: MapLayer,
    onLayerChange: (MapLayer) -> Unit,
    seaMarks: Boolean,
    onSeaMarksChange: (Boolean) -> Unit,
    /**
     * Null while this is a tab, which it normally is — `ScreenScaffold` then omits the back arrow.
     * Kept as a parameter because the screen is still reachable as a pushed destination.
     */
    onBack: (() -> Unit)? = null,
    /**
     * Whether the plot list is the curated set or everything. See `PublishedSubject.featured`.
     *
     * Hoisted like the window and the map layer: it is a preference carried between visits, not
     * something to re-choose every time the screen is opened.
     */
    basicOnly: Boolean,
    onBasicOnlyChange: (Boolean) -> Unit,
    /** The recorder's backlog, pulled on the caller's ticker. See `SensorPublisher.recordingLoad`. */
    load: RecordingLoad = RecordingLoad(),
    /** The navigation bar, supplied by `MainActivity`. See `TopLevel`. */
    bottomBar: @Composable () -> Unit = {},
) {
    // Drives the window slice and the health checks. The snapshot itself arrives on its own ticker.
    val nowMillis by produceState(System.currentTimeMillis(), paused) {
        while (!paused) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }
    var showAbout by remember { mutableStateOf(false) }
    var showThroughputHelp by remember { mutableStateOf(false) }
    var showAxes by remember { mutableStateOf(false) }
    // Which group the chips have narrowed to, if any. Deliberately *not* hoisted: narrowing to Wi-Fi
    // is something you do for a minute while looking at it, the same call `mapExpanded` makes.
    var chipFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val fix = snapshot.lastFix
    val latest: (PublishedSubject) -> Float? = { snapshot[it].latest }
    val rateOf: (PublishedSubject) -> Double? = { entry ->
        achievedHz(
            samples = status[entry].samplesPublished,
            firstEpochMillis = status[entry].firstPublishEpochMillis,
            lastEpochMillis = status[entry].lastPublishEpochMillis,
        )
    }
    val heading = latest(PublishedSubject.HEADING_TRUE_NORTH) ?: latest(PublishedSubject.HEADING_MAGNETIC)
    val headingIsTrue = latest(PublishedSubject.HEADING_TRUE_NORTH) != null

    if (showThroughputHelp) {
        InfoDialog(
            title = "Keeping up",
            body = "Two of the three places a backlog can build are measured here, and the third " +
                "cannot be.\n\n" +
                "The recorder queue holds ${formatCount(load.capacity.toLong())} samples — tens of " +
                "seconds of slack — between the sensors and the file. Depth is what is waiting; the " +
                "peak is the deepest it has been this run, and it is the number worth reading, since " +
                "a once-a-second look at a queue this size misses most bursts.\n\n" +
                "Shed samples never got that far: each sensor's callback buffer holds 64, and a " +
                "collector that cannot keep up loses them there. That is the phone itself falling " +
                "behind rather than the disk.\n\n" +
                "The link is the one that cannot be measured. Every subject is published with " +
                "congestion control set to drop, and a publish returns success whether or not " +
                "anything received it — measured on this app, about 9000 successful publishes landed " +
                "on an empty bus during a 26 s outage. So the rate above is what was handed to Zenoh, " +
                "not what arrived. Connection state and the replay figure are the only honest signals " +
                "about the bus, and both are coarse.",
            onDismiss = { showThroughputHelp = false },
        )
    }
    if (showAbout) {
        InfoDialog(
            title = "About this view",
            body = "Everything here is what actually went on the bus, not a separate read of the " +
                "sensors — so an empty plot means nothing was published.\n\n" +
                "Vector subjects are drawn as magnitude: three overlaid axes are unreadable at this " +
                "size, and magnitude is what answers \"is this sane\".\n\n" +
                "The window shows the last 30 seconds, 2 minutes or 10 minutes. It is limited by what " +
                "is held in memory — about 8000 samples per subject, which is roughly 2.5 minutes for " +
                "the 50 Hz IMU subjects and hours for the slow ones.",
            onDismiss = { showAbout = false },
        )
    }
    if (showAxes) {
        InfoDialog(
            title = "IMU axes",
            body = null,
            onDismiss = { showAxes = false },
            content = { AxisReferenceCard() },
        )
    }

    ScreenScaffold(
        title = "Live view",
        onBack = onBack,
        actions = {
            IconButton(onClick = { showAbout = true }) {
                Icon(Icons.Default.Info, contentDescription = "About the live view")
            }
        },
        bottomBar = bottomBar,
    ) { padding ->
        // Not hoisted like the other live-view preferences: expanding the chart is something you do
        // for a minute while looking at it, not a setting you carry between screens.
        var mapExpanded by rememberSaveable { mutableStateOf(false) }
        // Expanded, the chart takes the screen and the readouts go with it — reading a chart and
        // reading numbers are two different jobs and neither wants half a screen.
        //
        // **No height and no scroll here, which is the point.** This used to be a second constant,
        // hand-tuned to 560dp so that the navigation bar's ~80dp came off the 640 it wanted — a number
        // measured on one phone and wrong on any device whose bars differ. `padding` is the scaffold's
        // own measurement of the space between its bars, so filling it is correct everywhere by
        // construction. The bar stays on screen deliberately: a control that disappears is how somebody
        // ends up stranded on a full-screen map.
        if (mapExpanded) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                mapView(Modifier.fillMaxSize())
                MapToolbar(
                    followFix = followFix,
                    onFollowFixChange = onFollowFixChange,
                    layer = layer,
                    onLayerChange = onLayerChange,
                    seaMarks = seaMarks,
                    onSeaMarksChange = onSeaMarksChange,
                    expanded = mapExpanded,
                    onExpandedChange = { mapExpanded = it },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
            return@ScreenScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // -- the dashboard: everything above the fold ----------------------------------------
            // Expanded, the chart takes the screen and the readouts go with it — which is the point:
            // reading a chart and reading numbers are two different jobs and neither wants half a
            // screen. Collapsed it is still much taller than the 240dp it started at.
            // The chart and what it is showing, in one frame.
            //
            // The position used to sit on the page below a chart that ran edge to edge, so the eye went
            // straight from tiles to body text with nothing marking where one ended. Inside a rounded
            // surface, with the coordinates attached under a hairline, the two read as one instrument —
            // which is what they are.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
              Column {
                Box(Modifier.fillMaxWidth().height(MAP_HEIGHT)) {
                mapView(Modifier.fillMaxSize())
                MapToolbar(
                    followFix = followFix,
                    onFollowFixChange = onFollowFixChange,
                    layer = layer,
                    onLayerChange = onLayerChange,
                    seaMarks = seaMarks,
                    onSeaMarksChange = onSeaMarksChange,
                    expanded = mapExpanded,
                    onExpandedChange = { mapExpanded = it },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FixLine(
                    fix = fix,
                    nowMillis = nowMillis,
                    // The app's one definition of "this subject has stopped", rather than a threshold
                    // invented here — a second one would eventually disagree with the GNSS heading a
                    // few rows further down, which is the same rule reading the same status.
                    stale = running && subjectHealth(
                        status = status[PublishedSubject.LOCATION_FIX],
                        running = true,
                        nowMillis = nowMillis,
                        available = PublishedSubject.LOCATION_FIX !in unavailableSubjects,
                        enabled = PublishedSubject.LOCATION_FIX !in disabledSubjects,
                    ) == SubjectHealth.Stalled,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
              }
            }
            DashboardReadings(
                speedKnots = latest(PublishedSubject.SPEED_OVER_GROUND),
                courseDegrees = latest(PublishedSubject.COURSE_OVER_GROUND),
                headingDegrees = heading,
                headingIsTrue = headingIsTrue,
            )
            VitalsLine(
                satellitesUsed = latest(PublishedSubject.SATELLITES_USED),
                // Through the same formatter the row uses, so the word here and the word there cannot drift.
                fixQuality = latest(PublishedSubject.FIX_QUALITY)
                    ?.let { formatLiveValue(PublishedSubject.FIX_QUALITY, it) },
                // And the colour off the same enum, for the same reason.
                fixQualityTone = fixKindQuality(latest(PublishedSubject.FIX_QUALITY)),
                sinrDb = latest(PublishedSubject.CELLULAR_SINR),
                batteryPercent = latest(PublishedSubject.BATTERY_STATE_OF_CHARGE),
            )
            HealthChips(
                // Not the same list as the plot sections below, deliberately. A rig's calibration is
                // surveyed once and republished on a ten-second loop; it is configuration the phone is
                // announcing rather than telemetry it is measuring, and a health chip for it sits in
                // the one row that is meant to answer "is this run going well". The group keeps its
                // section further down, so a republish loop that has stopped is still visible.
                chips = subjectGroups().filter { it.source != SourceKind.CALIBRATION }.map { group ->
                    HealthChip(
                        name = chipName(group.title),
                        healthy = !groupSummary(
                            entries = group.entries,
                            status = status,
                            running = running,
                            nowMillis = nowMillis,
                            unavailable = unavailableSubjects,
                            disabled = disabledSubjects,
                        ).needsAttention,
                    )
                },
                selected = chipFilter,
                onSelect = { chipFilter = it },
            )

            HorizontalDivider(Modifier.padding(top = 4.dp))
            WindowControl(windowSeconds, onWindowSecondsChange, paused, onPausedChange)
            ScopeControl(basicOnly, onBasicOnlyChange)

            if (running) {
                ThroughputSection(
                    load = load,
                    dropped = recording.dropped,
                    shedBySubject = status.subjects
                        .filterValues { it.shed > 0 }
                        .mapValues { it.value.shed },
                    connection = status.connection,
                    replayLost = status.replayLost,
                    samplesPerSecond = subjectGroups().sumOf { group ->
                        groupSummary(
                            entries = group.entries,
                            status = status,
                            running = true,
                            nowMillis = nowMillis,
                            unavailable = unavailableSubjects,
                            disabled = disabledSubjects,
                        ).samplesPerSecond
                    },
                    onInfo = { showThroughputHelp = true },
                )
            }

            if (!running && snapshot.track.isEmpty()) {
                StatusLine(
                    text = "Nothing published yet",
                    tone = StatusTone.Neutral,
                    detail = "Start a run from the main screen — this view shows what went on the bus.",
                )
            }

            // -- the plots -----------------------------------------------------------------------
            //
            // Two filters, and they are different in kind. `basicOnly` is about which *subjects* are
            // worth a plot during a run; `chipFilter` is about which *group* somebody is looking into
            // right now. A group emptied by the Basic filter drops out entirely rather than showing a
            // heading over nothing.
            subjectGroups().filter { chipFilter == null || chipName(it.title) == chipFilter }
                .forEach { group ->
                val plotted = group.entries.filter { !basicOnly || it.featured }
                if (plotted.isEmpty()) return@forEach
                val summary = groupSummary(
                    entries = group.entries,
                    status = status,
                    running = running,
                    nowMillis = nowMillis,
                    unavailable = unavailableSubjects,
                    disabled = disabledSubjects,
                )
                val collapsed = group.title in collapsedGroups
                SectionHeader(
                    title = group.title,
                    trailing = featuredValue(group, latest, rateOf, fix?.accuracyMetres)
                        ?: "${summary.live}/${summary.total}",
                    trailingColor = if (summary.needsAttention) MaterialTheme.colorScheme.error else null,
                    expanded = !collapsed,
                    onToggle = { onToggleGroup(group.title) },
                    onInfo = if (group.title == "IMU") ({ showAxes = true }) else null,
                )
                if (!collapsed) {
                    plotted.forEach { entry ->
                        val window = windowedTo(snapshot[entry], windowSeconds, nowMillis)
                        if (!window.isEmpty) {
                            SparklineCard(
                                entry = entry,
                                window = window,
                                // Only the camera row gets a picture, and only the newest one — the
                                // store keeps a single frame, not a history.
                                frame = snapshot.frame
                                    .takeIf { entry == PublishedSubject.IMAGE_COMPRESSED },
                            )
                        }
                    }
                }
            }
        }
    }
}


/**
 * The chart's three controls, in one translucent column at its top-right corner.
 *
 * They were three separate `FilterChip`s in three separate surfaces — `Follow`, the layer name and
 * `Expand` — which between them took a strip of the chart about as wide as the position readout below
 * it and read as three competing buttons rather than as the chart's furniture. Icons in one container
 * take roughly a third of that, and the chart is what the screen is for.
 *
 * **Follow is a toggle, so its fill is its state and no word is needed.** The app's rule that colour
 * never carries a state alone is about *readouts* — a lamp saying whether a router is on the other end
 * has to be legible in sunlight to a colourblind reader. A control is different: it is pressed, it
 * responds, and the standard selected fill is what every toggle in the app already uses. The word
 * survives where it is actually needed, in the button's content description.
 *
 * One composable rather than two so the inline chart and the full-screen one cannot drift apart; the
 * only difference between them is which way the fullscreen glyph points.
 */
@Composable
private fun MapToolbar(
    followFix: Boolean,
    onFollowFixChange: (Boolean) -> Unit,
    layer: MapLayer,
    onLayerChange: (MapLayer) -> Unit,
    seaMarks: Boolean,
    onSeaMarksChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            MapIconButton(
                icon = IconMyLocation,
                description = if (followFix) "Following the fix, tap to stop" else "Follow the fix",
                onClick = { onFollowFixChange(!followFix) },
                active = followFix,
            )
            LayerControl(
                layer = layer,
                onLayerChange = onLayerChange,
                seaMarks = seaMarks,
                onSeaMarksChange = onSeaMarksChange,
            )
            MapIconButton(
                icon = if (expanded) IconFullscreenExit else IconFullscreen,
                description = if (expanded) "Shrink the chart" else "Expand the chart",
                onClick = { onExpandedChange(!expanded) },
            )
        }
    }
}

/**
 * One button in the chart's toolbar.
 *
 * 40dp rather than the icon's own 24: the visual weight is what this pass is reducing, and a tap target
 * shrunk to match is a control that gets missed on a moving boat.
 */
@Composable
private fun MapIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    /** A toggle that is currently on, drawn with the app's selected fill. */
    active: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = if (active) {
            IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/**
 * The base layer, and the seamarks over it.
 *
 * A menu rather than a row of chips: the list will grow — an imported archive is a layer in waiting —
 * and three chips across the top of the chart would already be taking a third of it.
 */
@Composable
private fun LayerControl(
    layer: MapLayer,
    onLayerChange: (MapLayer) -> Unit,
    seaMarks: Boolean,
    onSeaMarksChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        // The label went with the chip, and nothing was lost with it: the menu below names every layer
        // and ticks the one in use, so the active layer is one tap away rather than printed across the
        // chart it is already describing.
        MapIconButton(
            icon = IconLayers,
            description = "Map layer, currently ${layer.label}",
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MapLayer.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { Text(if (option == layer) "✓" else " ") },
                    onClick = {
                        onLayerChange(option)
                        open = false
                    },
                )
            }
            HorizontalDivider()
            // An overlay rather than a base layer, so it is a tick and not a choice: seamarks are
            // drawn *over* whichever of the above is showing.
            DropdownMenuItem(
                text = { Text("Sea marks") },
                leadingIcon = { Text(if (seaMarks) "✓" else " ") },
                onClick = {
                    onSeaMarksChange(!seaMarks)
                    open = false
                },
            )
        }
    }
}

/**
 * How much history to plot, and whether to stop the clock.
 *
 * Pause is the one that earns its place during a test: something odd goes past, and without it the
 * evidence has scrolled off the window before anyone can look at it.
 */
/**
 * Whether the phone is keeping up, in the detail the Session card deliberately leaves out.
 *
 * The card gives a verdict; this gives the numbers behind it and, just as importantly, says which
 * numbers do not exist. The link cannot be measured from here at all — see the ⓘ — so this section
 * separates what is measured (the recorder queue, the sensor flows) from what is merely reported
 * (connection state, replay), rather than mixing them into one reassuring figure.
 */
@Composable
private fun ThroughputSection(
    load: RecordingLoad,
    dropped: Long,
    shedBySubject: Map<PublishedSubject, Long>,
    connection: ConnectionState,
    replayLost: Long,
    samplesPerSecond: Double,
    onInfo: () -> Unit,
) {
    SectionHeader(
        title = "Keeping up",
        trailing = if (dropped > 0 || shedBySubject.isNotEmpty()) "losing data" else null,
        trailingColor = if (dropped > 0 || shedBySubject.isNotEmpty()) {
            MaterialTheme.colorScheme.error
        } else {
            null
        },
        onInfo = onInfo,
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ThroughputRow("Producing", "${formatRate(samplesPerSecond)} samples/s")
            ThroughputRow(
                "Waiting to be written",
                "${formatCount(load.depth)} of ${formatCount(load.capacity.toLong())}",
            )
            // The peak is given its own row rather than folded into the one above, because they answer
            // different questions: one is now, the other is whether this run ever came close.
            ThroughputRow("Deepest this run", formatCount(load.peak))

            if (dropped > 0) {
                ThroughputRow(
                    "Never reached the file",
                    formatCount(dropped),
                    tone = MaterialTheme.colorScheme.error,
                )
            }
            shedBySubject.forEach { (entry, shed) ->
                ThroughputRow(
                    "Shed by ${labelOf(entry).name.lowercase()}",
                    formatCount(shed),
                    tone = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()
            // Below the line on purpose: everything above is measured, everything here is inferred.
            ThroughputRow(
                "Router",
                when (connection) {
                    ConnectionState.Connected -> "connected"
                    ConnectionState.Disconnected -> "not connected"
                    ConnectionState.Idle -> "idle"
                },
                tone = if (connection == ConnectionState.Disconnected) {
                    MaterialTheme.colorScheme.error
                } else {
                    null
                },
            )
            if (replayLost > 0) {
                ThroughputRow(
                    "Outran the replay buffer",
                    formatCount(replayLost),
                    tone = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Delivery is not measurable from the phone — tap ⓘ.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThroughputRow(label: String, value: String, tone: Color? = null) {
    Row(Modifier.fillMaxWidth().readAsOneItem("$label $value")) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = tone ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Basic or All — how much of the bus this screen is trying to show.
 *
 * Separate from the window control above it because they answer different questions: the window is
 * *how far back*, this is *how much*. Basic is the default because thirty-nine plots is a page nobody
 * reads during a run; nothing is switched off by choosing it, and the count says what is being held
 * back so it cannot read as data having gone missing.
 */
@Composable
private fun ScopeControl(basicOnly: Boolean, onBasicOnlyChange: (Boolean) -> Unit) {
    val hidden = PublishedSubject.entries.count { !it.featured }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = basicOnly,
            onClick = { onBasicOnlyChange(true) },
            label = { Text("Basic") },
        )
        FilterChip(
            selected = !basicOnly,
            onClick = { onBasicOnlyChange(false) },
            label = { Text("All") },
        )
        if (basicOnly) {
            Text(
                "$hidden more under All",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WindowControl(
    windowSeconds: Int,
    onWindowSecondsChange: (Int) -> Unit,
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WINDOW_CHOICES.forEach { (seconds, label) ->
            FilterChip(
                selected = seconds == windowSeconds,
                onClick = { onWindowSecondsChange(seconds) },
                label = { Text(label) },
            )
        }
        Box(Modifier.weight(1f))
        FilterChip(
            selected = paused,
            onClick = { onPausedChange(!paused) },
            label = { Text(if (paused) "Paused" else "Pause") },
        )
    }
}

/**
 * One subject: its name, its reading, its shape over the window.
 *
 * A third shorter than the card it replaces — the footer used to spend its line on a sample count,
 * which tells an operator nothing that the rate and the range do not tell them better.
 */
@Composable
private fun SparklineCard(
    entry: PublishedSubject,
    window: SampleWindow,
    /** The newest camera frame, for the one subject that has one. Null everywhere else. */
    frame: FramePreview? = null,
) {
    val label = labelOf(entry)
    val lineColor = MaterialTheme.colorScheme.primary
    // Unwrapped before anything else touches it: a heading crossing north is a two-degree step, and
    // decimating first would alias a fast turn beyond recovery.
    val plotted = if (isCircularDegrees(entry)) unwrapAngles(window.values) else window.values
    // The true range, for the footer. What gets drawn uses `plotBounds`, which refuses to magnify a
    // sensor's last significant figure into a full-height wobble.
    val bounds = boundsOf(plotted)
    val rate = windowRateHz(window)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(label.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    window.latest?.let { formatLiveValue(entry, it) } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
                label.unit?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = unitGap(it), bottom = 2.dp),
                    )
                }
            }

            // Decoded against the bytes, not on every 5 Hz pull: the thumbnail only changes when a new
            // frame lands. A number cannot say the lens is covered or the exposure has gone black,
            // which is the whole reason the picture is here.
            val preview = remember(frame?.jpeg) {
                frame?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size)?.asImageBitmap() }
            }
            preview?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Latest camera frame",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            }

            if (window.isPlottable() && bounds != null) {
                val axis = plotBounds(bounds)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .padding(top = 4.dp)
                        .semantics {
                            contentDescription = "${label.name} trend over the window, " +
                                "between ${formatLiveValue(entry, bounds.min)} and " +
                                "${formatLiveValue(entry, bounds.max)}"
                        },
                ) {
                    val bins = envelope(plotted, size.width.toInt().coerceAtLeast(2)) ?: return@Canvas
                    val stepX = size.width / (bins.size - 1).coerceAtLeast(1)
                    // normalise() is bottom-up; screen y grows downward, hence the flip.
                    fun y(v: Float) = size.height * (1f - normalise(v, axis))

                    // The band: every sample in the window, so a spike is visible even where hundreds
                    // of samples share a pixel column.
                    val band = Path().apply {
                        moveTo(0f, y(bins.maxs[0]))
                        for (i in 1 until bins.size) lineTo(i * stepX, y(bins.maxs[i]))
                        for (i in bins.size - 1 downTo 0) lineTo(i * stepX, y(bins.mins[i]))
                        close()
                    }
                    drawPath(band, color = lineColor.copy(alpha = 0.35f))

                    // The running average through it, which is what makes a trend readable at 55 Hz.
                    val trend = Path().apply {
                        moveTo(0f, y(bins.means[0]))
                        for (i in 1 until bins.size) lineTo(i * stepX, y(bins.means[i]))
                    }
                    drawPath(trend, color = lineColor, style = Stroke(width = 2f))
                }
                Text(
                    buildString {
                        // The range of what is drawn: for a circular subject that is the unwrapped
                        // series, so a turn through north reads as 350–370 rather than 0–360.
                        append("${formatLiveValue(entry, bounds.min)} – ${formatLiveValue(entry, bounds.max)}")
                        // The unit belongs here as well as in the header: the footer is read on its
                        // own while scanning down a column of cards, and a bare "12 – 18" says
                        // nothing about what it is 12 of. Empty for the enum-valued subjects.
                        label.unit?.let { append(" $it") }
                        rate?.let { append(" · ${formatRate(it)} Hz") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "one sample so far",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

