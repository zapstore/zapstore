package dev.zapstore.iolite

/** `d` tags of the device-private events (`SPEC.md#device-private-events`). */
object DeviceEvents {
    const val STATE = "zapstore-device-state"
    const val KEY_BACKUP = "zapstore-device-key-backup"
    const val BOOKMARKS = "zapstore-bookmarks"
    const val UPDATE_IGNORE = "zapstore-update-ignore"

    /** The `name` tag; `Saved Apps` is the Flutter name for bookmarks. */
    fun listName(identifier: String): String = when (identifier) {
        BOOKMARKS -> "Saved Apps"
        UPDATE_IGNORE -> "Ignored Updates"
        else -> identifier
    }
}
