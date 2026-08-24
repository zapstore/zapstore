package dev.zapstore.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val ZapBackground = Color(0xFF0A0F1A)
val ZapSurface = Color(0xFF0F141B)
val ZapSurfaceVariant = Color(0xFF161C27)
val ZapSurfaceRaised = Color(0xFF242424)
val ZapOutline = Color(0xFF2D3748)
val ZapOutlineStrong = Color(0xFF3A4654)
val ZapPrimary = Color(0xFF5A58FE)
val ZapPrimaryHover = Color(0xFF4542FF)
val ZapActionForeground = Color.White
val ZapText = Color(0xFFE8EAED)
val ZapMuted = Color(0xFFB8BCC8)
val ZapSubtle = Color(0xFF838A97)
val ZapVerified = Color(0xFF1CD981)
val ZapWarning = Color(0xFFFFB338)
val ZapDanger = Color(0xFFFF4778)
val ZapFocus = Color(0xFF8280FF)
val ZapIconBackground = Color(0xFF161C27)
val ZapBackgroundGradient = Brush.linearGradient(
    colors = listOf(
        ZapBackground,
        Color(0xFF141A22),
        Color(0xFF111723),
    ),
)

val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val InterDisplayFontFamily = FontFamily(
    Font(R.font.inter_display_extra_bold, FontWeight.ExtraBold),
)

private val ZapColorScheme = darkColorScheme(
    primary = ZapPrimary,
    onPrimary = ZapActionForeground,
    primaryContainer = Color(0xFF25224E),
    onPrimaryContainer = Color(0xFFE4E3FF),
    secondary = ZapMuted,
    onSecondary = ZapBackground,
    tertiary = ZapVerified,
    onTertiary = ZapBackground,
    background = ZapBackground,
    onBackground = ZapText,
    surface = ZapSurface,
    onSurface = ZapText,
    surfaceVariant = ZapSurfaceVariant,
    onSurfaceVariant = ZapMuted,
    outline = ZapOutline,
    outlineVariant = ZapOutlineStrong,
    error = ZapDanger,
    onError = ZapBackground,
)

private val ZapTypography = Typography(
    displaySmall = Typography().displaySmall.copy(
        fontFamily = InterDisplayFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 27.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = Typography().headlineSmall.copy(
        fontFamily = InterDisplayFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = Typography().titleLarge.copy(
        fontFamily = InterDisplayFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = Typography().titleMedium.copy(
        fontFamily = InterDisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = Typography().bodyLarge.copy(
        fontFamily = InterFontFamily,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = Typography().bodyMedium.copy(
        fontFamily = InterFontFamily,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = Typography().bodySmall.copy(
        fontFamily = InterFontFamily,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = Typography().labelLarge.copy(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    labelMedium = Typography().labelMedium.copy(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = Typography().labelSmall.copy(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
)

private val ZapShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
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
