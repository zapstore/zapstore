package dev.zapstore.app.screens

import android.net.Uri

/** Navigation routes and the argument keys their ViewModels read from `SavedStateHandle`. */
object Routes {
    const val APP_ID_ARG = "appId"
    const val STACK_AUTHOR_ARG = "author"
    const val STACK_ID_ARG = "identifier"
    const val PUBKEY_ARG = "pubkey"

    const val HOME = "home"
    const val UPDATES = "updates"
    const val SETTINGS = "settings"
    const val APP = "app/{$APP_ID_ARG}"
    const val STACK = "stack/{$STACK_AUTHOR_ARG}/{$STACK_ID_ARG}"
    const val PROFILE = "profile/{$PUBKEY_ARG}"

    fun app(appId: String): String = "app/${Uri.encode(appId)}"
    fun stack(author: String, identifier: String): String = "stack/${Uri.encode(author)}/${Uri.encode(identifier)}"
    fun profile(pubkey: String): String = "profile/${Uri.encode(pubkey)}"
}
