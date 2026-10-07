package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.zapstore.app.R
import dev.zapstore.app.ZapDanger
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapTextSecondary

@Composable
fun UninstallProfilesDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ZapRadius.lg))
                .background(ZapSurface1)
                .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.lg))
                .padding(ZapSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.uninstall_title, name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.uninstall_other_profiles),
                color = ZapTextSecondary,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.install_cancel), color = ZapTextSecondary)
                }
                TextButton(onClick = onConfirm, modifier = Modifier.testTag("uninstallConfirm")) {
                    Text(stringResource(R.string.uninstall), color = ZapDanger)
                }
            }
        }
    }
}
