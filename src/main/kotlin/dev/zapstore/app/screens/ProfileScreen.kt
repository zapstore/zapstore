package dev.zapstore.app.screens

import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapIconButton
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
import dev.zapstore.app.components.StackCard
import dev.zapstore.app.components.InfoRow
import dev.zapstore.app.components.LoadingIndicator
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
    /** Set on the Profile tab: the signed-in account's own page, with the Settings gear. */
    onSettingsClick: (() -> Unit)? = null,
    /** The device's saved apps, pinned first among the stacks on the Profile tab. */
    saved: SavedApps? = null,
    onSavedClick: () -> Unit = {},
    onLoadMoreActivity: () -> Unit = {},
) {
    val own = onSettingsClick != null
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
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
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
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                    )
                    profile?.name?.takeIf { it != profile.displayName }?.let { StatusText("@$it") }
                    if (profile == null && state.profileLoading) LoadingIndicator(Modifier.padding(top = 8.dp))
                }
                onSettingsClick?.let { SettingsGear(it) }
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
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        val showSaved = saved != null && saved.ids.isNotEmpty()
        if (state.stacks.isNotEmpty() || showSaved) {
            item(key = "stacks", contentType = "stacks") {
                Column {
                    GroupHeader(stringResource(R.string.stacks), Modifier.padding(horizontal = 16.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        if (showSaved) item(key = "saved") { SavedStackCard(saved, onSavedClick) }
                        items(state.stacks, key = { it.identifier }) { stack ->
                            StackCard(
                                stack = stack,
                                apps = state.stackApps,
                                onClick = { onStackClick(stack) },
                                modifier = Modifier.testTag("profileStack:${stack.identifier}"),
                            )
                        }
                    }
                }
            }
        }

        if (own && state.stacks.isEmpty() && !showSaved) {
            item(key = "noStacks") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    GroupHeader(stringResource(R.string.stacks))
                    StatusText(stringResource(R.string.own_stacks_empty), Modifier.testTag("ownStacksEmpty"))
                }
            }
        }

        if (!own || state.apps.apps.isNotEmpty()) {
            appList(
                state = state.apps,
                emptyMessage = noApps,
                onAppClick = onAppClick,
                modifier = Modifier.padding(horizontal = 16.dp),
                title = if (own) yourApps else null,
                showAuthor = false,
            )
        }
        state.error?.let { item { StatusText(it, Modifier.padding(horizontal = 16.dp)) } }

        // Every entry is this person's, so each row starts at the app and leaves their name out.
        item(key = "activityHeader", contentType = "header") {
            GroupHeader(stringResource(R.string.activity), Modifier.padding(horizontal = 16.dp).testTag("profileActivity"))
        }
        activityRows(
            items = state.activity,
            loading = state.activityLoading,
            canLoadMore = state.activityCanLoadMore,
            profiles = emptyMap(),
            stackApps = emptyMap(),
            emptyText = noActivity,
            onOpen = { item -> item.app?.let(onAppClick) },
            showWho = false,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ZapSurface1)
                    .border(1.dp, ZapLine, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

@Composable
internal fun SettingsGear(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ZapIconButton(
        icon = R.drawable.ic_settings,
        contentDescription = stringResource(R.string.settings),
        onClick = onClick,
        style = ZapButtonStyle.Ghost,
        modifier = modifier.testTag("openSettings"),
    )
}
