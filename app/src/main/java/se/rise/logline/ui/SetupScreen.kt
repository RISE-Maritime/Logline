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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.rise.logline.ui.components.NavRow
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.readAsOneItem

/**
 * Everything that is configured rather than operated.
 *
 * This screen exists so the start screen can stop being a menu. Home used to carry six full-width
 * outlined buttons under its status card — Settings, Rigs, Recordings, Checklists and the rest — which
 * made the first thing anyone sees both a dashboard and a table of contents, and made `Start
 * publishing` compete with five controls that do nothing during a run.
 *
 * The rows here are rows, not buttons, and each carries as a subtitle the state its old button label
 * used to smuggle into itself (`Rigs · SSRS18`). That is the whole of this app's button hierarchy
 * problem: with these gone, `START Publish & REC` is the only filled button in the resting state.
 *
 * Like every screen here it takes data and lambdas — no repository, no `Context`.
 */
@Composable
fun SetupScreen(
    /** The active rig and how many others publish, from `rigSummaryOf`. Null when there are none. */
    rigSummary: String?,
    /** Hidden rather than shown-and-inert: most installs never turn checklists on. */
    checklistsEnabled: Boolean,
    /** Which bus this phone is on, shown so the commonest question needs no tap. */
    identity: String,
    onOpenSettings: () -> Unit,
    onOpenRigs: () -> Unit,
    onOpenChecklists: () -> Unit,
    onOpenAnnotationButtons: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    ScreenScaffold(title = "Setup", bottomBar = bottomBar) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("This phone")
            Card(Modifier.fillMaxWidth()) {
                Column {
                    NavRow(
                        title = "Settings",
                        subtitle = identity,
                        onClick = onOpenSettings,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                    NavRow(
                        title = "Rigs",
                        subtitle = rigSummary ?: "No rigs described yet",
                        onClick = onOpenRigs,
                    )
                }
            }

            // Recordings used to head this section and is now the Files tab: the saved files are what
            // this app produces, and reaching them through a configuration screen said otherwise.
            SectionHeader("Data")
            Card(Modifier.fillMaxWidth()) {
                NavRow(
                    title = "Annotation buttons",
                    subtitle = "What the Events screen offers with one tap",
                    onClick = onOpenAnnotationButtons,
                )
            }

            if (checklistsEnabled) {
                SectionHeader("Collaboration")
                Card(Modifier.fillMaxWidth()) {
                    NavRow(
                        title = "Checklists",
                        subtitle = "Worked through with other stations, before a run",
                        onClick = onOpenChecklists,
                    )
                }
            }
        }
    }
}

