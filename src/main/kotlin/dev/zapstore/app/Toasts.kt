package dev.zapstore.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class ToastKind { Success, Info, Warning, Error }

/**
 * One short message; [action] is a single text button, such as Copy on an error. A [sticky] one
 * stays until it is closed or replaced: an error should not vanish before it is read.
 */
data class Toast(
    val message: String,
    val kind: ToastKind = ToastKind.Success,
    val actionLabel: String? = null,
    val sticky: Boolean = kind == ToastKind.Error,
    val action: (() -> Unit)? = null,
)

/** Short messages after an action. Anything may post one; the activity shows the latest at the top. */
object Toasts {
    private val messages = MutableSharedFlow<Toast>(extraBufferCapacity = 8)
    val flow: SharedFlow<Toast> = messages.asSharedFlow()

    fun show(message: String, kind: ToastKind = ToastKind.Success) {
        messages.tryEmit(Toast(message, kind))
    }

    fun show(toast: Toast) {
        messages.tryEmit(toast)
    }
}
