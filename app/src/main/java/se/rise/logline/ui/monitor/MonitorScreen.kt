package se.rise.logline.ui.monitor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.rise.logline.monitor.CardKind
import se.rise.logline.monitor.MonitorCard
import se.rise.logline.monitor.MonitorLink
import se.rise.logline.monitor.MonitorSnapshot
import se.rise.logline.monitor.PayloadShape
import se.rise.logline.monitor.inputs
import se.rise.logline.monitor.resolve
import se.rise.logline.ui.components.InfoDialog
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone
import se.rise.logline.ui.formatCount
import java.net.URI

/**
 * The Monitor tab: another entity's data, as cards somebody chose.
 *
 * Takes data and lambdas only, per the rule every screen here follows — the stream, the store and the
 * settings are resolved in `App()`. [chart] is the osmdroid map for the Chart card, handed down as a
 * composable for the reason `LiveScreen` takes its map that way: a `MapView` needs a `Context` and
 * this screen must not.
 */
@Composable
fun MonitorScreen(
    entity: String,
    realm: String,
    baseUrl: String?,
    configuredUrl: String,
    configuredRealm: String,
    link: MonitorLink,
    snapshot: MonitorSnapshot,
    cards: List<MonitorCard>,
    onSourceChange: (entity: String, realm: String, url: String) -> Unit,
    onCardsChange: (List<MonitorCard>) -> Unit,
    chart: @Composable (ChartModel, Modifier) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    var info by remember { mutableStateOf(false) }
    var editingSource by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var topicsOpen by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(
        title = "Monitor",
        actions = {
            IconButton(onClick = { info = true }) {
                Icon(Icons.Default.Info, contentDescription = "About the Monitor tab")
            }
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "source") {
                SourceHeader(
                    entity = entity,
                    realm = realm,
                    baseUrl = baseUrl,
                    link = link,
                    snapshot = snapshot,
                    onEdit = { editingSource = true },
                    topicsOpen = topicsOpen,
                    onToggleTopics = { topicsOpen = !topicsOpen },
                )
            }
            if (cards.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No cards. Add one to start watching $entity.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(cards, key = { it.id }) { card ->
                CardFrame(card = card, snapshot = snapshot, onEdit = { editingId = card.id }) {
                    if (card.kind == CardKind.NavMap) {
                        ChartCardBody(card, snapshot, chart)
                    } else {
                        CardBody(card, snapshot)
                    }
                }
            }
            item(key = "add") {
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add card")
                }
            }
        }
    }

    if (info) {
        InfoDialog(
            title = "Monitor",
            body = "Watches another entity on the bus — everything it publishes under " +
                "$realm/@v0/$entity/pubsub — and shows it as cards, like panels in Foxglove Studio.\n\n" +
                "The data comes from the router's REST plugin as a Server-Sent Events stream, not " +
                "from a Zenoh subscriber: on this build a subscribed sample stamped by the router " +
                "aborts the app. The stream is plain HTTP and unauthenticated — the mTLS credentials " +
                "protect the Zenoh link only.\n\n" +
                "Every card resolves its inputs by subject. A source left on Auto reads the first " +
                "source publishing that subject; pin one in the card's settings to choose. A value " +
                "older than the card's stale time is greyed; a subject nobody publishes reads —.\n\n" +
                "The stream runs only while this tab is on screen.",
            onDismiss = { info = false },
        )
    }
    if (editingSource) {
        SourceDialog(
            entity = entity,
            realm = configuredRealm,
            url = configuredUrl,
            derivedUrl = baseUrl,
            onDismiss = { editingSource = false },
            onSave = { e, r, u ->
                onSourceChange(e, r, u)
                editingSource = false
            },
        )
    }
    if (adding) {
        AddCardSheet(
            onDismiss = { adding = false },
            onAdd = { kind ->
                onCardsChange(cards + MonitorCard.new(kind))
                adding = false
            },
        )
    }
    val editing = cards.firstOrNull { it.id == editingId }
    if (editing != null) {
        val index = cards.indexOf(editing)
        CardSettingsSheet(
            card = editing,
            snapshot = snapshot,
            canMoveUp = index > 0,
            canMoveDown = index < cards.lastIndex,
            onChange = { updated -> onCardsChange(cards.map { if (it.id == updated.id) updated else it }) },
            // One write carrying the draft *and* the move: two writes off the same captured list would
            // have the second undo the first.
            onMove = { updated, delta ->
                val to = (index + delta).coerceIn(0, cards.lastIndex)
                onCardsChange(
                    cards.map { if (it.id == updated.id) updated else it }
                        .toMutableList()
                        .apply { add(to, removeAt(index)) },
                )
            },
            onRemove = {
                onCardsChange(cards.filterNot { it.id == editing.id })
                editingId = null
            },
            onDismiss = { editingId = null },
        )
    }
}

/** The entity being watched, the state of the stream, and what has been seen on it. */
@Composable
private fun SourceHeader(
    entity: String,
    realm: String,
    baseUrl: String?,
    link: MonitorLink,
    snapshot: MonitorSnapshot,
    onEdit: () -> Unit,
    topicsOpen: Boolean,
    onToggleTopics: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entity, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "$realm · ${baseUrl?.let(::hostLabel) ?: "no router"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Settings, contentDescription = "Change what is watched")
                }
            }
            val topics = snapshot.topics.size
            when (link) {
                is MonitorLink.Streaming -> StatusLine(
                    if (topics == 0) "Connected · nothing from $entity yet" else "Streaming · ${formatCount(topics.toLong())} topics",
                    if (topics == 0) StatusTone.Warning else StatusTone.Positive,
                )
                is MonitorLink.Connecting -> StatusLine("Connecting to ${hostLabel(link.url)}", StatusTone.Neutral)
                is MonitorLink.Failed -> StatusLine(
                    "Not streaming: ${link.reason}",
                    StatusTone.Error,
                    detail = "Retrying. The router must serve its REST plugin (normally port 8000).",
                )
                MonitorLink.Idle -> StatusLine("Idle", StatusTone.Neutral)
            }
            if (topics > 0) {
                SectionHeader(
                    title = "Seen on the bus",
                    trailing = formatCount(topics.toLong()),
                    expanded = topicsOpen,
                    onToggle = onToggleTopics,
                )
                if (topicsOpen) TopicList(snapshot)
            }
        }
    }
}

@Composable
private fun TopicList(snapshot: MonitorSnapshot) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        snapshot.topics.forEach { t ->
            val age = ((snapshot.nowMillis - t.lastArrivalMillis) / 1000).coerceAtLeast(0)
            val note = when {
                t.shape == PayloadShape.Undecoded -> " · not decoded"
                t.undecodable > 0 -> " · ${t.undecodable} undecodable"
                else -> ""
            }
            Row {
                Text(
                    t.topic.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatCount(t.count)} · ${age}s$note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A card's chrome: its kind, what it resolved to, and the gear. */
@Composable
private fun CardFrame(
    card: MonitorCard,
    snapshot: MonitorSnapshot,
    onEdit: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.kind.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        resolvedSummary(card, snapshot),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Settings, contentDescription = "${card.kind.label} settings")
                }
            }
            Column(Modifier.padding(end = 12.dp, top = 4.dp)) { content() }
        }
    }
}

/** `heading_true_north_deg · gnss`, or which inputs are missing — the card's answer to "reading what?". */
private fun resolvedSummary(card: MonitorCard, snapshot: MonitorSnapshot): String {
    val inputs = card.inputs()
    val first = inputs.firstOrNull() ?: return ""
    val topic = snapshot.resolve(first)
    val head = if (topic != null && snapshot.info(topic) != null) "${topic.subject} · ${topic.source}" else "${first.subject} · not seen"
    val missing = inputs.drop(1).count { input -> snapshot.resolve(input)?.let(snapshot::info) == null }
    return if (inputs.size > 1 && missing > 0) "$head · ${inputs.size - 1 - missing}/${inputs.size - 1} more" else head
}

private fun hostLabel(url: String): String = runCatching {
    val u = URI(url)
    if (u.port > 0) "${u.host}:${u.port}" else u.host
}.getOrNull() ?: url

@Composable
private fun SourceDialog(
    entity: String,
    realm: String,
    url: String,
    derivedUrl: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var e by remember { mutableStateOf(entity) }
    var r by remember { mutableStateOf(realm) }
    var u by remember { mutableStateOf(url) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = e,
                    onValueChange = { e = it.trim() },
                    label = { Text("Entity") },
                    singleLine = true,
                    isError = e.isBlank() || '/' in e || '*' in e,
                    supportingText = { Text("One entity id, e.g. case. Changing it clears what is on screen.") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = r,
                    onValueChange = { r = it.trim() },
                    label = { Text("Realm") },
                    placeholder = { Text("Same as this phone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = u,
                    onValueChange = { u = it.trim() },
                    label = { Text("Router REST URL") },
                    placeholder = { Text(derivedUrl ?: "http://router:8000") },
                    supportingText = { Text("Blank uses the configured router's host on port 8000.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (e.isNotBlank()) onSave(e, r, u) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(e, r, u) },
                enabled = e.isNotBlank() && '/' !in e && '*' !in e,
            ) { Text("Watch") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCardSheet(onDismiss: () -> Unit, onAdd: (CardKind) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Add card",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            CardKind.entries.forEach { kind ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAdd(kind) }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        kind.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A card's settings, edited as a **draft** and committed when the sheet closes.
 *
 * Committing per keystroke would write DataStore on every character through a read-modify-write of
 * the captured settings — two quick keystrokes can land on the same `current` and one of them is lost,
 * and the field then redraws from whichever won. One write on close has neither problem.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardSettingsSheet(
    card: MonitorCard,
    snapshot: MonitorSnapshot,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (MonitorCard) -> Unit,
    onMove: (MonitorCard, Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(card.id) { mutableStateOf(card) }
    val commit = { if (draft != card) onChange(draft) }
    ModalBottomSheet(onDismissRequest = { commit(); onDismiss() }) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(card.kind.label, style = MaterialTheme.typography.titleLarge)
            ParamEditor(card = draft, snapshot = snapshot, onChange = { draft = it })
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onMove(draft, -1) }, enabled = canMoveUp) { Text("Up") }
                OutlinedButton(onClick = { onMove(draft, 1) }, enabled = canMoveDown) { Text("Down") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { commit(); onDismiss() }) { Text("Done") }
            }
        }
    }
}
