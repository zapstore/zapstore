package dev.zapstore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    state: HomeUiState,
    repository: CatalogRepository? = null,
    onSearchQueryChanged: (String) -> Unit,
    onSearchSubmitted: () -> Unit,
    onSearchCleared: () -> Unit,
    onStackClick: (String) -> Unit,
    onAppClick: (identifier: String, author: String) -> Unit,
    onProfileClick: (String) -> Unit = {},
    onLoadMoreReleases: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val latestReleases = stringResource(R.string.latest_releases)
    val noReleases = stringResource(R.string.no_releases)
    LoadMoreReleasesWhenNearEnd(
        listState = listState,
        entries = state.releaseFeed.entries,
        loading = state.releaseFeed.loadingMore,
        canLoadMore = state.releaseFeed.canLoadMore,
        onLoadMore = onLoadMoreReleases,
    )
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ZapBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        state = listState,
    ) {
        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChanged,
                placeholder = { Text(stringResource(R.string.search_apps)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchSubmitted() }),
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        val clearDescription = stringResource(R.string.clear_search)
                        TextButton(
                            onClick = onSearchCleared,
                            modifier = Modifier.semantics {
                                contentDescription = clearDescription
                            },
                        ) {
                            Text(
                                text = "×",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("searchField"),
                shape = RoundedCornerShape(16.dp),
            )
        }

        state.searchMessage?.let { message ->
            item { StatusText(message, Modifier.testTag("searchStatus")) }
        }
        items(state.searchResults, key = { it.address }) { app ->
            AppCard(
                app = app,
                onClick = { onAppClick(app.identifier, app.event.pubKey) },
                onProfileClick = { onProfileClick(app.event.pubKey) },
                repository = repository,
                modifier = Modifier.testTag("searchResult:${app.address}"),
            )
        }

        item {
            SectionTitle(
                value = stringResource(R.string.curated_stacks),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item {
            when {
                state.stacks.isNotEmpty() -> {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(end = 8.dp),
                    ) {
                        items(state.stacks, key = { it.event.id }) { stack ->
                            StackCard(
                                stack = stack,
                                appsByAddress = state.stackApps,
                                onClick = { onStackClick(stack.event.id) },
                                modifier = Modifier.testTag("stack:${stack.event.id}"),
                            )
                        }
                    }
                }

                state.stacksLoading -> LoadingIndicator(Modifier.padding(vertical = 12.dp))
                state.stacksError != null -> StatusText(state.stacksError)
                else -> StatusText(stringResource(R.string.no_stacks))
            }
        }

        releaseFeed(
            state = state.releaseFeed,
            title = latestReleases,
            emptyMessage = noReleases,
            repository = repository,
            onAppClick = onAppClick,
            onProfileClick = onProfileClick,
            modifier = Modifier,
        )
    }
}

@Composable
fun StackDetailScreen(
    state: StackDetailUiState,
    repository: CatalogRepository? = null,
    onAppClick: (identifier: String, author: String) -> Unit,
    onProfileClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ZapBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val stack = state.stack
        if (stack == null) {
            item {
                if (state.stackLoading) {
                    LoadingIndicator(Modifier.padding(vertical = 20.dp))
                } else {
                    Text(
                        text = stringResource(R.string.stack_not_found),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            state.error?.let { error -> item { StatusText(error) } }
            return@LazyColumn
        }

        item {
            Text(
                text = stack.name,
                style = MaterialTheme.typography.displaySmall,
            )
        }
        item {
            StatusText(
                stack.description.ifBlank {
                    pluralStringResource(
                        R.plurals.curated_apps,
                        stack.appAddresses.size,
                        stack.appAddresses.size,
                    )
                },
            )
        }
        item {
            SectionTitle(
                value = stringResource(R.string.apps),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        items(
            items = stack.appAddresses.mapNotNull(state.appsByAddress::get),
            key = { it.address },
        ) { app ->
            AppCard(
                app = app,
                onClick = { onAppClick(app.identifier, app.event.pubKey) },
                onProfileClick = { onProfileClick(app.event.pubKey) },
                repository = repository,
                modifier = Modifier.testTag("stackApp:${app.address}"),
            )
        }
        if (state.appsByAddress.isEmpty()) {
            item {
                if (state.appsLoading) {
                    LoadingIndicator(Modifier.padding(vertical = 12.dp))
                } else {
                    StatusText(stringResource(R.string.no_stack_apps))
                }
            }
        }
        state.error?.let { error -> item { StatusText(error) } }
    }
}

@Composable
fun AppDetailScreen(
    state: AppDetailUiState,
    onOpenUrl: (String) -> Unit,
    repository: CatalogRepository? = null,
    onProfileClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ZapBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val app = state.app
        if (app == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ZapSurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.appLoading) {
                        LoadingIndicator()
                    } else {
                        Text(
                            text = stringResource(R.string.app_not_found),
                            color = ZapMuted,
                        )
                    }
                }
            }
            state.error?.let { error -> item { StatusText(error) } }
            return@LazyColumn
        }

        item {
            val authorName = rememberProfileDisplayName(app.event.pubKey, repository)
            Row(verticalAlignment = Alignment.Top) {
                AppIcon(
                    title = app.name,
                    iconUrl = app.iconUrl,
                    modifier = Modifier.size(84.dp),
                )
                Spacer(modifier.width(16.dp))
                Column(modifier.weight(1f)) {
                    AppNameWithByline(
                        name = app.name,
                        authorName = authorName,
                        nameStyle = MaterialTheme.typography.displaySmall,
                        onAuthorClick = { onProfileClick(app.event.pubKey) },
                        authorTestTag = "profile:${app.event.pubKey}",
                    )
                    state.release?.let { release ->
                        Spacer(modifier.height(10.dp))
                        VersionPill(version = release.version)
                    }
                }
            }
        }

        if (app.screenshots.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(app.screenshots.take(5), key = { it }) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = stringResource(R.string.screenshot_description, app.name),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(176.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(ZapIconBackground),
                        )
                    }
                }
            }
        }

        val description = app.event.content.ifBlank { app.summary }
        if (description.isNotBlank()) {
            item {
                MarkdownText(
                    value = description,
                    color = ZapMuted,
                    style = MaterialTheme.typography.bodyLarge,
                    onOpenUrl = onOpenUrl,
                )
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                HorizontalDivider(Modifier.weight(1f), color = ZapOutline)
                Text(
                    text = stringResource(R.string.latest_release).uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                HorizontalDivider(Modifier.weight(1f), color = ZapOutline)
            }
        }

        state.release?.let { release ->
            item { VersionRow(release) }
            if (release.notes.isNotBlank()) {
                item {
                    MarkdownText(
                        value = release.notes,
                        color = ZapMuted,
                        style = MaterialTheme.typography.bodyLarge,
                        onOpenUrl = onOpenUrl,
                    )
                }
            }
        } ?: item {
            Box(
                Modifier
                    .width(220.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZapSurfaceVariant),
            )
        }

        item {
            AppInfoCard(
                app = app,
                release = state.release,
                onOpenUrl = onOpenUrl,
                repository = repository,
                onProfileClick = { onProfileClick(app.event.pubKey) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        state.error?.let { error -> item { StatusText(error) } }
    }
}

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onOpenUrl: (String) -> Unit,
    repository: CatalogRepository? = null,
    onAppClick: (identifier: String, author: String) -> Unit = { _, _ -> },
    onLoadMoreReleases: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val profileApps = stringResource(R.string.profile_apps)
    val noProfileApps = stringResource(R.string.no_profile_apps)
    LoadMoreReleasesWhenNearEnd(
        listState = listState,
        entries = state.releaseFeed.entries,
        loading = state.releaseFeed.loadingMore,
        canLoadMore = state.releaseFeed.canLoadMore,
        onLoadMore = onLoadMoreReleases,
    )
    val bannerParallax = if (listState.firstVisibleItemIndex == 0) {
        listState.firstVisibleItemScrollOffset * 0.25f
    } else {
        0f
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ZapBackground)
            .navigationBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val profile = state.profile
        if (profile == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ZapSurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.profileLoading) {
                        LoadingIndicator()
                    } else {
                        Text(
                            text = stringResource(R.string.profile_not_found),
                            color = ZapMuted,
                        )
                    }
                }
            }
            state.error?.let { error -> item { StatusText(error) } }
            return@LazyColumn
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(ZapSurfaceVariant),
            ) {
                profile.banner?.takeIf(::isHttpUrl)?.let { bannerUrl ->
                    AsyncImage(
                        model = bannerUrl,
                        contentDescription = stringResource(R.string.profile_banner_description),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .graphicsLayer { translationY = bannerParallax }
                            .background(ZapSurfaceVariant),
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileAvatar(
                        name = profile.displayName ?: profile.name ?: state.pubkey,
                        pictureUrl = profile.picture,
                        size = 84.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = profile.displayName ?: profile.name ?: state.pubkey,
                            style = MaterialTheme.typography.displaySmall,
                        )
                        profile.name?.takeIf { it != profile.displayName }?.let {
                            StatusText("@$it")
                        }
                    }
                }
            }
        }

        profile.about?.takeIf(String::isNotBlank)?.let { about ->
            item {
                MarkdownText(
                    value = about,
                    color = ZapMuted,
                    style = MaterialTheme.typography.bodyLarge,
                    onOpenUrl = onOpenUrl,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ZapSurface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                profile.website?.let {
                    InfoRow(stringResource(R.string.website), it, link = it, onOpenUrl = onOpenUrl)
                }
                profile.nip05?.let {
                    InfoRow(stringResource(R.string.nip05), it, onOpenUrl = onOpenUrl)
                }
                InfoRow(stringResource(R.string.public_key), state.pubkey, onOpenUrl = onOpenUrl)
            }
        }

        releaseFeed(
            state = state.releaseFeed,
            title = profileApps,
            emptyMessage = noProfileApps,
            repository = repository,
            onAppClick = onAppClick,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        state.error?.let { error -> item { StatusText(error) } }
    }
}

private fun LazyListScope.releaseFeed(
    state: ReleaseFeedUiState,
    title: String,
    emptyMessage: String,
    repository: CatalogRepository?,
    onAppClick: (identifier: String, author: String) -> Unit,
    onProfileClick: (String) -> Unit = {},
    modifier: Modifier,
) {
    item {
        SectionTitle(
            value = title,
            modifier = modifier.padding(top = 4.dp),
        )
    }
    when {
        state.entries.isEmpty() && state.initialLoading -> item {
            LoadingIndicator(modifier.padding(vertical = 12.dp))
        }

        state.entries.isEmpty() -> item {
            StatusText(state.error ?: emptyMessage, modifier)
        }

        else -> {
            state.error?.let { error -> item { StatusText(error, modifier) } }
            items(state.entries, key = { "app:${it.app.event.id}" }) { entry ->
                val app = entry.app
                AppCard(
                    app = app,
                    release = entry.release,
                    onClick = { onAppClick(app.identifier, app.event.pubKey) },
                    onProfileClick = { onProfileClick(app.event.pubKey) },
                    repository = repository,
                    modifier = modifier.testTag("app:${app.event.id}"),
                )
            }
            if (state.loadingMore) {
                item(key = "release-loading") {
                    LoadingIndicator(
                        modifier
                            .testTag("releaseLoading")
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadMoreReleasesWhenNearEnd(
    listState: LazyListState,
    entries: List<ReleaseFeedEntry>,
    loading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
) {
    val prefetchKeys = remember(entries) {
        entries.takeLast(RELEASE_PREFETCH_THRESHOLD)
            .mapTo(mutableSetOf()) { "app:${it.app.event.id}" }
    }
    val nearEnd by remember(listState, prefetchKeys) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { it.key in prefetchKeys }
        }
    }
    LaunchedEffect(nearEnd, loading, canLoadMore) {
        if (nearEnd && !loading && canLoadMore) onLoadMore()
    }
}

private const val RELEASE_PREFETCH_THRESHOLD = 3

@Composable
private fun ProfileAvatar(
    name: String,
    pictureUrl: String?,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(ZapSurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            color = ZapMuted,
        )
        pictureUrl?.takeIf(::isHttpUrl)?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.profile_avatar_description, name),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        }
    }
}

@Composable
private fun VersionRow(release: ReleaseInfo) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ZapSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        StatusText(stringResource(R.string.version))
        Spacer(Modifier.width(6.dp))
        Text(
            text = release.version,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(6.dp))
        StatusText("(${formatDate(release.event.createdAt)})")
    }
}

@Composable
private fun AppInfoCard(
    app: AppInfo,
    release: ReleaseInfo?,
    onOpenUrl: (String) -> Unit,
    repository: CatalogRepository?,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ZapSurface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        InfoRow(
            label = stringResource(R.string.source),
            value = app.repository ?: stringResource(R.string.not_available),
            link = app.repository,
            onOpenUrl = onOpenUrl,
        )
        app.license?.let {
            InfoRow(stringResource(R.string.license), it, onOpenUrl = onOpenUrl)
        }
        InfoRow(stringResource(R.string.app_id), app.identifier, onOpenUrl = onOpenUrl)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            StatusText(stringResource(R.string.author), Modifier.weight(1f))
            ProfileComponent(
                pubkey = app.event.pubKey,
                repository = repository,
                modifier = Modifier.weight(1f),
                onClick = onProfileClick,
            )
        }
        release?.let {
            InfoRow(
                stringResource(R.string.release_date),
                formatDate(it.event.createdAt),
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    link: String? = null,
    onOpenUrl: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        StatusText(label, Modifier.weight(1f))
        Text(
            text = value,
            color = if (link?.let(::isHttpUrl) == true) ZapPrimary else ZapText,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (link?.let(::isHttpUrl) == true) {
                        Modifier.clickable { onOpenUrl(link) }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

private fun formatDate(createdAt: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(createdAt * 1_000))
