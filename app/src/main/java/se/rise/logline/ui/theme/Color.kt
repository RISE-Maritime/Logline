package se.rise.logline.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's own colours, taken from the launcher icon: a near-white mark on a #05377A–#012E69 navy.
 *
 * Blue rather than Material's baseline purple, and fixed rather than wallpaper-derived — see the note
 * on `dynamicColor` in `Theme.kt`.
 *
 * The surface roles are all spelled out rather than left to default. `Card` draws on
 * `surfaceContainer*`, and those defaults are the baseline *purple* neutrals — leaving them would have
 * produced a blue app with lavender cards, which is worse than either.
 */

// Primary — the blue everything leans on: buttons, section headings, the "Publishing" state.
val BluePrimaryLight = Color(0xFF0B57A4)
val BlueOnPrimaryLight = Color(0xFFFFFFFF)
val BluePrimaryContainerLight = Color(0xFFD3E3FF)
val BlueOnPrimaryContainerLight = Color(0xFF001C3A)

val BluePrimaryDark = Color(0xFFA6C8FF)
val BlueOnPrimaryDark = Color(0xFF00325B)
val BluePrimaryContainerDark = Color(0xFF004881)
val BlueOnPrimaryContainerDark = Color(0xFFD3E3FF)

// Secondary — a desaturated blue-grey, for the quieter chrome.
val BlueSecondaryLight = Color(0xFF4F5F78)
val BlueOnSecondaryLight = Color(0xFFFFFFFF)
val BlueSecondaryContainerLight = Color(0xFFD6E3FF)
val BlueOnSecondaryContainerLight = Color(0xFF0B1C31)

val BlueSecondaryDark = Color(0xFFB7C7E4)
val BlueOnSecondaryDark = Color(0xFF213047)
val BlueSecondaryContainerDark = Color(0xFF37475F)
val BlueOnSecondaryContainerDark = Color(0xFFD6E3FF)

// Tertiary — deliberately warm, because it is what `StatusTone.Warning` paints. A cool tertiary sat
// close enough to primary that a warning read as just another blue label.
val AmberTertiaryLight = Color(0xFF7C5800)
val AmberOnTertiaryLight = Color(0xFFFFFFFF)
val AmberTertiaryContainerLight = Color(0xFFFFDEA6)
val AmberOnTertiaryContainerLight = Color(0xFF271900)

val AmberTertiaryDark = Color(0xFFF9BD4A)
val AmberOnTertiaryDark = Color(0xFF422C00)
val AmberTertiaryContainerDark = Color(0xFF5E4200)
val AmberOnTertiaryContainerDark = Color(0xFFFFDEA6)

// Error — Material's standard red. A dropped sample and a lost router must not look like anything else.
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

// Neutrals, with a faint blue cast so the greys sit with the blue rather than against it.
val SurfaceLight = Color(0xFFFBFCFF)
val OnSurfaceLight = Color(0xFF1A1C1E)
val SurfaceVariantLight = Color(0xFFDFE2EB)
val OnSurfaceVariantLight = Color(0xFF43474E)
val OutlineLight = Color(0xFF73777F)
val OutlineVariantLight = Color(0xFFC3C7CF)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF3F4F9)
val SurfaceContainerLight = Color(0xFFEDEFF4)
val SurfaceContainerHighLight = Color(0xFFE7E9EE)
val SurfaceContainerHighestLight = Color(0xFFE1E3E9)
val InverseSurfaceLight = Color(0xFF2F3033)
val InverseOnSurfaceLight = Color(0xFFF1F0F4)
val InversePrimaryLight = Color(0xFFA6C8FF)

val SurfaceDarkColor = Color(0xFF101418)
val OnSurfaceDark = Color(0xFFE0E2E8)
val SurfaceVariantDark = Color(0xFF42474E)
val OnSurfaceVariantDark = Color(0xFFC2C7CF)
val OutlineDark = Color(0xFF8C9199)
val OutlineVariantDark = Color(0xFF42474E)
val SurfaceContainerLowestDark = Color(0xFF0B0F13)
val SurfaceContainerLowDark = Color(0xFF191C20)
val SurfaceContainerDark = Color(0xFF1D2024)
val SurfaceContainerHighDark = Color(0xFF272A2F)
val SurfaceContainerHighestDark = Color(0xFF32353A)
val InverseSurfaceDark = Color(0xFFE0E2E8)
val InverseOnSurfaceDark = Color(0xFF2E3135)
val InversePrimaryDark = Color(0xFF285EA7)
