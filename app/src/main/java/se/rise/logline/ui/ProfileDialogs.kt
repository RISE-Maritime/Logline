package se.rise.logline.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.rise.logline.config.SettingsProfile

/**
 * What an import is about to change, before it changes it.
 *
 * A profile arrives from another phone and overwrites the endpoints a run depends on. Showing what it
 * carries — and naming what it *cannot* carry — is the difference between a settings screen that
 * changed under somebody and one they changed.
 */
@Composable
fun ImportProfileDialog(
    profile: SettingsProfile,
    onApply: (withOperator: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var withOperator by remember { mutableStateOf(profile.hasOperator) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply these settings?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profile.realm?.let { Line("Realm", it) }
                profile.routerEndpoints?.let {
                    Line("Router", it.split('\n').joinToString(", "))
                }
                val sources = listOfNotNull(
                    profile.locationSource,
                    profile.imuSource,
                    profile.deviceSource,
                    profile.calibrationSource,
                ).distinct()
                if (sources.isNotEmpty()) Line("Source ids", sources.joinToString(", "))
                profile.sensorRates?.let { Line("Rates", formatCounted(it.size.toLong(), "subject")) }
                profile.qosOverrides?.let { Line("QoS overrides", formatCounted(it.size.toLong(), "subject")) }
                profile.disabledSubjects?.takeIf { it.isNotBlank() }?.let {
                    Line("Switched off", formatCounted(it.split('\n').size.toLong(), "subject"))
                }

                if (profile.hasOperator) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = withOperator, onCheckedChange = { withOperator = it })
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(
                                listOfNotNull(
                                    profile.operatorName,
                                    profile.operatorRole,
                                    profile.rocSiteId,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Clear this when setting up somebody else's phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Text(
                    "This phone keeps its own entity id and its identity on the bus — a profile " +
                        "configures a phone, it does not clone one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApply(withOperator) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * What an export is about to write, before it writes it.
 *
 * The counterpart to [ImportProfileDialog], and the more important half: an import lands on one phone
 * somebody is holding, while an export writes a file to shared storage that will be mailed, synced and
 * forwarded. The two switches are **off by default** for that reason — the common errand is handing
 * over endpoints and rates, and that file should carry no credential and nobody's name.
 *
 * Each row states the consequence rather than an adjective, the rule the rest of this app follows: the
 * key is somebody's to pay for, and the identity is what other stations will see beside a checklist tick.
 */
@Composable
fun ExportProfileDialog(
    hasMapTilerKey: Boolean,
    operatorSummary: String?,
    onExport: (withSecrets: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var withSecrets by remember { mutableStateOf(false) }
    val offerable = hasMapTilerKey || operatorSummary != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export settings?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Writes a file to Downloads, which other apps on this phone can read and a cloud " +
                        "backup will sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (offerable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = withSecrets, onCheckedChange = { withSecrets = it })
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(
                                listOfNotNull(
                                    "map key".takeIf { hasMapTilerKey },
                                    "operator identity".takeIf { operatorSummary != null },
                                ).joinToString(" and ").replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            operatorSummary?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (hasMapTilerKey) {
                                    "Tick this only for a phone in your own fleet — the key is billed to you."
                                } else {
                                    "Tick this only for your own phone."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Text(
                    "This phone's entity id and its identity on the bus stay behind either way — a " +
                        "profile configures a phone, it does not clone one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onExport(withSecrets) }) { Text("Export") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The connection settings as a QR, for the other phone's camera.
 *
 * Says what is *not* in it as plainly as what is: somebody who scans this and then wonders why their
 * rates did not come across should not have to find out by comparing two screens.
 */
@Composable
fun ConnectionQrDialog(payload: String, onDismiss: () -> Unit) {
    val code = remember(payload) { qrBitmap(payload) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection QR") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (code == null) {
                    Text("These settings are too long for a QR — use the exported file instead.")
                } else {
                    Image(bitmap = code, contentDescription = "Connection settings", modifier = Modifier.size(260.dp))
                    Text(
                        "Scan from the other phone: Settings → Scan a connection QR.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Carries the realm, router endpoints and source ids. Switched-off subjects, " +
                            "rates, QoS overrides and annotation buttons need the exported file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
