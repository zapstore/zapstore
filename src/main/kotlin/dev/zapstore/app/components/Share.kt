package dev.zapstore.app.components

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.zapstore.app.R

/** The share icon every page with a link carries in its head. */
@Composable
fun ShareButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ZapIconButton(
        R.drawable.ic_share,
        stringResource(R.string.action_share),
        onClick,
        modifier = modifier.testTag("share"),
        style = ZapButtonStyle.Ghost,
    )
}

/** Opens the Android share sheet with [text]; the second argument is the sheet's title. */
@Composable
fun rememberSharer(): (text: String, title: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { text, title ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            runCatching { context.startActivity(Intent.createChooser(send, title)) }
        }
    }
}
