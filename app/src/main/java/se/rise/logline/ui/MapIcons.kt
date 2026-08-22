package se.rise.logline.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The glyphs `material-icons-core` does not carry, drawn here rather than pulled from a dependency.
 *
 * Four of them are the map toolbar's, which is what named the file; [IconImage] is not, and is here
 * anyway because the reason below is about the classpath rather than about maps, and a second file
 * would only copy it.
 *
 * Only `material-icons-core` is on the classpath, and it carries none of `MyLocation`, `Layers`,
 * `Fullscreen` or `Image` — those live in `material-icons-extended`, which is several thousand
 * vectors and a deprecated artifact besides. A few paths cost a few hundred bytes and nothing at
 * runtime, which is the same trade `BearingArrow` in `LiveDashboard.kt` already makes.
 *
 * All three are transcribed from Material Symbols on the standard 24x24 grid, so they sit at the same
 * optical weight as `Icons.Default.Info` beside them in the app bar. Keep that grid: a 24dp icon drawn
 * on a 20 or 26 unit viewport is subtly the wrong size next to every other icon in the app and reads as
 * sloppiness rather than as a mistake anybody can name.
 */
private fun mapIcon(name: String, block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit) =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

private val fill = SolidColor(Color.Black)

/** A crosshair round a dot: "put me in the middle and keep me there". */
val IconMyLocation: ImageVector by lazy {
    mapIcon("MyLocation") {
        path(fill = fill) {
            // The ring, as an annulus: outer circle clockwise, inner circle counter-clockwise, so the
            // non-zero winding rule punches the hole rather than filling it in.
            moveTo(12f, 8f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, 8f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, -8f)
            close()
            moveTo(12f, 10f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = false, 0f, 4f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = false, 0f, -4f)
            close()
        }
        // The four ticks, and the stub of axis each one sits on.
        path(fill = fill) {
            moveTo(11f, 2f); horizontalLineTo(13f); verticalLineTo(5f); horizontalLineTo(11f); close()
            moveTo(11f, 19f); horizontalLineTo(13f); verticalLineTo(22f); horizontalLineTo(11f); close()
            moveTo(2f, 11f); horizontalLineTo(5f); verticalLineTo(13f); horizontalLineTo(2f); close()
            moveTo(19f, 11f); horizontalLineTo(22f); verticalLineTo(13f); horizontalLineTo(19f); close()
        }
    }
}

/** Two stacked sheets: the base layer and what is drawn over it. */
val IconLayers: ImageVector by lazy {
    mapIcon("Layers") {
        path(fill = fill) {
            // The top sheet, a diamond seen in plan.
            moveTo(12f, 3f)
            lineTo(21f, 9f)
            lineTo(12f, 15f)
            lineTo(3f, 9f)
            close()
        }
        path(fill = fill) {
            // The one below it, showing only as an edge — enough to read as a stack.
            moveTo(12f, 17.2f)
            lineTo(4.6f, 12.3f)
            lineTo(3f, 13.4f)
            lineTo(12f, 19.4f)
            lineTo(21f, 13.4f)
            lineTo(19.4f, 12.3f)
            close()
        }
    }
}

/** Four corner brackets pointing outward — the standard "take the whole screen". */
val IconFullscreen: ImageVector by lazy {
    mapIcon("Fullscreen") {
        path(fill = fill) {
            moveTo(4f, 4f); horizontalLineTo(10f); verticalLineTo(6f)
            horizontalLineTo(6f); verticalLineTo(10f); horizontalLineTo(4f); close()
            moveTo(14f, 4f); horizontalLineTo(20f); verticalLineTo(10f)
            horizontalLineTo(18f); verticalLineTo(6f); horizontalLineTo(14f); close()
            moveTo(4f, 14f); horizontalLineTo(6f); verticalLineTo(18f)
            horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(4f); close()
            moveTo(18f, 14f); horizontalLineTo(20f); verticalLineTo(20f)
            horizontalLineTo(14f); verticalLineTo(18f); horizontalLineTo(18f); close()
        }
    }
}

/** The same brackets pointing inward, for the way back out of a full-screen chart. */
val IconFullscreenExit: ImageVector by lazy {
    mapIcon("FullscreenExit") {
        path(fill = fill) {
            moveTo(4f, 8f); horizontalLineTo(8f); verticalLineTo(4f)
            horizontalLineTo(10f); verticalLineTo(10f); horizontalLineTo(4f); close()
            moveTo(14f, 4f); horizontalLineTo(16f); verticalLineTo(8f)
            horizontalLineTo(20f); verticalLineTo(10f); horizontalLineTo(14f); close()
            moveTo(4f, 14f); horizontalLineTo(10f); verticalLineTo(20f)
            horizontalLineTo(8f); verticalLineTo(16f); horizontalLineTo(4f); close()
            moveTo(14f, 14f); horizontalLineTo(20f); verticalLineTo(16f)
            horizontalLineTo(16f); verticalLineTo(20f); horizontalLineTo(14f); close()
        }
    }
}

/**
 * One button in the chart's toolbar.
 *
 * 40dp rather than the icon's own 24: the visual weight is what this pass is reducing, and a tap target
 * shrunk to match is a control that gets missed on a moving boat.
 */
@Composable
internal fun MapIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    /** A toggle that is currently on, drawn with the app's selected fill. */
    active: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = if (active) {
            IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/**
 * A framed picture: a mountain and a sun. The placeholder where a platform has no photograph.
 *
 * Drawn as an outline rather than a filled block so an empty frame reads as *absence* — a solid
 * rectangle at 48dp in a list looks like a picture that failed to load.
 */
val IconImage: ImageVector by lazy {
    mapIcon("Image") {
        path(fill = fill) {
            // The frame, as an annulus: outer rectangle clockwise, inner counter-clockwise, so the
            // non-zero winding rule leaves the middle open for what is drawn inside it.
            moveTo(3f, 3f); horizontalLineTo(21f); verticalLineTo(21f); horizontalLineTo(3f); close()
            moveTo(5f, 5f); verticalLineTo(19f); horizontalLineTo(19f); verticalLineTo(5f); close()
        }
        path(fill = fill) {
            // A ridge line rising to the right, sitting on the frame's lower edge.
            moveTo(6f, 17f)
            lineTo(10.5f, 11f)
            lineTo(13.5f, 15f)
            lineTo(15.5f, 12.5f)
            lineTo(18f, 17f)
            close()
        }
        path(fill = fill) {
            // The sun, high on the left where the ridge is lowest.
            moveTo(8.5f, 6.5f)
            arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, 3f)
            arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 0f, -3f)
            close()
        }
    }
}
