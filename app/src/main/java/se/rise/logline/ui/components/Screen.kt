package se.rise.logline.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import se.rise.logline.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import se.rise.logline.publish.ConnectionState
import se.rise.logline.ui.Routes
import se.rise.logline.ui.theme.signalGreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The frame every screen shares: a title, a real back arrow, and somewhere to pin the actions.
 *
 * Before this, each screen printed its own headline into the scroll and put "Back" or "Cancel" at the
 * *bottom* of it — so leaving a screen meant scrolling to the end of it first, and the only reliable
 * way out was the system gesture, which discarded edits silently.
 *
 * **The bar scrolls away with the content.** These screens are long lists read on a phone, and a
 * permanently parked title costs a row of them for a name you already know. `enterAlways` rather than
 * `exitUntilCollapsed`: the bar leaves entirely when you scroll down, and comes straight back on the
 * first upward flick, so the back arrow is never further away than one gesture. Its measured height
 * shrinks as it goes, so the content takes the space back rather than leaving a gap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    /**
     * Screen-specific actions, drawn *before* the run status — which is always last, so the far right
     * of the bar means the same thing on every screen.
     */
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    /**
     * Transient confirmation, for an action whose only other evidence arrives late.
     *
     * Material draws this above [bottomBar], so it does not hide the navigation bar or a form's Save.
     */
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            // **Pinned, not scrolled away.** It used to use `enterAlwaysScrollBehavior`, on the
            // argument that a long list should not spend a row on a name you already know. That was
            // true while the bar carried only a name — now it carries whether the phone is publishing
            // and recording, which is the one thing worth being able to see at any moment, and a
            // status that disappears on the first downward flick is no status at all.
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                // The app mark where a pushed screen puts its back arrow: one slot, and whichever is
                // there tells you where you are — at the top of the app, or inside something.
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    } else {
                        Box(Modifier.padding(start = 10.dp)) { AppMark(size = APP_MARK_IN_BAR) }
                    }
                },
                actions = {
                    actions()
                    RunStatus()
                },
            )
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        content = content,
    )
}

/**
 * Save and Cancel, pinned above the keyboard rather than at the end of the form.
 *
 * Measured on the settings screen before this existed: six swipes from the top of the form to the Save
 * button, on every single edit.
 */
@Composable
fun FormActions(
    onSave: () -> Unit,
    onCancel: () -> Unit,
    saveEnabled: Boolean = true,
    saveLabel: String = "Save",
    hint: String? = null,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                // The gesture bar, which this used to sit almost on top of — measured about 28 px of
                // clearance on a Pixel 6, so Save and Cancel were both a mis-swipe and awkward to reach.
                // `NavigationBar` applies this inset itself, which is why the tabbed screens looked
                // right and every *form* screen did not.
                //
                // On the Column rather than the Surface, so the tonal background still runs behind the
                // gesture area instead of leaving a bare strip under the bar.
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
        ) {
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = saveEnabled, modifier = Modifier.weight(1f)) {
                    Text(saveLabel)
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        }
    }
}

/**
 * One heading style for every section on every screen, with room for a summary on the right.
 *
 * Optionally the control that folds its section away: on the start screen a group's summary is the
 * thing you read first, and its rows are the drill-down.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingColor: Color? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    /** Opens the documentation this section would otherwise have to print into the page. */
    onInfo: (() -> Unit)? = null,
    /**
     * A control for the whole section, drawn at the far right.
     *
     * Outside the collapse chevron so it lines up with whatever the section's rows put in their own
     * trailing column — on the start screen that is each subject's switch, and a master switch that did
     * not sit in the same column would not read as governing them. It is a sibling of the header's
     * `clickable`, so operating it does not also fold the section away.
     */
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(top = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            trailing?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = trailingColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            onInfo?.let {
                IconButton(onClick = it, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "About $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded != null) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            action?.invoke()
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

/**
 * A title, the state that used to hide inside a button label, and somewhere to go.
 *
 * Shared rather than written per screen: Setup's list and the per-subject page's link to whichever
 * subject owns its rate are the same row, and two copies of a row drift — `ConnectionChip` was written
 * twice and the two spellings of `Idle` are how that was noticed.
 */
@Composable
fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .readAsOneItem("$title. $subtitle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            // The title says where this goes; naming the chevron would only repeat it.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How a [StatusLine] reads, and which icon carries it. Never colour alone — the text always says it. */
enum class StatusTone { Positive, Neutral, Warning, Error }

@Composable
fun StatusLine(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val color = when (tone) {
        // Green, not the app's blue: green means *fine* and blue is general information, which is the
        // same traffic light the connection lamp uses. `primary` said "healthy" only by convention.
        StatusTone.Positive -> signalGreen()
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.Warning -> MaterialTheme.colorScheme.tertiary
        StatusTone.Error -> MaterialTheme.colorScheme.error
    }
    val icon: ImageVector = when (tone) {
        StatusTone.Positive -> Icons.Default.CheckCircle
        StatusTone.Neutral -> Icons.Default.Info
        StatusTone.Warning, StatusTone.Error -> Icons.Default.Warning
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            // The text beside it already names the state; announcing the icon too would just repeat it.
            contentDescription = null,
            tint = color,
            modifier = Modifier.padding(end = 8.dp, top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleMedium, color = color)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        action?.invoke()
    }
}

/**
 * The one dialog shape for anything that cannot be undone.
 *
 * Clearing the client key is the case this was written for: it authenticates the phone to the shared
 * fleet bus and the phone has no copy to restore from.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    /** What backing out is called here — "keep editing" only makes sense over an unsaved form. */
    dismissLabel: String = "Keep editing",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/**
 * A whole row read as one item by TalkBack.
 *
 * A subject row is five `Text`s that only mean anything together; swiping through them one at a time is
 * how a screen reader turns a status list into a word salad.
 */
fun Modifier.readAsOneItem(description: String): Modifier =
    clearAndSetSemantics { contentDescription = description }

/**
 * How large the mark is drawn in the app bar.
 *
 * **Bounded by the bar, not by the slot.** `CenterAlignedTopAppBar` fixes its own height at 64dp and
 * measures the navigation icon against that, so anything up to about 48dp makes the mark bigger without
 * moving a single pixel of the page below — verified by measuring the first card's top edge before and
 * after, which did not move. Beyond that the bar itself starts to give, which is the one thing this is
 * not allowed to cost. 40dp leaves about 12dp of clearance either side.
 *
 * Note the adaptive-icon foreground carries a 0.80 safe-zone scale inside its own canvas (see
 * `art/svg_to_adaptive_icon.py`), so the *drawn mark* is smaller again than the figure here.
 */
private val APP_MARK_IN_BAR = 40.dp

/**
 * The launcher icon, drawn as the launcher draws it: foreground over background, clipped round.
 *
 * Both layers are vectors, so this is the same artwork the home screen shows rather than a separate
 * copy that could drift from it — `art/logline_icon.svg` stays the one source. The foreground already
 * carries the 0.8 safe-zone scale, so the circular clip lands where the launcher's mask does.
 */
@Composable
fun AppMark(modifier: Modifier = Modifier, size: Dp = 30.dp) {
    Box(modifier.size(size).clip(CircleShape)) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            // The title beside it says "Logline"; naming the icon too would just repeat it.
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Documentation, one tap away rather than printed into the page.
 *
 * The live view used to carry a paragraph about magnitudes and the entire axis reference between the
 * map and the plots. Both are worth having and neither is worth the space it took on a screen someone
 * is reading mid-test.
 */
@Composable
fun InfoDialog(
    title: String,
    body: String?,
    onDismiss: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                content?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The five things this app is for, and the only destinations that carry a navigation bar.
 *
 * Everything else — the settings form, the rig editor, a sensor, a QoS sheet, the annotation buttons —
 * is *pushed* on top of one of these and keeps a back arrow instead. That split is what lets the bar
 * share [ScreenScaffold]'s single `bottomBar` slot with [FormActions] and never collide with it: no
 * screen is both a tab and a form.
 *
 * `route` comes from [Routes] rather than being spelled again here, so a tab's route and the graph's
 * declaration of it cannot drift — the bar decides what is selected by comparing against the current
 * back-stack entry, and a stale string there is a tab that never lights up. Note `main` must stay the graph's start destination — system back on
 * any other tab pops to it, which is the platform-standard behaviour the nav call below relies on.
 */
enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    /**
     * The start screen, and no longer a play arrow.
     *
     * `PlayArrow` sat directly above the Start button pinned into the same bottom bar —
     * two start controls, one of which does nothing of the sort — and it went on saying "play" for the
     * whole of a run. `Home` is the honest reading: this is where a session is begun, watched and
     * ended. The choice is out of `material-icons-core`'s forty-eight, which is the whole vocabulary
     * available here; `material-icons-extended` is tens of megabytes of vectors for four drawings.
     */
    Session(Routes.MAIN, "Session", Icons.Default.Home),
    Live(Routes.LIVE, "Live", Icons.Default.Place),
    Events(Routes.ANNOTATIONS, "Events", Icons.Default.Edit),

    /**
     * Saved recordings, promoted out of Setup.
     *
     * The files are the *output* of this app, and they were two taps down a configuration screen while
     * the start screen spent a line of its status card telling people which folder they had gone to.
     * Labelled `Files` rather than `Recordings` because five tabs leave about 72 dp each and the longer
     * word ellipsizes; the screen keeps its own title.
     */
    Files(Routes.RECORDINGS, "Files", Icons.AutoMirrored.Filled.List),
    Setup(Routes.SETUP, "Setup", Icons.Default.Settings),
}

/**
 * The persistent bar across the bottom of the four top-level screens.
 *
 * Icons come from `material-icons-core`, which is already a dependency and carries all four. Do not
 * reach for `material-icons-extended` to get a prettier glyph — it is tens of megabytes of vectors for
 * four drawings.
 */
@Composable
fun LoglineNavBar(current: String?, onSelect: (TopLevel) -> Unit) {
    NavigationBar {
        TopLevel.entries.forEach { dest ->
            NavigationBarItem(
                selected = current == dest.route,
                onClick = { onSelect(dest) },
                icon = { Icon(dest.icon, contentDescription = null) },
                label = { Text(dest.label) },
            )
        }
    }
}

/**
 * What the run is doing, as the top bar shows it.
 *
 * Ambient rather than a parameter: every screen's bar carries it, and threading three fields through
 * fourteen screen signatures to reach a chip in shared chrome would put the publisher's state into the
 * argument list of the rig editor. `App()` provides it once.
 */
data class RunState(
    val running: Boolean = false,
    val connection: ConnectionState = ConnectionState.Idle,
    val recording: Boolean = false,
)

val LocalRunState = compositionLocalOf { RunState() }

/**
 * Publishing and recording, side by side, at the far right of every bar.
 *
 * **Two indicators, not one word.** A single label had to choose between them by priority, so a run
 * that was recording said `REC` and stopped saying anything about the link — which hid the more
 * important of the two: whether the samples are reaching a router is in doubt, whether the file is
 * being written is not.
 *
 * Colour follows the app's traffic light and nothing else: **green is fine, amber is a warning, red is
 * an error, grey is off**. That is why recording is *green* rather than the conventional red — red here
 * means something is wrong, and a healthy recording is the opposite of that. `connectionColor()` is the
 * same function the status card's lamp uses, so the bar and the card cannot disagree.
 *
 * Never colour alone: each lamp carries its own word, which is what survives sunlight and a colourblind
 * reader.
 */
@Composable
private fun RunStatus() {
    val state = LocalRunState.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(end = 14.dp),
    ) {
        StatusLamp(
            label = "PUB",
            color = connectionColor(state.running, state.connection),
            // Blinking would be wrong here: publishing is a steady condition, and a pulse should mean
            // something is happening right now.
            blinking = false,
            described = when {
                !state.running -> "Not publishing"
                state.connection == ConnectionState.Disconnected -> "Publishing, no router"
                state.connection == ConnectionState.Connected -> "Publishing"
                else -> "Connecting"
            },
        )
        StatusLamp(
            label = "REC",
            color = if (state.recording) {
                signalGreen()
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            // The one thing that blinks, and only while a file is actually being written.
            blinking = state.recording,
            described = if (state.recording) "Recording" else "Not recording",
        )
    }
}

@Composable
private fun StatusLamp(label: String, color: Color, blinking: Boolean, described: String) {
    val blink by rememberInfiniteTransition(label = label).animateFloat(
        initialValue = 1f,
        targetValue = if (blinking) 0.25f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "$label-lamp",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.readAsOneItem(described),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = blink }
                .clip(CircleShape)
                .background(color)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Whether there is a router on the other end — the one thing worth knowing from across a cockpit.
 *
 * Shared rather than written per screen because it was written twice and the two drifted: the start
 * screen said `Idle` and the live view said `IDLE`, from unrelated implementations of the same idea.
 * The wording here is the whole vocabulary for *global* state; a session fact ("Publishing to
 * nothing", "Recording saved") belongs on the status card, and a screen's own mode ("LIVE · REC") on
 * that screen.
 */
/**
 * The link as a traffic light: green connected, amber still trying, red gone, grey idle.
 *
 * Shared by the chip in the app bar and the dot beside the router in the start screen's detail panel,
 * because they report the same fact and read as a contradiction the moment they differ. It is a
 * *link* state and nothing else — a stalled subject or a slow sensor does not colour it, since the
 * question this answers is whether there is a router on the other end.
 */
@Composable
fun connectionColor(running: Boolean, connection: ConnectionState): Color = when {
    !running -> MaterialTheme.colorScheme.onSurfaceVariant
    connection == ConnectionState.Connected -> signalGreen()
    connection == ConnectionState.Disconnected -> MaterialTheme.colorScheme.error
    // Idle while the run is up means the session is still opening.
    else -> MaterialTheme.colorScheme.tertiary
}

@Composable
fun ConnectionChip(running: Boolean, connection: ConnectionState, modifier: Modifier = Modifier) {
    val text = when {
        !running -> "Idle"
        connection == ConnectionState.Connected -> "Running"
        connection == ConnectionState.Disconnected -> "No router"
        else -> "Connecting"
    }
    val color = connectionColor(running, connection)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(end = 12.dp).readAsOneItem("Router $text"),
    ) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(9.dp)) {}
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * One shape for "there is nothing here yet", because there were three.
 *
 * A `StatusLine` on the rig list, a centred column on the recordings list and a bare line of body text
 * on the annotation screen all answered the same question in a different voice. An empty state is the
 * first thing a new install shows, so it is worth saying what the thing is for and offering the one
 * action that ends it — hence [primary], with [secondary] for the import-shaped alternative.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primary: (@Composable () -> Unit)? = null,
    secondary: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (primary != null || secondary != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primary?.invoke()
                secondary?.invoke()
            }
        }
    }
}
