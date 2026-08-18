package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.RigZero

/**
 * Typing the zero in by hand — from a chart, a survey, or another receiver.
 *
 * A dialog rather than fields on the page, and that is not only tidiness: the position on the page is
 * written by *captures*, and a text field bound to the same value would have the operator's half-typed
 * latitude replaced the moment an averaged fix landed.
 *
 * A typed position keeps [CaptureMethod.MANUAL] and no accuracy at all. Inventing one — even a
 * generous one — would let a typed position claim a quality nobody measured.
 */
@Composable
fun TypedPositionDialog(
    zero: RigZero?,
    onDismiss: () -> Unit,
    onConfirm: (RigZero) -> Unit,
) {
    var latitude by remember { mutableStateOf(zero?.latitude?.toString().orEmpty()) }
    var longitude by remember { mutableStateOf(zero?.longitude?.toString().orEmpty()) }
    var altitude by remember { mutableStateOf(zero?.altitudeM?.toString().orEmpty()) }

    val lat = latitude.toDoubleOrNull()
    val lon = longitude.toDoubleOrNull()
    val valid = lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type the zero point") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Decimal degrees, WGS84 — the datum every GNSS fix is already in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it },
                    label = { Text("Latitude") },
                    isError = latitude.isNotBlank() && (lat == null || lat !in -90.0..90.0),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it },
                    label = { Text("Longitude") },
                    isError = longitude.isNotBlank() && (lon == null || lon !in -180.0..180.0),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = altitude,
                    onValueChange = { altitude = it },
                    label = { Text("Altitude (optional)") },
                    suffix = { Text("m") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        RigZero(
                            latitude = lat ?: 0.0,
                            longitude = lon ?: 0.0,
                            altitudeM = altitude.toDoubleOrNull(),
                            // Typed, so there is nothing to say about accuracy or scatter. Absent is
                            // the honest answer; a number here would be a claim nobody measured.
                            accuracyM = null,
                            scatterM = null,
                            headingDeg = zero?.headingDeg ?: 0.0,
                            headingSource = zero?.headingSource ?: HeadingSource.MANUAL,
                            capture = CaptureMethod.MANUAL,
                            samples = 0,
                            capturedAtEpochMillis = System.currentTimeMillis(),
                        )
                    )
                },
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The rig's forward axis, typed — from a chart, a drawing, or a surveyed heading. */
@Composable
fun TypedHeadingDialog(
    initial: Double?,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var heading by remember { mutableStateOf(initial?.toString().orEmpty()) }
    val value = heading.toDoubleOrNull()
    val valid = value != null && value in 0.0..360.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type the forward axis") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Degrees true, 0-360, of the direction the rig's +X axis points.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = heading,
                    onValueChange = { heading = it },
                    label = { Text("Heading") },
                    suffix = { Text("°") },
                    isError = heading.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm((value ?: 0.0) % 360.0) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
