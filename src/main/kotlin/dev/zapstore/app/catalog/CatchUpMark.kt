package dev.zapstore.app.catalog

import android.content.Context

/**
 * Remembers across launches that the phone holds only the catalog the APK came with, and no sync has
 * brought it up to date yet. The catalog screen stays up while this is set, so a first install opens on
 * the latest apps rather than on the ones from the day the APK was built.
 *
 * It is set only when the APK's snapshot is imported, which happens once, into an empty catalog. An
 * install that updated over an existing catalog never sets it.
 */
interface CatchUpMark {
    var pending: Boolean

    /** Kept for the life of the process. Tests, and builds that have no place to write. */
    class InMemory(override var pending: Boolean = false) : CatchUpMark
}

class PrefsCatchUpMark(context: Context) : CatchUpMark {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var pending: Boolean
        get() = prefs.getBoolean(PENDING, false)
        // Committed: the import that sets it never runs again, so a lost write would skip the wait for good.
        set(value) {
            prefs.edit().putBoolean(PENDING, value).commit()
        }

    private companion object {
        const val PREFS = "catalog_catch_up"
        const val PENDING = "pending"
    }
}
