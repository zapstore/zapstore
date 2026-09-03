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
import android.content.ClipData
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
    val noProfileApps = stringResource(R.string.no_profile_apps)
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
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
            .background(ZapCanvas)
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
                        .background(ZapSurface2),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.profileLoading) {
                        LoadingIndicator()
                    } else {
                        Text(
                            text = stringResource(R.string.profile_not_found),
                            color = ZapTextSecondary,
                        )
                    }
                }
            }
            state.error?.let { error -> item { StatusText(error) } }
            return@LazyColumn
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAvatar(
                    pubkey = state.pubkey,
                    name = profile.displayName ?: profile.name ?: state.pubkey,
                    pictureUrl = profile.picture,
                    profileVersion = profile.event.id,
                    size = 84.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = profile.displayName ?: profile.name ?: state.pubkey,
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                    )
                    profile.name?.takeIf { it != profile.displayName }?.let {
                        StatusText("@$it")
                    }
                }
            }
        }

        profile.about?.takeIf(String::isNotBlank)?.let { about ->
            item {
                MarkdownText(
                    value = about,
                    color = ZapTextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    onOpenUrl = onOpenUrl,
                    collapsible = true,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        releaseFeed(
            state = state.releaseFeed,
            emptyMessage = noProfileApps,
            repository = repository,
            onAppClick = onAppClick,
            modifier = Modifier.padding(horizontal = 16.dp),
            showAuthor = false,
        )
        state.error?.let { error -> item { StatusText(error) } }

        item {
            val npub = remember(state.pubkey) { state.pubkey.toNpub() }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ZapSurface1)
                    .border(1.dp, ZapLine, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                profile.website?.let {
                    InfoRow(stringResource(R.string.website), it, link = it, onOpenUrl = onOpenUrl)
                }
                profile.nip05?.let {
                    InfoRow(stringResource(R.string.nip05), it, onOpenUrl = onOpenUrl)
                }
                InfoRow(
                    label = stringResource(R.string.npub),
                    value = npub,
                    onCopy = {
                        coroutineScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("npub", npub)))
                        }
                    },
                )
            }
        }
    }
}


@Composable
private fun ProfileAvatar(
    pubkey: String,
    name: String,
    pictureUrl: String?,
    profileVersion: String,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(ZapSurface2),
        contentAlignment = Alignment.Center,
    ) {
        ProfileImage(
            pubkey = pubkey,
            pictureUrl = pictureUrl,
            profileVersion = profileVersion,
            contentDescription = stringResource(R.string.profile_avatar_description, name),
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
        )
    }
}

