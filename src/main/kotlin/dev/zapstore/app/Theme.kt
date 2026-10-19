package dev.zapstore.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Zapstore Design System v3.4 — "Open Shelf" (design/DESIGN.md, design/system/android/ZapstoreTheme.kt).
// Dark only. Neutral near-black chrome; app artwork is the only saturated color.

// ---- Color ----

val ZapCanvas = Color(0xFF0F0F11)

val ZapSurface1 = Color(0xFF18181A) // Cards, search field
val ZapSurface2 = Color(0xFF1F1F22) // Nested containers, evidence cards, sheets, chips, nav bar
val ZapSurface3 = Color(0xFF27272A) // Tonal buttons, icon plates, action-sheet tiles
val ZapSurface4 = Color(0xFF303034) // Switch track off, active segment, grabbers

// Material roles are opaque, so the web's white-alpha hairlines are flattened onto the canvas.
val ZapLine = Color(0xFF202022)
val ZapLineStrong = Color(0xFF2E2E30)
val ZapHighlight = Color(0x08FFFFFF) // 3% white — inset top highlight on in-flow cards

val ZapTextPrimary = Color(0xFFECECEE)
val ZapTextSecondary = Color(0xFFA6A6AB)
val ZapTextTertiary = Color(0xFF8E8E94)

val ZapAction = Color(0xFF3570B8) // Buttons, switches on, progress. White text 5.05:1
val ZapActionHover = Color(0xFF3D78BF)
val ZapActionPressed = Color(0xFF2C5E9B) // Also the action text form on paper
val ZapActionText = Color(0xFF7DB1F0) // Links, active nav icons, focus ring
val ZapActionTint = Color(0x293570B8) // 16% — selected chip fill
val ZapActionContainer = Color(0xFF1D2633) // Action tint flattened over surface-1
val ZapOnAction = Color(0xFFFFFFFF)

val ZapVerified = Color(0xFF30A66F)
val ZapVerifiedTint = Color(0x1F30A66F)
val ZapWarning = Color(0xFFF2B95B)
val ZapWarningTint = Color(0x1FF2B95B)
val ZapDanger = Color(0xFFF0706E)
val ZapDangerTint = Color(0x1FF0706E)
val ZapDangerFill = Color(0xFF3A1C1C)
val ZapOnDangerFill = Color(0xFFFFD9D8)
val ZapBadge = Color(0xFFE5484D) // Update count on the nav bar, white bold text

/** Logo field and the version pill. Never a flat UI tint. */
val ZapBrand = Color(0xFF173A61)

// Paper is the snackbar only on Android.
val ZapPaper = Color(0xFFF3F0EB)
val ZapInk = Color(0xFF0B0B0C)

val ZapScrim = Color(0x99000000)

// ---- Spacing ----
// 4 dp base: tight inside groups, generous between them, more above a heading than below.
object ZapSpacing {
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 20.dp
    val space6 = 24.dp
    val space8 = 32.dp
    val space10 = 40.dp
    val space12 = 48.dp
    val space16 = 64.dp
}

// ---- Size ----
object ZapSize {
    /** Visual height of a button; Material pads it to [touchTarget]. */
    val button = 40.dp
    /** Fields. */
    val control = 44.dp
    /** A sheet's full-width primary action. */
    val controlLarge = 48.dp
    val touchTarget = 48.dp
}

// ---- Radius ----
object ZapRadius {
    val bar = 2.dp // progress tracks, grabbers
    val xs = 6.dp // tags, copy buttons
    val sm = 8.dp // snackbar action
    val control = 12.dp // buttons, fields, search
    val md = 14.dp // list rows, menus, snackbar
    val lg = 20.dp // cards, FAB
    val xl = 28.dp // sheets
    val full = 999.dp // chips, segmented, badges, switches, version pill, avatars

    /** App and stack tiles are 22% of their size. */
    const val appTileFraction = 0.22f
}

// ---- Type ----
// DM Sans, variable wght + opsz. Android does not apply optical sizing on its own,
// so each style pins opsz to its size (axis range 9–40).

@OptIn(ExperimentalTextApi::class)
private fun dmSans(opsz: Int) = FontFamily(
    listOf(400, 500, 600, 700).flatMap { weight ->
        listOf(FontStyle.Normal to R.font.dm_sans, FontStyle.Italic to R.font.dm_sans_italic).map { (fontStyle, res) ->
            Font(
                res,
                weight = FontWeight(weight),
                style = fontStyle,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(weight),
                    FontVariation.Setting("opsz", opsz.coerceIn(9, 40).toFloat()),
                ),
            )
        }
    },
)

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = dmSans(size),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

private val ZapTypography = Typography(
    displayLarge = style(36, 44, 600, -0.03),
    displayMedium = style(36, 44, 600, -0.03),
    displaySmall = style(36, 44, 600, -0.03), // Display, Figure
    headlineLarge = style(28, 36, 600, -0.03),
    headlineMedium = style(28, 36, 600, -0.03), // Headline
    headlineSmall = style(28, 34, 600, -0.025), // Title 1: screen titles
    titleLarge = style(22, 28, 600, -0.015), // Title 2: sheet titles
    titleMedium = style(18, 24, 600), // Title 4: app name in detail heads
    titleSmall = style(15, 20, 600), // Row title
    bodyLarge = style(16, 24, 400), // Body
    bodyMedium = style(14, 20, 400), // Body small
    bodySmall = style(13, 18, 400), // Caption
    labelLarge = style(14, 20, 600), // Button
    labelMedium = style(13, 18, 500).copy(fontFeatureSettings = "tnum"), // Evidence
    labelSmall = style(12, 16, 600, 0.08), // Label: uppercase at the call site
)

/** Hashes, package ids, npubs: same face, tabular figures. Never monospace. */
val ZapEvidenceStyle: TextStyle = ZapTypography.labelMedium

/** Card and plan titles. */
val ZapTitle3Style: TextStyle = style(20, 26, 600, -0.01)

private val ZapColorScheme = darkColorScheme(
    primary = ZapAction,
    onPrimary = ZapOnAction,
    primaryContainer = ZapActionContainer,
    onPrimaryContainer = ZapActionText,
    inversePrimary = ZapActionPressed,
    secondary = ZapVerified,
    onSecondary = ZapCanvas,
    secondaryContainer = ZapSurface3,
    onSecondaryContainer = ZapTextPrimary,
    tertiary = ZapWarning,
    onTertiary = ZapCanvas,
    background = ZapCanvas,
    onBackground = ZapTextPrimary,
    surface = ZapCanvas,
    onSurface = ZapTextPrimary,
    surfaceVariant = ZapSurface2,
    onSurfaceVariant = ZapTextSecondary,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = ZapCanvas,
    surfaceContainerLow = ZapSurface1,
    surfaceContainer = ZapSurface2,
    surfaceContainerHigh = ZapSurface3,
    surfaceContainerHighest = ZapSurface4,
    surfaceBright = ZapSurface4,
    surfaceDim = ZapCanvas,
    inverseSurface = ZapPaper,
    inverseOnSurface = ZapInk,
    outline = ZapLineStrong,
    outlineVariant = ZapLine,
    error = ZapDanger,
    onError = ZapCanvas,
    errorContainer = ZapDangerFill,
    onErrorContainer = ZapOnDangerFill,
    scrim = ZapScrim,
)

private val ZapShapes = Shapes(
    extraSmall = RoundedCornerShape(ZapRadius.xs),
    small = RoundedCornerShape(ZapRadius.control),
    medium = RoundedCornerShape(ZapRadius.md),
    large = RoundedCornerShape(ZapRadius.lg),
    extraLarge = RoundedCornerShape(ZapRadius.xl),
)

// Material buttons and SearchBar default to a full pill whatever Shapes says.
// Nothing you press to act is a pill here; pass this explicitly.
val ZapButtonShape = RoundedCornerShape(ZapRadius.control)


@Composable
fun ZapstoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZapColorScheme,
        typography = ZapTypography,
        shapes = ZapShapes,
        content = content,
    )
}
