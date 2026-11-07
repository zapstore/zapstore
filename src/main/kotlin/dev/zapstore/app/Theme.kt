package dev.zapstore.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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

// Zapstore Design System v3.13, "Open Shelf", dark stone (design/DESIGN.md).
// Colors, spacing and radii are generated into Tokens.kt from design/system/tokens.json.

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

/**
 * Screen width over Android's font size, in dp, under which a row cannot hold its text and a button
 * side by side. A 360 dp phone crosses it one step above the default size; a 411 dp one a step later.
 */
private const val ROOMY_TEXT_WIDTH = 360

/** The person's font size leaves too little width for text beside a button; stack them instead. */
@Composable
@ReadOnlyComposable
fun largeFont(): Boolean {
    val scale = LocalDensity.current.fontScale
    return scale > 1f && LocalConfiguration.current.screenWidthDp / scale < ROOMY_TEXT_WIDTH
}

/** This height at the person's font size, for a box that must hold one line of text. */
@Composable
@ReadOnlyComposable
fun Dp.withFontScale(): Dp = with(LocalDensity.current) { value.sp.toDp() }.coerceAtLeast(this)

// ---- Type ----
// DM Sans, variable wght + opsz. Android does not apply optical sizing on its own,
// so each style pins opsz to its size (axis range 9–40).

@OptIn(ExperimentalTextApi::class)
private fun dmSans(opsz: Float) = FontFamily(
    listOf(400, 500, 600, 700).flatMap { weight ->
        listOf(FontStyle.Normal to R.font.dm_sans, FontStyle.Italic to R.font.dm_sans_italic).map { (fontStyle, res) ->
            Font(
                res,
                weight = FontWeight(weight),
                style = fontStyle,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(weight),
                    FontVariation.Setting("opsz", opsz.coerceIn(9f, 40f)),
                ),
            )
        }
    },
)

private fun style(size: Number, line: Number, weight: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = dmSans(size.toFloat()),
    fontWeight = FontWeight(weight),
    fontSize = size.toDouble().sp,
    lineHeight = line.toDouble().sp,
    letterSpacing = tracking.em,
)

private val ZapTypography = Typography(
    displayLarge = style(38.25, 46.75, 600, -0.01), // Display
    displayMedium = style(38.25, 46.75, 600, -0.01),
    displaySmall = style(38.25, 46.75, 600, -0.02), // Figure
    headlineLarge = style(29.75, 38.25, 600, -0.01),
    headlineMedium = style(29.75, 38.25, 600, -0.01), // Headline
    headlineSmall = style(29.75, 36, 600, -0.025), // Title 1: screen titles
    titleLarge = style(23.25, 29.75, 600, -0.015), // Title 2: sheet titles
    titleMedium = style(19, 25.5, 600, -0.015), // Title 4: app name in detail heads
    titleSmall = style(16, 21.25, 600), // Row title
    bodyLarge = style(17, 25.5, 400), // Body
    bodyMedium = style(14.75, 21.25, 400), // Body small
    bodySmall = style(13.75, 19, 400), // Caption
    labelLarge = style(14.75, 21.25, 600), // Button
    labelMedium = style(13.75, 19, 500).copy(fontFeatureSettings = "tnum"), // Evidence
    labelSmall = style(12.75, 17, 600, 0.08), // Label: uppercase at the call site
)

/** Hashes, package ids, npubs: same face, tabular figures. Never monospace. */
val ZapEvidenceStyle: TextStyle = ZapTypography.labelMedium

/** Card and plan titles. */
val ZapTitle3Style: TextStyle = style(21.25, 27.5, 600, -0.015)

private val ZapColorScheme = darkColorScheme(
    primary = ZapAction,
    onPrimary = ZapOnAction,
    primaryContainer = ZapActionContainer,
    onPrimaryContainer = ZapActionText,
    inversePrimary = ZapActionPressed,
    secondary = ZapVerified,
    onSecondary = ZapCanvas,
    secondaryContainer = ZapSurface3,
    onSecondaryContainer = ZapText,
    tertiary = ZapWarning,
    onTertiary = ZapCanvas,
    background = ZapCanvas,
    onBackground = ZapText,
    surface = ZapCanvas,
    onSurface = ZapText,
    surfaceVariant = ZapSurface2,
    onSurfaceVariant = ZapText2,
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
