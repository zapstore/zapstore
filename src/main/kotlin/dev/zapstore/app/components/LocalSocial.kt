package dev.zapstore.app.components

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the user has social on in Settings. Screens leave out comments, zaps, other people's activity
 * and signing in while it is false. It defaults to true so a preview shows the full screen.
 */
val LocalSocial = compositionLocalOf { true }
