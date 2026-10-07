package dev.zapstore.app.install

import java.util.concurrent.CopyOnWriteArrayList

data class InstallResult(
    val packageId: String,
    val success: Boolean,
    val message: String? = null,
    val awaitingUser: Boolean = false,
)

/** The status receiver and the app page share one process. Listeners are called on the receiver thread. */
object InstallEvents {
    private val listeners = CopyOnWriteArrayList<(InstallResult) -> Unit>()

    fun listen(block: (InstallResult) -> Unit): () -> Unit {
        listeners += block
        return { listeners -= block }
    }

    fun publish(result: InstallResult) {
        listeners.forEach { it(result) }
    }
}
