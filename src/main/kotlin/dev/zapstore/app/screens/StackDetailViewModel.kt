package dev.zapstore.app.screens

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import dev.zapstore.iolite.AppCoordinate
import dev.zapstore.app.account.AccountStore
import dev.zapstore.app.ZapstoreApplication
import dev.zapstore.app.Toasts
import dev.zapstore.app.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.components.AppListState
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.app.AppConfig
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.Kinds
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.StackRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class StackDetailUiState(
    val stack: StackRecord? = null,
    val stackLoading: Boolean = true,
    /** Listings in stack order; apps not in the local catalog are omitted. */
    val apps: AppListState = AppListState(),
    val appsById: Map<String, AppRecord> = emptyMap(),
    val error: String? = null,
    /** Comments on this stack, newest first. Zaps on a stack are not read. */
    val comments: List<ActivityItem> = emptyList(),
    val commentProfiles: Map<String, ProfileRecord> = emptyMap(),
    val commentsLoading: Boolean = true,
    val commentsCanLoadMore: Boolean = false,
    /** The signed-in account wrote this stack, so it can remove apps and rename it. */
    val own: Boolean = false,
    val edit: StackEditUi = StackEditUi(),
)

/** A write to the account's own stack: the app id or [RENAME] in flight, the last failure, and finished renames. */
data class StackEditUi(val busy: String? = null, val error: String? = null, val renamed: Int = 0) {
    companion object {
        const val RENAME = "+rename"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StackDetailViewModel(
    private val iolite: Iolite,
    savedStateHandle: SavedStateHandle,
    private val account: AccountStore? = null,
    private val strings: (Int, Array<out Any>) -> String = { _, _ -> "" },
) : ViewModel() {
    private val author: String = requireNotNull(savedStateHandle[Routes.STACK_AUTHOR_ARG])
    private val identifier: String = requireNotNull(savedStateHandle[Routes.STACK_ID_ARG])

    private val stack = iolite.query(Query.stack(author, identifier), AppConfig.authorStacksQuery)

    private val appsById: Flow<Map<String, AppRecord>> = stack
        .map { it.items?.appIds.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest(iolite::observeAppsById)

    private val profiles = appsById.map { it.values.toList() }.authorProfiles(iolite)

    private val edit = MutableStateFlow(StackEditUi())
    private val own = (account?.account ?: flowOf(null)).map { it?.pubkey.equals(author, ignoreCase = true) }

    /** The stack's coordinate, which a comment on it names as its root `A`. */
    private val root = "${Kinds.AppStack}:$author:$identifier"
    private val commentLimit = MutableStateFlow(COMMENT_PAGE)

    /** The curator's and the catalog key's inboxes are asked as well, since a comment on the stack tells its curator. */
    private val comments: Flow<QueryState<List<CommentRecord>>> = combine(
        iolite.observeCatalogs().map { catalogs -> catalogs.firstOrNull()?.manifestPubkey }.distinctUntilChanged(),
        commentLimit,
        ::Pair,
    ).flatMapLatest { (catalogKey, limit) ->
        iolite.query(Query.stackComments(root, listOfNotNull(author, catalogKey), limit), AppConfig.commentQuery)
    }

    private val commentProfiles: Flow<Map<String, ProfileRecord>> = comments
        .map { state -> state.items.map(CommentRecord::pubkey).toSet() }
        .distinctUntilChanged()
        .flatMapLatest(iolite::observeProfiles)

    fun loadMoreComments() {
        if (uiState.value.commentsCanLoadMore) commentLimit.update { it + COMMENT_PAGE }
    }

    val uiState: StateFlow<StackDetailUiState> = combine(
        combine(stack, appsById, profiles, own, edit) { a, b, c, d, e -> Five(a, b, c, d, e) },
        comments,
        commentProfiles,
        commentLimit,
    ) { (stackState, appsById, profiles, own, edit), comments, commentProfiles, limit ->
        val stack = stackState.items
        StackDetailUiState(
            own = own,
            edit = edit,
            stack = stack,
            stackLoading = stack == null && !stackState.settled,
            apps = AppListState(
                apps = stack?.appIds.orEmpty().mapNotNull(appsById::get),
                profiles = profiles,
                loading = false,
            ),
            appsById = appsById,
            error = stackState.relayError(),
            comments = comments.items.filter { it.content.isNotBlank() }.map { ActivityItem(it.eventId, it.pubkey, it.createdAt, comment = it.content) },
            commentProfiles = commentProfiles,
            commentsLoading = comments.items.isEmpty() && !comments.settled,
            commentsCanLoadMore = comments.items.size >= limit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StackDetailUiState())

    fun removeApp(appId: String) {
        val stack = uiState.value.stack ?: return
        write(appId, stack.name, stack.apps.filter { it.appId != appId }, strings(R.string.toast_stack_removed, arrayOf(stack.name)))
    }

    fun rename(name: String) {
        val stack = uiState.value.stack ?: return
        val title = name.trim().takeIf { it.isNotEmpty() && it != stack.name } ?: return
        write(StackEditUi.RENAME, title, stack.apps, strings(R.string.toast_stack_renamed, arrayOf(title)))
    }

    private fun write(busy: String, name: String, apps: List<AppCoordinate>, done: String) {
        val store = account ?: return
        if (edit.value.busy != null) return
        edit.update { it.copy(busy = busy, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val signer = store.signer() ?: error(strings(R.string.sign_in_first, emptyArray()))
                iolite.setStack(signer, identifier, name, apps)
            }
            if (result.isSuccess) Toasts.show(done)
            edit.update {
                it.copy(
                    busy = null,
                    error = result.exceptionOrNull()?.let { e -> e.message ?: strings(R.string.post_failed, emptyArray()) },
                    renamed = if (result.isSuccess && busy == StackEditUi.RENAME) it.renamed + 1 else it.renamed,
                )
            }
        }
    }

    private data class Five<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    companion object {
        private const val COMMENT_PAGE = 20

        fun factory(iolite: Iolite): ViewModelProvider.Factory = viewModelFactory {
            initializer { StackDetailViewModel(iolite, createSavedStateHandle()) }
        }

        fun factory(app: ZapstoreApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                StackDetailViewModel(app.iolite, createSavedStateHandle(), app.account) { res, args -> app.getString(res, *args) }
            }
        }
    }
}
