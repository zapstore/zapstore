package dev.zapstore.app.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zapstore.app.ZapstoreApplication

/** True once remote HTTP (CDN icons, avatars) can be fetched on the current network path. */
@Composable
fun rememberRemoteImagesReady(): Boolean {
    val context = LocalContext.current
    val network = remember(context) { (context.applicationContext as? ZapstoreApplication)?.network }
    if (network == null) return true
    return network.remoteReady.collectAsStateWithLifecycle().value
}
