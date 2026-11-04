package dev.zapstore.app.components

import android.content.ClipData
import dev.zapstore.app.R
import dev.zapstore.app.Toasts
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText3
import dev.zapstore.app.isHttpUrl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun InfoRow(
    label: String,
    value: String,
    link: String? = null,
    onOpenUrl: (String) -> Unit = {},
    onCopy: (() -> Unit)? = null,
    verticalPadding: Dp = 2.dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding),
    ) {
        StatusText(label)
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (link?.let(::isHttpUrl) == true) {
                Text(
                    text = value,
                    color = ZapActionText,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenUrl(link) },
                )
            } else {
                Text(
                    text = value,
                    color = ZapText,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onCopy != null) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.copy),
                    tint = ZapText3,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** Puts a value on the clipboard and says so with a toast. Takes the field's label and the value. */
@Composable
fun rememberCopier(): (label: String, value: String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    return remember(clipboard, scope, copied) {
        { label, value ->
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value))) }
            Toasts.show(copied)
        }
    }
}

/**
 * A value someone may want to check or paste elsewhere: the label above, the whole value below in
 * tabular figures (it wraps; a hash is never cut in the middle), and a copy icon at the end.
 * A [link] makes the value open instead of plain text.
 */
@Composable
fun EvidenceField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    link: String? = null,
    onOpenUrl: (String) -> Unit = {},
    copyable: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val copy = rememberCopier()
    val copyLabel = stringResource(R.string.copy)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1)) {
            Text(label, color = ZapText3, style = MaterialTheme.typography.labelMedium)
            val linked = link?.let(::isHttpUrl) == true
            val open: (() -> Unit)? = onClick ?: if (linked) ({ onOpenUrl(link.orEmpty()) }) else null
            Text(
                text = value,
                color = if (open != null) ZapActionText else ZapText,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                modifier = if (open != null) Modifier.clickable(onClick = open) else Modifier,
            )
        }
        if (copyable) {
            IconButton(onClick = { copy(label, value) }, modifier = Modifier.size(ZapSpacing.space10)) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = "$copyLabel $label",
                    tint = ZapText3,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

