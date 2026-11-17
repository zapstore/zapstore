package dev.zapstore.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Carries the social setting to everything that holds social state. Turning it off closes the reads first,
 * so nothing refills the tables, then signs out, then wipes what social reads stored. It runs again at
 * every start while off, so a wipe that was cut short finishes. Turning it on only reopens the reads:
 * the data comes back as screens ask for it.
 */
class SocialSwitch(
    private val social: Flow<Boolean>,
    private val setReads: (Boolean) -> Unit,
    private val signOut: () -> Unit,
    private val wipe: suspend () -> Unit,
) {
    /** Follows [social] until the collecting scope ends. */
    suspend fun run() {
        social.distinctUntilChanged().collect { on ->
            setReads(on)
            if (!on) {
                // A failure here would crash every launch while off, and stop this from following the setting.
                guarded("sign out") { signOut() }
                guarded("social wipe") { wipe() }
            }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.e(TAG, "$what failed", failure)
        }
    }

    private companion object {
        const val TAG = "SocialSwitch"
    }
}
