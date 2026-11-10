package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapVerified

/**
 * Says which apps Zapstore updated in the background, above the Updates section, where those apps
 * used to be listed. [names] are the apps; closing it is final, there is no history behind it.
 */
@Composable
fun AwayUpdatedNotice(names: List<String>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZapRadius.md))
            .background(ZapSurface1)
            .padding(start = ZapSpacing.space4, top = ZapSpacing.space2, bottom = ZapSpacing.space2, end = ZapSpacing.space1)
            .testTag("awayUpdated"),
    ) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = ZapVerified, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(pluralStringResource(R.plurals.away_updated_title, names.size, names.size), color = ZapText, style = MaterialTheme.typography.titleSmall)
            Text(
                names.joinToString(", "),
                color = ZapText2,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("awayUpdatedNames"),
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("dismissAwayUpdated")) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close), tint = ZapText2, modifier = Modifier.size(18.dp))
        }
    }
}
