package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.rise.logline.ui.components.AppMark
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.readAsOneItem

/**
 * Which build this phone is holding.
 *
 * The version is bumped by hand in `version.properties`, so the only way to tell two APKs apart is to
 * ask the phone — which until now it could not answer. Everything here is a fact about the install
 * rather than a setting, so nothing on this screen is editable.
 *
 * Deliberately no ⓘ: nothing was moved off this page, and an icon on every heading teaches the eye to
 * skip all of them.
 *
 * Like every screen here it takes data and lambdas — no repository, no `Context`. [appBuildOf] is
 * resolved once in `App()`.
 */
@Composable
fun AboutScreen(build: AppBuild, onBack: () -> Unit) {
    ScreenScaffold(title = "About", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppMark(size = 44.dp)
                Column {
                    Text("Logline", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "GNSS, IMU, barometer and battery on a Keelson bus",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionHeader("This build")
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Fact("Version", build.versionName)
                    Fact("Build", "${build.versionCode} · ${buildTypeOf(build)}")
                    Fact("Installed", formatBuildDate(build.installedAtMillis))
                    // Only when it differs. On a first install the two instants are the same, and a
                    // line restating the one above it is noise rather than a second fact.
                    if (build.updatedAtMillis != build.installedAtMillis) {
                        Fact("Updated", formatBuildDate(build.updatedAtMillis))
                    }
                    Fact("Application id", build.applicationId)
                }
            }
        }
    }
}

/** A label and its figure. Same shape as the detail rows on the start and subject screens. */
@Composable
private fun Fact(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .readAsOneItem("$label: $value"),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(128.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
