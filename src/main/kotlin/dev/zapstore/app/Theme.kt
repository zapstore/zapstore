package dev.zapstore.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ZapBackground = Color(0xFF0A0F1A)
val ZapSurface = Color(0xFF0F141B)
val ZapSurfaceVariant = Color(0xFF161C27)
val ZapOutline = Color(0xFF2D3748)
val ZapPrimary = Color(0xFF3A6FCC)
val ZapText = Color(0xFFE8EAED)
val ZapMuted = Color(0xFFB8BCC8)
val ZapIconBackground = Color(0xFF2D2D2D)

val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val InterDisplayFontFamily = FontFamily(
    Font(R.font.inter_display_extra_bold, FontWeight.ExtraBold),
)

private val ZapColorScheme = darkColorScheme(
    primary = ZapPrimary,
    onPrimary = Color.White,
    background = ZapBackground,
    onBackground = ZapText,
    surface = ZapSurface,
    onSurface = ZapText,
    surfaceVariant = ZapSurfaceVariant,
    onSurfaceVariant = ZapMuted,
    outline = ZapOutline,
)

private val ZapTypography = Typography(
    displaySmall = Typography().displaySmall.copy(
        fontFamily = InterDisplayFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 27.sp,
    ),
    headlineSmall = Typography().headlineSmall.copy(
        fontFamily = InterDisplayFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
    ),
    titleLarge = Typography().titleLarge.copy(
        fontFamily = InterDisplayFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 21.sp,
    ),
    titleMedium = Typography().titleMedium.copy(
        fontFamily = InterDisplayFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 17.sp,
    ),
    bodyLarge = Typography().bodyLarge.copy(
        fontFamily = InterFontFamily,
        fontSize = 15.sp,
    ),
    bodyMedium = Typography().bodyMedium.copy(
        fontFamily = InterFontFamily,
        fontSize = 14.sp,
    ),
    bodySmall = Typography().bodySmall.copy(
        fontFamily = InterFontFamily,
        fontSize = 12.sp,
    ),
    labelLarge = Typography().labelLarge.copy(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
    ),
    labelMedium = Typography().labelMedium.copy(
        fontFamily = InterFontFamily,
        fontSize = 13.sp,
    ),
)

@Composable
fun ZapstoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZapColorScheme,
        typography = ZapTypography,
        content = content,
    )
}
