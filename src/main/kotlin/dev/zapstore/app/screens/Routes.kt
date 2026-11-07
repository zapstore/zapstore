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
    const val ME = "me"
    const val SETTINGS = "settings"
    /** Optional on the app route: the event id of a comment the page scrolls to once it is listed. */
    const val COMMENT_ARG = "comment"
    const val APP = "app/{$APP_ID_ARG}?$COMMENT_ARG={$COMMENT_ARG}"
    const val STACKS = "stacks"
    const val DIAGNOSTICS = "diagnostics"
    const val SAVED = "saved"
    const val STACK = "stack/{$STACK_AUTHOR_ARG}/{$STACK_ID_ARG}"
    const val PROFILE = "profile/{$PUBKEY_ARG}"

    /** Bottom-bar destinations, in bar order. */
    val TABS = listOf(HOME, UPDATES, ME)

    fun app(appId: String, commentId: String? = null): String =
        "app/${Uri.encode(appId)}" + commentId?.let { "?$COMMENT_ARG=${Uri.encode(it)}" }.orEmpty()
    fun stack(author: String, identifier: String): String = "stack/${Uri.encode(author)}/${Uri.encode(identifier)}"
    fun profile(pubkey: String): String = "profile/${Uri.encode(pubkey)}"
}
