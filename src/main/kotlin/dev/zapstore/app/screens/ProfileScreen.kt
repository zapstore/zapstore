package dev.zapstore.app.screens

import dev.zapstore.app.components.shortDisplayName
import android.content.ClipData
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zapstore.app.MarkdownText
import dev.zapstore.app.R
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapSurface2
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText2
import dev.zapstore.app.components.GroupHeader
import dev.zapstore.app.components.ShareButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapIconButton
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.components.StackCard
import dev.zapstore.app.components.StackShelfFraction
import dev.zapstore.app.components.InfoRow
import dev.zapstore.app.components.ProfileImage
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.appList
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.StackRecord
import dev.zapstore.iolite.toNpub
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onOpenUrl: (String) -> Unit,
    onAppClick: (AppRecord) -> Unit,
    modifier: Modifier = Modifier,
    onStackClick: (StackRecord) -> Unit = {},
    /** Set on the Profile tab: opens the device's settings. This page is then the same shape as anyone else's. */
    onSettings: (() -> Unit)? = null,
    /** The device's saved apps, pinned first among the stacks on the Profile tab. */
    saved: SavedApps? = null,
    onSavedClick: () -> Unit = {},
    onLoadMoreActivity: () -> Unit = {},
    onShare: (() -> Unit)? = null,
) {
    val own = onSettings != null
    val noApps = stringResource(R.string.no_profile_apps)
    val yourApps = stringResource(R.string.your_apps)
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val npub = remember(state.pubkey) { state.pubkey.toNpub() }
    val profile = state.profile
    val title = shortDisplayName(state.pubkey, profile)

    val listState = rememberLazyListState()
    LoadMoreNearEnd(
        { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount },
        state.activityCanLoadMore,
        onLoadMoreActivity,
    )
    val noActivity = stringResource(R.string.no_person_activity)
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(ZapCanvas)
            .navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = ZapSpacing.space8),
        verticalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(ZapSpacing.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(ZapSpacing.space16)
                        .clip(CircleShape)
                        .background(ZapSurface2),
                    contentAlignment = Alignment.Center,
                ) {
                    ProfileImage(
                        pubkey = state.pubkey,
                        pictureUrl = profile?.picture,
                        profileVersion = profile?.eventId,
                        contentDescription = stringResource(R.string.profile_avatar_description, title),
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                    )
                }
                Spacer(Modifier.width(ZapSpacing.space4))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                    )
                    profile?.name?.takeIf { it != profile.displayName }?.let { StatusText("@$it") }
                }
                onShare?.let { ShareButton(it) }
                onSettings?.let {
                    ZapIconButton(
                        icon = R.drawable.ic_settings,
                        contentDescription = stringResource(R.string.settings),
                        onClick = it,
                        style = ZapButtonStyle.Ghost,
                        modifier = Modifier.testTag("openSettings"),
                    )
                }
            }
        }

        profile?.about?.takeIf(String::isNotBlank)?.let { about ->
            item {
                MarkdownText(
                    value = about,
                    color = ZapText2,
                    style = MaterialTheme.typography.bodyLarge,
                    onOpenUrl = onOpenUrl,
                    collapsible = true,
                    modifier = Modifier.padding(horizontal = ZapSpacing.space4),
                )
            }
        }

        val showSaved = saved != null && saved.ids.isNotEmpty()
        if (state.stacks.isNotEmpty() || showSaved) {
            item(key = "stacks", contentType = "stacks") {
                Column {
                    GroupHeader(stringResource(R.string.stacks), Modifier.padding(horizontal = ZapSpacing.space4))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                        contentPadding = PaddingValues(horizontal = ZapSpacing.space4),
                    ) {
                        if (showSaved) item(key = "saved") {
                            SavedStackCard(saved, onSavedClick, Modifier.fillParentMaxWidth(StackShelfFraction))
                        }
                        items(state.stacks, key = { it.identifier }) { stack ->
                            StackCard(
                                stack = stack,
                                apps = state.stackApps,
                                onClick = { onStackClick(stack) },
                                modifier = Modifier.fillParentMaxWidth(StackShelfFraction).testTag("profileStack:${stack.identifier}"),
                            )
                        }
                    }
                }
            }
        }

        if (!own || state.apps.apps.isNotEmpty()) {
            appList(
                state = state.apps,
                emptyMessage = noApps,
                onAppClick = onAppClick,
                modifier = Modifier.padding(horizontal = ZapSpacing.space4),
                title = if (own) yourApps else null,
                showAuthor = false,
            )
        }
        state.error?.let { item { StatusText(it, Modifier.padding(horizontal = ZapSpacing.space4)) } }

        // Every entry is this person's, so each row starts at the app and leaves their name out.
        if (state.activity.isNotEmpty()) item(key = "activityHeader", contentType = "header") {
            GroupHeader(stringResource(R.string.activity), Modifier.padding(horizontal = ZapSpacing.space4).testTag("profileActivity"))
        }
        if (state.activity.isNotEmpty()) activityRows(
            items = state.activity,
            loading = state.activityLoading,
            canLoadMore = state.activityCanLoadMore,
            profiles = emptyMap(),
            stackApps = emptyMap(),
            emptyText = noActivity,
            onOpen = { item -> item.app?.let(onAppClick) },
            showWho = false,
            modifier = Modifier.padding(horizontal = ZapSpacing.space4),
        )

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZapSpacing.space4)
                    .clip(RoundedCornerShape(ZapRadius.lg))
                    .background(ZapSurface1)
                    .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.lg))
                    .padding(horizontal = ZapSpacing.space4, vertical = ZapSpacing.space2),
            ) {
                profile?.website?.let { InfoRow(stringResource(R.string.website), it, link = it, onOpenUrl = onOpenUrl) }
                profile?.nip05?.let { InfoRow(stringResource(R.string.nip05), it) }
                InfoRow(
                    label = stringResource(R.string.npub),
                    value = npub,
                    onCopy = {
                        coroutineScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("npub", npub))) }
                    },
                )
            }
        }

    }
}
