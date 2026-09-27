package dev.zapstore.app.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.AppConfig
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.ZapRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ZapSummary(
    val count: Int = 0,
    val totalSats: Long = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

data class CommentsState(
    val threads: List<CommentThread> = emptyList(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
    val loading: Boolean = true,
    val error: CommentsError? = null,
)

data class AppDetailUiState(
    val app: AppRecord? = null,
    /** False until the first SQLite read; afterwards a null [app] means "not in the catalog". */
    val loaded: Boolean = false,
    val author: ProfileRecord? = null,
    val zaps: ZapSummary = ZapSummary(),
    val comments: CommentsState = CommentsState(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailViewModel(
    private val iolite: Iolite,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val appId: String = requireNotNull(savedStateHandle[Routes.APP_ID_ARG])

    private val app: Flow<AppRecord?> = iolite.observeApp(appId)

    private val author: Flow<ProfileRecord?> = app
        .map { it?.authorPubkey }
        .distinctUntilChanged()
        .flatMapLatest { pubkey ->
            if (pubkey == null) flowOf(null) else iolite.query(Query.profile(pubkey), AppConfig.profileQuery).map { it.items }
        }

    private val zaps: Flow<QueryState<List<ZapRecord>>?> = app
        .map { it?.coordinate }
        .distinctUntilChanged()
        .flatMapLatest { coordinate ->
            if (coordinate == null) flowOf(null) else iolite.query(Query.zaps(coordinate, limit = ZAP_LIMIT), AppConfig.zapQuery)
        }

    private val comments: Flow<QueryState<List<CommentRecord>>?> = app
        .map { it?.coordinate }
        .distinctUntilChanged()
        .flatMapLatest { coordinate ->
            if (coordinate == null) {
                flowOf(null)
            } else {
                iolite.query(Query.comments(coordinate, limit = COMMENT_LIMIT), AppConfig.commentQuery)
            }
        }

    private val commentProfiles: Flow<Map<String, ProfileRecord>> = comments
        .map { state -> state?.items.orEmpty().map(CommentRecord::pubkey).toSet() }
        .distinctUntilChanged()
        .flatMapLatest { pubkeys ->
            if (pubkeys.isEmpty()) {
                flowOf(emptyMap())
            } else {
                iolite.query(Query.profiles(pubkeys), AppConfig.commentProfileQuery).map { it.items }
            }
        }

    val uiState: StateFlow<AppDetailUiState> = combine(app, author, zaps, comments, commentProfiles) { app, author, zaps, comments, commentProfiles ->
        AppDetailUiState(
            app = app,
            loaded = true,
            author = author,
            zaps = ZapSummary(
                count = zaps?.items?.size ?: 0,
                totalSats = zaps?.items?.sumOf(ZapRecord::amountSats) ?: 0,
                loading = zaps?.isLoading ?: false,
                error = zaps?.error?.message,
            ),
            comments = CommentsState(
                threads = threadComments(comments?.items.orEmpty()),
                profiles = commentProfiles,
                loading = comments?.isLoading ?: false,
                error = comments?.error.toCommentsError(),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AppDetailUiState())

    fun retryComments() {
        iolite.refreshConnections()
    }

    companion object {
        /** The summary is a recent-activity signal, not a ledger. */
        private const val ZAP_LIMIT = 500
        private const val COMMENT_LIMIT = 50

        fun factory(iolite: Iolite): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppDetailViewModel(iolite, createSavedStateHandle()) }
        }
    }
}
