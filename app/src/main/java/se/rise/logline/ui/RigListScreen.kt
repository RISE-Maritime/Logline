package se.rise.logline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.platform.DiscoveredPlatform
import se.rise.logline.platform.DiscoveryState
import se.rise.logline.ui.components.ConfirmDialog
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone

/**
 * The route segment that means "a rig that does not exist yet".
 *
 * A path segment rather than a nullable argument because the editor route is keyed on entity id: an
 * import or a remote library update can reorder the list while the editor sits on the back stack, and
 * an index would then quietly edit a different rig. [NEW_SENSOR] is the same idea one level down.
 */
const val NEW_RIG = "new"

/**
 * The rig library — the phone's platform list, and the counterpart to crowsnest's own-ship selector.
 *
 * A rig here is a keelson **platform**: its entity id is the `{entity_id}` chunk of every key its
 * geometry travels on, which is why the screen shows the id under the name rather than hiding it. Two
 * controls per row, and they answer different questions:
 *
 * - the radio is **which rig this phone is on**, the same single choice crowsnest's selector makes;
 * - the switch is **whether this rig's geometry goes on the bus**, which is not the same question. A
 *   campaign may want the geometry of every rig in the water logged, not only the one the phone is
 *   bolted to.
 *
 * The active rig's switch is on and disabled: saying "the phone is on this rig" and then not
 * publishing its geometry is a contradiction, and a control that can express it is a control somebody
 * will use by accident.
 */
@Composable
fun RigListScreen(
    rigs: List<RigCalibration>,
    activeEntityId: String,
    publishingEntityIds: Set<String>,
    /** True while a run is going: the switches restart it, so they are read-only until it stops. */
    publishing: Boolean,
    onOpenRig: (String) -> Unit,
    onAddRig: () -> Unit,
    onSetActive: (String) -> Unit,
    onSetPublishing: (String, Boolean) -> Unit,
    /** Write the whole library as a crowsnest-shaped `platform_registry.json`. */
    onExportRegistry: () -> Unit,
    /** Open the file picker. What comes back is reviewed before anything is replaced. */
    onImport: () -> Unit,
    /** The last export or import's outcome, good or bad. Cleared when the screen is left. */
    message: String?,
    /**
     * Names of rigs an import is about to overwrite, when it is about to overwrite any.
     *
     * Non-empty puts a confirmation in front of the merge. A surveyed zero is twenty minutes of
     * somebody standing still holding a phone; replacing one has to be a deliberate tap rather than a
     * side effect of picking a file with a familiar entity id in it.
     */
    pendingReplacements: List<String>,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    /** What a bus scan has turned up so far, and whether one is running. */
    discovery: DiscoveryState,
    discovered: List<DiscoveredPlatform>,
    /** Why the platform session could not open — a missing TLS credential, a bad endpoint. */
    linkFailure: String?,
    onDiscover: () -> Unit,
    /** Copy a discovered platform into the library as an ordinary rig. */
    onAdopt: (DiscoveredPlatform) -> Unit,
    /** Share the library with other stations. Off by default — it publishes this phone's rigs. */
    shareLibrary: Boolean,
    onSetShareLibrary: (Boolean) -> Unit,
    /** A library another station published, waiting to be applied or dismissed. */
    incomingRigCount: Int?,
    onApplyIncoming: () -> Unit,
    onDismissIncoming: () -> Unit,
    onBack: () -> Unit,
) {
    if (pendingReplacements.isNotEmpty()) {
        ConfirmDialog(
            title = "Replace ${pendingReplacements.size} rig${if (pendingReplacements.size == 1) "" else "s"}?",
            body = "The file describes ${pendingReplacements.joinToString(", ")}, which " +
                "${if (pendingReplacements.size == 1) "is" else "are"} already in the library. " +
                "Importing replaces the geometry, the zero point and every sensor pose. Which rigs " +
                "this phone publishes is left as it is.",
            confirmLabel = "Replace",
            onConfirm = onConfirmImport,
            onDismiss = onCancelImport,
            dismissLabel = "Cancel",
        )
    }
    ScreenScaffold(title = "Rigs", onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("Platforms", trailing = summaryOf(rigs, activeEntityId, publishingEntityIds))

            if (rigs.isEmpty()) {
                StatusLine(
                    "No rigs yet",
                    StatusTone.Neutral,
                    detail = "A rig records where a sensor rig's zero point is and where each sensor " +
                        "sits relative to it. Nothing publishes until one is described.",
                )
            }

            rigs.forEach { rig ->
                RigRow(
                    rig = rig,
                    active = rig.entityId == activeEntityId,
                    publishing = rig.entityId == activeEntityId || rig.entityId in publishingEntityIds,
                    // A rig with no sensors has nothing to say, so its switch would be a promise the
                    // publisher does not keep — see Settings.publishingRigs.
                    publishable = rig.isPublishable,
                    locked = publishing,
                    onOpen = { onOpenRig(rig.entityId) },
                    onSetActive = { onSetActive(rig.entityId) },
                    onSetPublishing = { onSetPublishing(rig.entityId, it) },
                )
            }

            if (publishing) {
                StatusLine(
                    "Publishing",
                    StatusTone.Neutral,
                    detail = "Which rigs publish is fixed when a run starts — each one declares its " +
                        "own publishers and its own liveliness token. Stop to change it.",
                )
            }

            OutlinedButton(onClick = onAddRig, modifier = Modifier.fillMaxWidth()) {
                Text("Add rig")
            }

            SectionHeader("Share with crowsnest")
            Text(
                "Crowsnest keeps its own platform list, keyed by the same entity ids. Exporting writes " +
                    "the whole library in that shape; importing reads either that or a single " +
                    "platform-geometry file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onImport,
                    enabled = !publishing,
                    modifier = Modifier.weight(1f),
                ) { Text("Import…") }
                OutlinedButton(
                    onClick = onExportRegistry,
                    enabled = rigs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Export all") }
            }
            message?.let {
                StatusLine(it, if (it.startsWith("Could not")) StatusTone.Error else StatusTone.Positive)
            }
            // Said here rather than discovered afterwards: a re-export of an imported entry would
            // otherwise quietly drop a station's stream inventory.
            Text(
                "An import keeps the geometry — dimensions, the reference point and every sensor " +
                    "pose. MMSI, call sign, data streams and camera calibrations are not read, and " +
                    "are not written back out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader(
                "On the bus",
                trailing = when (discovery) {
                    DiscoveryState.Scanning -> "scanning"
                    DiscoveryState.Done -> "${discovered.size} found"
                    DiscoveryState.Failed -> "failed"
                    DiscoveryState.Idle -> null
                },
            )
            Text(
                "Keelson has no list of platforms to ask for, so this listens: entity ids from " +
                    "liveliness, and geometry from anything republishing its configuration. A " +
                    "platform connector repeats every ten seconds, so the scan takes about that long.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Said rather than left to be inferred from a button that does nothing: without a
            // session there is nothing to scan, and a `tls/` endpoint with no imported credentials is
            // exactly that case.
            linkFailure?.let {
                StatusLine(
                    "Not connected",
                    StatusTone.Error,
                    detail = it,
                    action = null,
                )
            }
            OutlinedButton(
                onClick = onDiscover,
                enabled = discovery != DiscoveryState.Scanning && linkFailure == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (discovery == DiscoveryState.Scanning) "Scanning…" else "Scan the bus")
            }
            if (discovery == DiscoveryState.Done && discovered.isEmpty()) {
                StatusLine(
                    "Nothing found",
                    StatusTone.Neutral,
                    detail = "No platform announced itself on this realm. A router that is reachable " +
                        "but has no platforms on it looks exactly like this.",
                )
            }
            discovered.forEach { platform ->
                DiscoveredRow(
                    platform = platform,
                    // Already in the library: adopting again would overwrite a rig somebody may have
                    // corrected by hand since.
                    known = rigs.any { it.entityId == platform.entityId },
                    onAdopt = { onAdopt(platform) },
                )
            }

            SectionHeader("Shared library")
            Text(
                "Publishes this phone's rigs where other stations can read them, and applies theirs " +
                    "when they are newer. Which rig is active and which ones publish stay this " +
                    "phone's own — a library arriving over the air never changes what goes on the bus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Share with other stations", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = shareLibrary, onCheckedChange = onSetShareLibrary)
            }
            incomingRigCount?.let { count ->
                StatusLine(
                    "A newer library is available",
                    StatusTone.Neutral,
                    detail = "$count rig${if (count == 1) "" else "s"} from another station.",
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onDismissIncoming) { Text("Ignore") }
                            OutlinedButton(onClick = onApplyIncoming) { Text("Apply") }
                        }
                    },
                )
            }
        }
    }
}

private fun summaryOf(
    rigs: List<RigCalibration>,
    activeEntityId: String,
    publishingEntityIds: Set<String>,
): String? {
    if (rigs.isEmpty()) return null
    val publishing = rigs.count {
        it.isPublishable && (it.entityId == activeEntityId || it.entityId in publishingEntityIds)
    }
    return "$publishing of ${rigs.size} publishing"
}

@Composable
private fun RigRow(
    rig: RigCalibration,
    active: Boolean,
    publishing: Boolean,
    publishable: Boolean,
    locked: Boolean,
    onOpen: () -> Unit,
    onSetActive: () -> Unit,
    onSetPublishing: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = active, onClick = onSetActive, enabled = !locked)
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(vertical = 12.dp),
            ) {
                Text(rig.name.ifBlank { rig.entityId }, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitleOf(rig),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = publishing && publishable,
                // The active rig always publishes, so its switch states a fact rather than offering a
                // choice; a rig with no sensors has nothing to publish at all.
                onCheckedChange = if (active || !publishable || locked) null else onSetPublishing,
                enabled = !active && publishable && !locked,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun DiscoveredRow(platform: DiscoveredPlatform, known: Boolean, onAdopt: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    platform.rig?.name?.ifBlank { platform.entityId } ?: platform.entityId,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    // Liveliness gives an id and nothing else, so an entity with no document is worth
                    // showing and not worth adopting — there is no geometry to copy.
                    if (platform.hasGeometry) {
                        "${platform.entityId} · ${platform.rig!!.sensors.size} sensors"
                    } else {
                        "${platform.entityId} · alive, no geometry published"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                known -> Text(
                    "In library",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                platform.hasGeometry -> OutlinedButton(onClick = onAdopt) { Text("Add") }
            }
        }
    }
}

private fun subtitleOf(rig: RigCalibration): String = buildList {
    add(rig.entityId)
    rig.platformType?.let { add(it.wire) }
    add(
        when (rig.sensors.size) {
            0 -> "no sensors"
            1 -> "1 sensor"
            else -> "${rig.sensors.size} sensors"
        }
    )
    // The zero is what anchors the transforms to the earth; a rig without one is legitimate and worth
    // distinguishing at a glance from one that has been surveyed.
    if (rig.zero?.hasPosition == true) add("surveyed") else add("no zero")
}.joinToString(" · ")
