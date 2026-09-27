package dev.zapstore.app.screens

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zapstore.app.MarkdownText
import dev.zapstore.app.R
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapSurface2
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.app.components.InfoRow
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.ProfileImage
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.appList
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.toNpub
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onOpenUrl: (String) -> Unit,
    onAppClick: (AppRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val noApps = stringResource(R.string.no_profile_apps)
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val npub = remember(state.pubkey) { state.pubkey.toNpub() }
    val profile = state.profile
    val title = profile?.displayNameOrNpub ?: npub

    LazyColumn(
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
                        .size(84.dp)
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
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                        maxLines = 2,
                    )
                    profile?.name?.takeIf { it != profile.displayName }?.let { StatusText("@$it") }
                    if (profile == null && state.profileLoading) LoadingIndicator(Modifier.padding(top = 8.dp))
                }
            }
        }

        profile?.about?.takeIf(String::isNotBlank)?.let { about ->
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

        appList(
            state = state.apps,
            emptyMessage = noApps,
            onAppClick = onAppClick,
            modifier = Modifier.padding(horizontal = 16.dp),
            showAuthor = false,
        )
        state.error?.let { item { StatusText(it, Modifier.padding(horizontal = 16.dp)) } }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ZapSurface1)
                    .border(1.dp, ZapLine, RoundedCornerShape(16.dp))
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
