package dev.zapstore.app.screens

import dev.zapstore.app.ZapSurface2
import dev.zapstore.app.components.rememberSharer
import dev.zapstore.app.components.rememberCopier
import dev.zapstore.iolite.neventOf
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zapstore.app.MarkdownText
import dev.zapstore.app.R
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3
import dev.zapstore.app.ZapWarning
import dev.zapstore.app.components.ProfileImage
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.VersionPill
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapIconButton
import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.ZapRecord
import java.text.NumberFormat

/*
 * The comments of an app or a stack, laid out as the Flutter app (master) lays them out: one wide
 * "Add Comment" button that opens a sheet, a card per comment with its replies indented under it on a
 * coloured line, and a Reply link on each card while signed in. A zap that carries a note is listed
 * among them; the rest stay in the zap row.
 */

/** The line and tint of a reply, by depth. Same hues as the Flutter app. */
private val ThreadColors = listOf(0xFF6366F1, 0xFF8B5CF6, 0xFFA855F7, 0xFFD946EF, 0xFFEC4899).map(::Color)

private val CardShape = RoundedCornerShape(ZapRadius.md)

/** What a sheet is writing: a new comment on the page's subject, or a reply to [parent]. */
internal data class CommentDraft(val parent: CommentRecord? = null, val parentAuthor: ProfileRecord? = null)

/** The wide button under the header that opens the comment sheet. */
@Composable
internal fun AddCommentButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ZapButton(
        text = stringResource(R.string.add_comment),
        onClick = onClick,
        style = ZapButtonStyle.Tonal,
        icon = R.drawable.ic_add_comment,
        modifier = modifier.fillMaxWidth().testTag("addComment"),
    )
}

/** A root comment with its replies under it, or one zap with a note: what the list holds per item. */
@Composable
internal fun FeedBlock(
    block: List<FeedEntry>,
    profiles: Map<String, ProfileRecord>,
    replyable: Boolean,
    onReply: (CommentRecord) -> Unit,
    onOpenUrl: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.forEach { entry ->
            when (entry) {
                is FeedEntry.Comment -> CommentCard(
                    comment = entry.comment,
                    author = profiles[entry.comment.pubkey],
                    depth = entry.depth,
                    onOpenUrl = onOpenUrl,
                    onAuthorClick = onAuthorClick,
                    onReply = if (replyable) ({ onReply(entry.comment) }) else null,
                )
                is FeedEntry.Zap -> ZapNoteCard(entry.zap, profiles[entry.zap.pubkey], onOpenUrl, onAuthorClick)
            }
        }
    }
}

/** A tappable card that signs in with Amber: what signing in lets the person do, then a chevron. */
@Composable
internal fun SignInPrompt(message: String, social: SocialUi, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.testTag("signInPrompt"), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(ZapSurface3)
                .clickable(enabled = !social.signingIn, role = Role.Button, onClick = onSignIn)
                .padding(ZapSpacing.space4)
                .testTag("signInPromptRow"),
        ) {
            if (social.signingIn) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ZapActionText)
            } else {
                Icon(painterResource(R.drawable.ic_login), contentDescription = null, tint = ZapActionText, modifier = Modifier.size(20.dp))
            }
            Text(message, color = ZapText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = ZapText3, modifier = Modifier.size(20.dp))
        }
        social.signInError?.let { StatusText(it, Modifier.testTag("signInError")) }
    }
}

/**
 * The sheet for a comment or a reply: a title with a close button, the comment being answered (for a
 * reply), then either the sign-in prompt or a four-line field, and one full-width post button. It closes
 * itself once [SocialUi.posted] moves.
 */
@Composable
internal fun CommentSheet(
    title: String,
    hint: String,
    signInMessage: String,
    postLabel: String,
    social: SocialUi,
    onPost: (String) -> Unit,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    preview: String? = null,
    /** The release a new comment is tied to, shown beside the title; null when it is about the whole app. */
    titleVersion: String? = null,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val opened = remember { social.posted }
    LaunchedEffect(social.posted) { if (social.posted != opened) onDismiss() }
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
            modifier = modifier
                .fillMaxWidth()
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5)
                .testTag("commentSheet"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    titleVersion?.let { VersionPill(it, Modifier.testTag("commentSheetVersion")) }
                }
                ZapIconButton(
                    icon = R.drawable.ic_close,
                    contentDescription = stringResource(R.string.close),
                    onClick = onDismiss,
                    style = ZapButtonStyle.Ghost,
                    modifier = Modifier.testTag("closeCommentSheet"),
                )
            }
            preview?.let { ParentPreview(it) }
            if (social.signedIn) {
                ZapTextArea(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = hint,
                    minLines = 4,
                    maxLines = 4,
                    autofocus = true,
                    modifier = Modifier.fillMaxWidth().testTag("commentField"),
                )
                social.error?.let { StatusText(it, Modifier.testTag("commentError")) }
            } else {
                SignInPrompt(signInMessage, social, onSignIn)
            }
            ZapButton(
                text = postLabel,
                onClick = { onPost(text) },
                enabled = social.signedIn && text.isNotBlank() && !social.posting,
                loading = social.posting,
                modifier = Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag("postComment"),
            )
        }
    }
}

/** The comment a reply answers, quoted on a line: the first three lines of it. */
@Composable
private fun ParentPreview(content: String) {
    Text(
        content,
        color = ZapText2,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZapRadius.sm))
            .background(ZapSurface3.copy(alpha = 0.5f))
            .drawBehind { drawRect(ZapText3, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = ZapSpacing.space3 + 3.dp, end = ZapSpacing.space3, top = ZapSpacing.space3, bottom = ZapSpacing.space3)
            .testTag("replyPreview"),
    )
}

/**
 * A comment as a card: avatar, name, the version it was written on (top-level only), the date, the
 * text, then Reply while signed in. A reply sits [depth] steps in, on a coloured line, with a tinted card.
 */
@Composable
internal fun CommentCard(
    comment: CommentRecord,
    author: ProfileRecord?,
    onOpenUrl: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    depth: Int = 0,
    onReply: (() -> Unit)? = null,
) {
    val reply = depth > 0
    val thread = ThreadColors[(depth - 1).coerceAtLeast(0) % ThreadColors.size]
    val name = shortDisplayName(comment.pubkey, author)
    var menu by remember { mutableStateOf(false) }
    val copy = rememberCopier()
    val share = rememberSharer()
    val haptics = LocalHapticFeedback.current
    val link = remember(comment.eventId, comment.pubkey) { neventOf(comment.eventId, comment.pubkey)?.let { "https://njump.me/$it" } }
    Box(modifier.fillMaxWidth().padding(start = 12.dp * depth).testTag("commentDepth:${comment.eventId}:$depth")) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(if (reply) thread.copy(alpha = 0.08f) else ZapSurface1)
                .then(if (reply) Modifier.drawBehind { drawRect(thread, size = Size(3.dp.toPx(), size.height)) } else Modifier.border(1.dp, ZapLine, CardShape))
                .pointerInput(comment.eventId) {
                    detectTapGestures(onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    })
                }
                .padding(start = if (reply) ZapSpacing.space3 + 3.dp else ZapSpacing.space3, top = ZapSpacing.space3, end = ZapSpacing.space3, bottom = ZapSpacing.space3)
                .testTag("comment:${comment.eventId}"),
        ) {
            CommentAvatar(comment.pubkey, author, name, if (reply) 28.dp else 32.dp, onAuthorClick)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!reply) {
                            comment.version?.let { version ->
                                Text(stringResource(R.string.comment_on_version), color = ZapText3, style = MaterialTheme.typography.bodySmall)
                                VersionPill(version, Modifier.testTag("commentVersion:${comment.eventId}"))
                            }
                        }
                    }
                    Text(formatDate(comment.createdAt), color = ZapText3, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                MarkdownText(value = comment.content, color = ZapText2, style = MaterialTheme.typography.bodyMedium, onOpenUrl = onOpenUrl)
                onReply?.let { ReplyLink(it, Modifier.testTag("reply:${comment.eventId}")) }
            }
        }
        // Long press: the text, a link to the comment, or the share sheet.
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = ZapSurface2) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.comment_copy_text)) },
                onClick = {
                    menu = false
                    copy(name, comment.content)
                },
                modifier = Modifier.testTag("commentCopyText"),
            )
            link?.let {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.comment_copy_link)) },
                    onClick = {
                        menu = false
                        copy(name, it)
                    },
                    modifier = Modifier.testTag("commentCopyLink"),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                onClick = {
                    menu = false
                    share(listOfNotNull(comment.content, link).joinToString("\n\n"), name)
                },
                modifier = Modifier.testTag("commentShare"),
            )
        }
    }
}

/**
 * A zap that came with a note, in the same card as a comment: the amount sits by the name, the note under it.
 */
@Composable
internal fun ZapNoteCard(
    zap: ZapRecord,
    author: ProfileRecord?,
    onOpenUrl: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = shortDisplayName(zap.pubkey, author)
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(ZapSurface1)
            .border(1.dp, ZapLine, CardShape)
            .padding(ZapSpacing.space3)
            .testTag("zapEntry:${zap.eventId}"),
    ) {
        CommentAvatar(zap.pubkey, author, name, 32.dp, onAuthorClick, tag = "zapAuthor:${zap.pubkey}")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, tint = ZapWarning, modifier = Modifier.size(14.dp))
                Text(
                    remember(zap.amountSats) { NumberFormat.getInstance().format(zap.amountSats) },
                    color = ZapWarning,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.testTag("zapSats:${zap.eventId}"),
                )
                Box(Modifier.weight(1f))
                Text(formatDate(zap.createdAt), color = ZapText3, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            MarkdownText(value = zap.note, color = ZapText2, style = MaterialTheme.typography.bodyMedium, onOpenUrl = onOpenUrl)
        }
    }
}

@Composable
private fun CommentAvatar(
    pubkey: String,
    author: ProfileRecord?,
    name: String,
    size: Dp,
    onClick: (String) -> Unit,
    tag: String = "profile:$pubkey",
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(ZapSurface3)
            .clickable(role = Role.Button) { onClick(pubkey) }
            .testTag(tag),
    ) {
        ProfileImage(
            pubkey = pubkey,
            pictureUrl = author?.picture,
            profileVersion = author?.eventId,
            contentDescription = stringResource(R.string.profile_avatar_description, name),
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun ReplyLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(ZapRadius.xs))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(end = ZapSpacing.space2),
    ) {
        Icon(painterResource(R.drawable.ic_reply), contentDescription = null, tint = ZapActionText, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.reply), color = ZapActionText, style = MaterialTheme.typography.labelMedium)
    }
}
