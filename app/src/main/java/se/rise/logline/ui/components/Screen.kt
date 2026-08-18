package se.rise.logline.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
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
    /** Shown before the title. The app mark on the start screen; nothing on the inner screens. */
    titleIcon: (@Composable () -> Unit)? = null,
    /** The trailing end of the bar — the connection state on the start screen. */
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        // Where the content's scroll reaches the bar. Without this the bar never moves — the scroll
        // happens inside the content and nothing else hears about it.
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        titleIcon?.let {
                            it()
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    onBack?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = { actions() },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = bottomBar,
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
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
        StatusTone.Positive -> MaterialTheme.colorScheme.primary
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
