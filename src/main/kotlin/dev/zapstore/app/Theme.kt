package dev.zapstore.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Zapstore Design System v2 — "Graphite Shelf" (design/DESIGN.md, design/system/tokens.json)
// Dark only. Warm graphite surfaces carry the product; app artwork is the only saturated color.

// ---- Color ----

val ZapCanvas = Color(0xFF100F0D)

val ZapSurface1 = Color(0xFF191816) // Cards, search field, nav hover
val ZapSurface2 = Color(0xFF201E1C) // Nested containers, sheet body, segmented track
val ZapSurface3 = Color(0xFF292725) // Tonal buttons, icon plates, action-sheet tiles
val ZapSurface4 = Color(0xFF322F2D) // Hover on surface-3, switch track off, active segment

val ZapLine = Color(0x0FFFFFFF) // 6% white — default hairline
val ZapLineStrong = Color(0x1FFFFFFF) // 12% white — interactive edges
val ZapHighlight = Color(0x09FFFFFF) // 3.5% white — inset top highlight on in-flow cards

val ZapTextPrimary = Color(0xFFF3F0EB)
val ZapTextSecondary = Color(0xFFAAA59D)
val ZapTextTertiary = Color(0xFF97928A)
val ZapTextInverse = ZapCanvas

val ZapAction = Color(0xFF3065A6) // Buttons, switches on, active segment. White text (5.9:1)
val ZapActionHover = Color(0xFF3671BA)
val ZapActionPressed = Color(0xFF275286)
val ZapActionText = Color(0xFF73AAED) // Links, active nav icons, focus ring (7.9:1 on canvas)
val ZapActionTint = Color(0x293065A6) // 16% — selected chip fill, focus ring glow
val ZapOnAction = Color(0xFFFFFFFF)

val ZapVerified = Color(0xFF30A66F)
val ZapVerifiedTint = Color(0x1F30A66F) // 12%
val ZapWarning = Color(0xFFF2B95B)
val ZapWarningTint = Color(0x1FF2B95B) // 12%
val ZapDanger = Color(0xFFF0706E)
val ZapDangerTint = Color(0x1FF0706E) // 12%
val ZapDangerFill = Color(0xFF3A1C1C)

val ZapBrand = Color(0xFF173A61) // Logo field only; never a flat UI tint

val ZapFocus = ZapActionText

/** Surface-3 tonal fill used for the version pill (surface-2 when it sits directly on canvas). */
val ZapVersionPillBackground = ZapSurface3

// ---- Spacing ----
// 4px base unit ladder: tight inside groups, generous between them.
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
// Compact-density control heights (design/system/tokens.json "size"); Android is always at
// the ≤860px floor, so controls use the larger figure in each pair.
object ZapSize {
    val control = 44.dp
    val controlSmall = 40.dp
    val controlLarge = 48.dp
    val touchTarget = 44.dp
}

// ---- Radius ----
// Corners are soft and sized to the element; pills for anything pressed.
object ZapRadius {
    val xs = 6.dp // tags, kbd, copy buttons
    val sm = 10.dp // nav links, snackbar action
    val md = 14.dp // fields, list rows, action-sheet tiles, snackbar
    val lg = 20.dp // cards, panels
    val xl = 28.dp // sheets, hero stage
    val full = 999.dp // buttons, chips, badges, search, switches

    /** App/stack tiles are always 22% of their size (design/system/tokens.json radius.appTile). */
    const val appTileFraction = 0.22f
}

// ---- Type ----
// One workhorse grotesque (Wix Madefor Text) carries everything; hierarchy comes from size and weight.

private val ZapLetterSpacing = (-0.016).em

val WixMadeforTextFontFamily = FontFamily(
    Font(R.font.wix_madefor_text_regular, FontWeight.Normal),
    Font(R.font.wix_madefor_text_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.wix_madefor_text_medium, FontWeight.Medium),
    Font(R.font.wix_madefor_text_medium_italic, FontWeight.Medium, FontStyle.Italic),
    Font(R.font.wix_madefor_text_semibold, FontWeight.SemiBold),
    Font(R.font.wix_madefor_text_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
    Font(R.font.wix_madefor_text_bold, FontWeight.Bold),
    Font(R.font.wix_madefor_text_bold_italic, FontWeight.Bold, FontStyle.Italic),
    Font(R.font.wix_madefor_text_extrabold, FontWeight.ExtraBold),
    Font(R.font.wix_madefor_text_extrabold_italic, FontWeight.ExtraBold, FontStyle.Italic),
)

private val ZapColorScheme = darkColorScheme(
    primary = ZapAction,
    onPrimary = ZapOnAction,
    primaryContainer = ZapActionPressed,
    onPrimaryContainer = ZapOnAction,
    secondary = ZapTextSecondary,
    onSecondary = ZapCanvas,
    tertiary = ZapVerified,
    onTertiary = ZapCanvas,
    background = ZapCanvas,
    onBackground = ZapTextPrimary,
    surface = ZapSurface1,
    onSurface = ZapTextPrimary,
    surfaceVariant = ZapSurface2,
    onSurfaceVariant = ZapTextSecondary,
    outline = ZapLine,
    outlineVariant = ZapLineStrong,
    error = ZapDanger,
    onError = ZapTextPrimary,
    errorContainer = ZapDangerFill,
    onErrorContainer = ZapDanger,
)

// Sizes below follow design/system/tokens.json ("font.size"), converted 1rem = 16sp; Android
// heading1/display use the fluid-scale floor since there is no viewport to clamp against.
private val ZapTypography = Typography(
    // Display — hero headline only.
    displayLarge = Typography().displayLarge.copy(
        fontFamily = WixMadeforTextFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 38.sp,
        letterSpacing = ZapLetterSpacing,
    ),
    // Heading 1 — section and screen titles.
    displaySmall = Typography().displaySmall.copy(
        fontFamily = WixMadeforTextFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = ZapLetterSpacing,
    ),
    // Heading 2 — sheet titles, welcome card.
    headlineSmall = Typography().headlineSmall.copy(
        fontFamily = WixMadeforTextFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = ZapLetterSpacing,
    ),
    titleLarge = Typography().titleLarge.copy(
        fontFamily = WixMadeforTextFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = ZapLetterSpacing,
    ),
    // Heading 3 — app names in detail heads and rows.
    titleMedium = Typography().titleMedium.copy(
        fontFamily = WixMadeforTextFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = ZapLetterSpacing,
    ),
    // Body — 16sp on Android.
    bodyLarge = Typography().bodyLarge.copy(
        fontFamily = WixMadeforTextFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = ZapLetterSpacing,
    ),
    // Body small — descriptions in rows and cards.
    bodyMedium = Typography().bodyMedium.copy(
        fontFamily = WixMadeforTextFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = ZapLetterSpacing,
    ),
    // Caption — timestamps, secondary facts.
    bodySmall = Typography().bodySmall.copy(
        fontFamily = WixMadeforTextFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = ZapLetterSpacing,
    ),
    // Button label — 14sp/600.
    labelLarge = Typography().labelLarge.copy(
        fontFamily = WixMadeforTextFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = ZapLetterSpacing,
    ),
    // Evidence — verifiable values (versions, hashes, package IDs); tabular figures.
    labelMedium = Typography().labelMedium.copy(
        fontFamily = WixMadeforTextFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = ZapLetterSpacing,
        fontFeatureSettings = "tnum",
    ),
    // Label — list-group headers, table headers, nav groups. Uppercase at call sites.
    labelSmall = Typography().labelSmall.copy(
        fontFamily = WixMadeforTextFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = ZapLetterSpacing,
    ),
)

private val ZapShapes = Shapes(
    extraSmall = RoundedCornerShape(ZapRadius.xs),
    small = RoundedCornerShape(ZapRadius.sm),
    medium = RoundedCornerShape(ZapRadius.md),
    large = RoundedCornerShape(ZapRadius.lg),
    extraLarge = RoundedCornerShape(ZapRadius.xl),
)

@Composable
fun ZapstoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZapColorScheme,
        typography = ZapTypography,
        shapes = ZapShapes,
        content = content,
    )
}
