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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    onSearchQueryChanged: (String) -> Unit,
    onSearchSubmitted: () -> Unit,
    onSearchCleared: () -> Unit,
    onStackClick: (String) -> Unit,
    onAppClick: (identifier: String, author: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ZapBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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

                state.stacksLoading -> StatusText(stringResource(R.string.loading_stacks))
                state.stacksError != null -> StatusText(state.stacksError)
                else -> StatusText(stringResource(R.string.no_stacks))
            }
        }

        item {
            SectionTitle(
                value = stringResource(R.string.latest_releases),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item { StatusText(state.releasesMessage) }
        items(state.releases, key = { it.event.id }) { release ->
            val app = release.appIdentifier?.let(state.releaseApps::get) ?: AppInfo(release.event)
            AppCard(
                app = app,
                release = release,
                onClick = { onAppClick(app.identifier, app.event.pubKey) },
                modifier = Modifier.testTag("release:${release.event.id}"),
            )
        }
    }
}

@Composable
fun StackDetailScreen(
    state: StackDetailUiState,
    onBack: () -> Unit,
    onAppClick: (identifier: String, author: String) -> Unit,
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
        item { BackButton(onBack) }

        val stack = state.stack
        if (stack == null) {
            item {
                Text(
                    text = when {
                        state.stackLoading -> stringResource(R.string.loading_stack)
                        else -> stringResource(R.string.stack_not_found)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
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
                modifier = Modifier.testTag("stackApp:${app.address}"),
            )
        }
        if (state.appsByAddress.isEmpty()) {
            item {
                StatusText(
                    if (state.appsLoading) {
                        stringResource(R.string.loading_stack_apps)
                    } else {
                        stringResource(R.string.no_stack_apps)
                    },
                )
            }
        }
        state.error?.let { error -> item { StatusText(error) } }
    }
}

@Composable
fun AppDetailScreen(
    state: AppDetailUiState,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
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
        item { BackButton(onBack) }

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
                    Text(
                        text = if (state.appLoading) {
                            stringResource(R.string.loading_app)
                        } else {
                            stringResource(R.string.app_not_found)
                        },
                        color = ZapMuted,
                    )
                }
            }
            state.error?.let { error -> item { StatusText(error) } }
            return@LazyColumn
        }

        item {
            Row(verticalAlignment = Alignment.Top) {
                AppIcon(
                    title = app.name,
                    iconUrl = app.iconUrl,
                    modifier = Modifier.size(84.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.displaySmall,
                    )
                    StatusText(app.identifier)
                    state.release?.let { release ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = buildString {
                                append(release.version)
                                release.channel?.let { append(" · ").append(it) }
                            },
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(ZapPrimary)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        item {
            StatusText(
                stringResource(R.string.published_by, app.event.pubKey.take(16)),
            )
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
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        state.error?.let { error -> item { StatusText(error) } }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.testTag("backButton"),
    ) {
        Text("‹ ${stringResource(R.string.back)}")
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
        InfoRow(
            stringResource(R.string.author),
            "${app.event.pubKey.take(16)}…",
            onOpenUrl = onOpenUrl,
        )
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
