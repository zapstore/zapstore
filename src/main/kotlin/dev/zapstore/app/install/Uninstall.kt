package dev.zapstore.app.install

import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.core.net.toUri

/** True when this profile can see another one, so uninstall may leave the app there. */
fun profilesNeedMention(profileCount: Int): Boolean = profileCount > 1

fun Context.hasOtherProfiles(): Boolean = runCatching {
    profilesNeedMention(getSystemService(UserManager::class.java)?.userProfiles?.size ?: 1)
}.getOrDefault(false)

fun uninstallPackageUri(packageId: String): String = "package:$packageId"

fun uninstallIntent(packageId: String): Intent =
    Intent(Intent.ACTION_DELETE, uninstallPackageUri(packageId).toUri())
