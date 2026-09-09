package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.rise.logline.calibrate.CalibrationChange
import se.rise.logline.calibrate.ChangeArea

/**
 * What this edit replaces, and a way to put it back.
 *
 * Shown only where there is something to say: an unedited platform, or one being created, renders
 * nothing at all. That is deliberate — a permanent "no changes" panel is a row of furniture people
 * learn to look past, and the point of these lines is that seeing one means something happened.
 *
 * Not a `StatusLine`. That carries an icon and this codebase reserves an icon for a warning or a
 * failure; a change is neither. It is a statement about what is in the draft, which is what the
 * quieter surface says.
 */
@Composable
fun ChangeRows(
    changes: List<CalibrationChange>,
    area: ChangeArea?,
    onRevert: (CalibrationChange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = if (area == null) changes else changes.filter { it.area == area }
    if (shown.isEmpty()) return

    // **Grouped by what a revert would actually put back**, which is an area and — for sensors — one
    // frame id. A moved zero and the accuracy it was measured to are two statements about one
    // measurement: they cannot be reverted separately, so offering two buttons that do the same
    // thing would be describing the model wrongly. Found on the phone, where reverting the position
    // row correctly brought the provenance back with it and left the second button doing nothing new.
    val groups = shown.groupBy { it.area to it.frameId }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { (_, rows) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        rows.forEach { change ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(change.summary, style = MaterialTheme.typography.bodyMedium)
                        // What is being replaced, stated in full rather than as a delta — a delta
                        // tells you how far it moved and not what it was, and only one of those can
                        // be checked against a chart or a survey.
                            change.was?.let {
                                Text(
                                    "was $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        // The consequence, in the error colour, because it is the half nothing else
                        // on the screen would mention: a covariance that stops going out, or a key
                        // every consumer is subscribed to moving.
                        change.cost?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        }
                    }
                    // One per group, and only where there is something to go back to. A first
                    // capture replaces nothing, and a button that would undo an addition into a
                    // blank is not a revert.
                    rows.firstOrNull { it.was != null }?.let { revertable ->
                        TextButton(onClick = { onRevert(revertable) }) { Text("Revert") }
                    }
                }
            }
        }
    }
}
