package dev.zapstore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionTitle(
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier,
    )
}

@Composable
fun SectionLabel(
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value.uppercase(),
        color = ZapSubtle,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}

@Composable
fun StatusText(
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        color = ZapMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}

@Composable
fun EvidenceText(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = ZapText,
) {
    Text(
        text = value,
        color = color,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
        modifier = modifier,
    )
}

@Composable
fun StatusBadge(
    value: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        color = foreground,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = ZapPrimary,
            strokeWidth = 2.dp,
        )
    }
}

fun isHttpUrl(value: String): Boolean =
    value.startsWith("https://") || value.startsWith("http://")
