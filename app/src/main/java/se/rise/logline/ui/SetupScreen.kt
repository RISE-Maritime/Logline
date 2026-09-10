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
 * outlined buttons under its status card — Settings, Platforms, Recordings, Checklists and the rest — which
 * made the first thing anyone sees both a dashboard and a table of contents, and made `Start
 * publishing` compete with five controls that do nothing during a run.
 *
 * The rows here are rows, not buttons, and each carries as a subtitle the state its old button label
 * used to smuggle into itself (`Platforms · Sealog`). That is the whole of this app's button hierarchy
 * problem: with these gone, `START Publish & REC` is the only filled button in the resting state.
 *
 * Like every screen here it takes data and lambdas — no repository, no `Context`.
 */
@Composable
fun SetupScreen(
    /** The active platform and how many others publish, from `platformSummaryOf`. Null when there are none. */
    platformSummary: String?,
    /** Hidden rather than shown-and-inert: most installs never turn checklists on. */
    checklistsEnabled: Boolean,
    /** Which bus this phone is on, shown so the commonest question needs no tap. */
    identity: String,
    /** `1.0 (1) · debug build`, from `buildSummary`, so the version needs no tap either. */
    versionSummary: String,
    onOpenSettings: () -> Unit,
    onOpenPlatforms: () -> Unit,
    onOpenChecklists: () -> Unit,
    onOpenAbout: () -> Unit,
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
            // **The platform comes first, and it is not "this phone".** `entity_id` names the physical
            // thing the data is about, and a platform's geometry is about the platform — so filing it under the
            // phone was a category error as well as a matter of order. It is also the thing most of
            // this screen exists for: a phone's own settings are typed once, a platform is surveyed,
            // corrected and swapped.
            SectionHeader("Platforms")
            Card(Modifier.fillMaxWidth()) {
                NavRow(
                    title = "Platform library",
                    subtitle = platformSummary ?: "No platforms described yet",
                    onClick = onOpenPlatforms,
                )
            }

            SectionHeader("This phone")
            Card(Modifier.fillMaxWidth()) {
                NavRow(
                    title = "Settings",
                    subtitle = identity,
                    onClick = onOpenSettings,
                )
            }

            // The annotation buttons used to have a row here. They are edited from the Events tab's
            // own Quick marks header — "Edit", or "Add a button" when there are none — which is where
            // somebody is standing when they discover a button is missing. Two doors to one editor
            // meant the Setup one was found first and told you nothing about what it changed.

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

            // Last, because it is read once and never operated. The row states the build itself
            // rather than the word "Version": the version *is* what the row is for, and a phone
            // being asked which APK it holds should not need the tap to answer.
            SectionHeader("About")
            Card(Modifier.fillMaxWidth()) {
                NavRow(
                    title = "Logline",
                    subtitle = versionSummary,
                    onClick = onOpenAbout,
                )
            }
        }
    }
}

