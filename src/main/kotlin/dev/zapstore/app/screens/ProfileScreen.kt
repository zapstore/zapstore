package dev.zapstore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

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
            .background(ZapBackgroundGradient)
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
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileAvatar(
                        pubkey = state.pubkey,
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
                    collapsible = true,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ZapSurface.copy(alpha = 0.8f))
                    .border(1.dp, ZapOutline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
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
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        state.error?.let { error -> item { StatusText(error) } }
    }
}


@Composable
private fun ProfileAvatar(
    pubkey: String,
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
        ProfileImage(
            pubkey = pubkey,
            pictureUrl = pictureUrl,
            contentDescription = stringResource(R.string.profile_avatar_description, name),
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
        )
    }
}

