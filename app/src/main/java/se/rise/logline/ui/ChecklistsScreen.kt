package se.rise.logline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.rise.logline.checklist.ChecklistLink
import se.rise.logline.checklist.ChecklistUiState
import se.rise.logline.checklist.Procedure
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone

/**
 * The checklist library: what procedures exist on this bus, and how far through each one is.
 *
 * Takes data and lambdas only, like every other screen. Three states worth distinguishing, and the
 * screen says which it is in rather than showing an empty list for all three: no identity set up yet,
 * nothing published to the bus, and a library it can show.
 */
@Composable
fun ChecklistsScreen(
    state: ChecklistUiState,
    operatorName: String,
    operatorRole: String,
    rocSite: String,
    onSaveIdentity: (name: String, role: String, site: String) -> Unit,
    onOpenProcedure: (String) -> Unit,
    onPublishStarter: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(title = "Checklists", onBack = onBack, modifier = modifier) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyncLine(state)

            if (operatorName.isBlank()) {
                IdentityCard(operatorName, operatorRole, rocSite, onSaveIdentity)
                return@Column
            }

            if (state.procedures.isEmpty()) {
                EmptyLibraryCard(state, onPublishStarter)
                return@Column
            }

            state.procedures
                .groupBy { it.category.ifBlank { "other" } }
                .toSortedMap()
                .forEach { (category, procedures) ->
                    SectionHeader(
                        title = category.replaceFirstChar { it.uppercase() },
                        trailing = formatCounted(procedures.size.toLong(), "procedure"),
                    )
                    procedures.forEach { procedure ->
                        ProcedureRow(procedure, state, onOpenProcedure)
                    }
                }
        }
    }
}

@Composable
private fun SyncLine(state: ChecklistUiState) {
    val others = state.presence.size
    when (state.sync.link) {
        ChecklistLink.Connected -> StatusLine(
            text = "Synced",
            tone = StatusTone.Positive,
            detail = if (others == 0) {
                "No other operators on this checklist right now."
            } else {
                "Also here: " + state.presence.joinToString { "${it.username} (${it.rocSite})" }
            },
        )

        ChecklistLink.Disconnected -> StatusLine(
            text = "Working offline",
            tone = StatusTone.Warning,
            // The distinction that matters: nothing is lost, it is queued. Saying "disconnected" and
            // stopping there invites somebody to redo the work somewhere else.
            detail = "Ticks are kept on this phone and sent when the router comes back.",
        )

        ChecklistLink.Opening -> StatusLine("Connecting…", StatusTone.Neutral)

        ChecklistLink.Failed -> StatusLine(
            text = "Checklist sync failed",
            tone = StatusTone.Error,
            detail = state.sync.message,
        )

        ChecklistLink.Off -> StatusLine(
            text = "Not syncing",
            tone = StatusTone.Neutral,
            detail = "Turn on checklist sync in Settings to share this with other sites.",
        )
    }
}

/**
 * Asked for once, before anything is published.
 *
 * Every checklist event carries who did it and where — that is the point of a shared checklist — so
 * there is no useful "anonymous" mode to fall back on. Better to ask plainly than to publish
 * "Operator" from eight phones.
 */
@Composable
private fun IdentityCard(
    name: String,
    role: String,
    site: String,
    onSave: (String, String, String) -> Unit,
) {
    var editedName by remember { mutableStateOf(name) }
    var editedRole by remember { mutableStateOf(role) }
    var editedSite by remember { mutableStateOf(site) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Who is using this phone?", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every tick you make is published with this name and site, and other operators see " +
                    "it on theirs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = editedName,
                onValueChange = { editedName = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = editedRole,
                onValueChange = { editedRole = it },
                label = { Text("Role (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = editedSite,
                onValueChange = { editedSite = it },
                label = { Text("Site") },
                supportingText = { Text("Must differ from the ROC stations' own site names.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSave(editedName.trim(), editedRole.trim(), editedSite.trim()) },
                enabled = editedName.isNotBlank() && editedSite.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun EmptyLibraryCard(state: ChecklistUiState, onPublishStarter: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.sync.link != ChecklistLink.Connected) {
                // Offline with nothing cached. Offering "publish the starter library" here would look
                // like a fix and quietly go nowhere — the put would succeed and land on no router.
                Text("No procedures on this phone", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Nothing has been cached here yet, and there is no router to read the library " +
                        "from. Connect to one and it will appear.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!state.sync.bootstrapped) {
                Text("Looking for procedures…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Reading the library from the router.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("No procedures on this bus", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Nothing has been published to checklist_procedure, or the router has no storage " +
                        "configured for it — from here those look the same. Publishing the starter " +
                        "library puts it on the router, where every site reads it from then on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onPublishStarter, modifier = Modifier.fillMaxWidth()) {
                    Text("Publish starter library")
                }
            }
        }
    }
}

@Composable
private fun ProcedureRow(
    procedure: Procedure,
    state: ChecklistUiState,
    onOpen: (String) -> Unit,
) {
    val progress = state.state.progressFor(procedure.procedureId)
    val done = progress.completedCount()
    val flagged = progress.flaggedCount()
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(procedure.procedureId) }
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(procedure.title, style = MaterialTheme.typography.titleMedium)
                if (procedure.description.isNotBlank()) {
                    Text(
                        procedure.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (flagged > 0) {
                    Text(
                        formatCounted(flagged.toLong(), "flagged item"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                "$done/${procedure.items.size}",
                style = MaterialTheme.typography.titleMedium,
                color = if (done == procedure.items.size && procedure.items.isNotEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
