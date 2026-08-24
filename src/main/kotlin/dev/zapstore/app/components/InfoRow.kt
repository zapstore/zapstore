package dev.zapstore.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun InfoRow(
    label: String,
    value: String,
    link: String? = null,
    onOpenUrl: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        StatusText(label, Modifier.weight(1f))
        if (link?.let(::isHttpUrl) == true) {
            Text(
                text = value,
                color = ZapPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenUrl(link) },
            )
        } else {
            EvidenceText(
                value = value,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

