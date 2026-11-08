package dev.zapstore.app.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.isSpecified
import dev.zapstore.app.R
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3
import dev.zapstore.app.screens.downloadLabel
import dev.zapstore.iolite.AppRecord
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp

/** A known host that reads short, with a small logo: forges as `owner/repo`, F-Droid as the app id. */
internal enum class Forge(val title: String, val icon: Int, val hosts: Set<String>) {
    GitHub("GitHub", R.drawable.ic_forge_github, setOf("github.com")),
    GitLab("GitLab", R.drawable.ic_forge_gitlab, setOf("gitlab.com")),
    Codeberg("Codeberg", R.drawable.ic_forge_codeberg, setOf("codeberg.org")),
    FDroid("F-Droid", R.drawable.ic_forge_fdroid, setOf("fdroid.org", "f-droid.org")),
}

/** What a line says about an address: the [forge] when it is on a known one, and the text to show. */
internal data class InstallSource(val forge: Forge?, val label: String)

/**
 * Short names, as the Flutter app gave them. GitHub, GitLab and Codeberg: `owner/repo`. F-Droid: the app id from the file name (`app.comaps.fdroid_25080802.apk` is `app.comaps.fdroid`) or its
 * `packages` page. Any other address is [whole], without its scheme, or just its host when that is false. An
 * address on a known host that has no repository or app in it is treated as any other.
 */
internal fun installSource(url: String, whole: Boolean = true): InstallSource {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull()
    val host = uri?.host?.lowercase()?.removePrefix("www.")
    val forge = Forge.entries.firstOrNull { host in it.hosts }
    val segments = uri?.path.orEmpty().split('/').filter { it.isNotEmpty() }
    val short = when (forge) {
        null -> null
        Forge.FDroid -> {
            val repo = segments.indexOf("repo")
            val packages = segments.indexOf("packages")
            when {
                repo != -1 && repo + 1 < segments.size -> segments[repo + 1].substringBefore('_').removeSuffix(".apk")
                packages != -1 && packages + 1 < segments.size -> segments[packages + 1]
                else -> null
            }
        }
        else -> segments.takeIf { it.size >= 2 }?.let { "${it[0]}/${it[1]}" }
    }
    return when {
        forge != null && !short.isNullOrEmpty() -> InstallSource(forge, short)
        whole -> InstallSource(null, downloadLabel(url))
        else -> InstallSource(null, host ?: downloadLabel(url))
    }
}

/**
 * Where something comes from, as [lead] (when given) then the source: a known host's logo and short name, or the
 * address, cut in the middle when it is long. With [onClick] the whole line opens something.
 */
@Composable
internal fun SourceLine(
    url: String,
    lead: String?,
    modifier: Modifier = Modifier,
    /** False cuts an address on an unknown host down to the host. */
    whole: Boolean = true,
    /** False keeps the line as wide as its text, so a mark can follow it. */
    fill: Boolean = true,
    onClick: (() -> Unit)? = null,
    tag: String? = null,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    leadColor: androidx.compose.ui.graphics.Color = ZapText3,
    color: androidx.compose.ui.graphics.Color = ZapText,
) {
    val source = installSource(url, whole)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
        modifier = modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        lead?.let { Text(it, color = leadColor, style = style, maxLines = 1) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f, fill = fill),
        ) {
            source.forge?.let { forge ->
                Image(
                    painterResource(forge.icon),
                    contentDescription = forge.title,
                    modifier = Modifier.size(16.dp).clip(CircleShape).testTag("forge:${forge.name}"),
                )
            }
            // The name is one string to a reader, but drawn in two so a long repository gives way first: the owner
            // before the slash keeps its room.
            val slash = source.label.indexOf('/').takeIf { source.forge != null && it > 0 }
            val textStyle = style.copy(fontWeight = if (source.forge != null) FontWeight.SemiBold else FontWeight.Normal)
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clearAndSetSemantics {
                        text = AnnotatedString(source.label)
                        if (tag != null) this.testTag = tag
                    },
            ) {
                if (slash != null) {
                    // The owner is who it comes from, so it keeps its room and the repository gives way first.
                    Text(
                        source.label.substring(0, slash),
                        color = color,
                        style = textStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    Text(
                        source.label.substring(slash),
                        color = color,
                        style = textStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Text(source.label, color = color, style = textStyle, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
        }
    }
}

/** Where the catalog got an unclaimed app: its file's address, else its repository. Null when it has neither. */
internal fun AppRecord.catalogSource(): String? = (downloadUrl ?: repository)?.takeIf { it.isNotBlank() }

/**
 * `from [logo] owner/repo`: the one way every mention of an unclaimed app or a catalog-signed release says where
 * it comes from. Short, one line, not a link. Draws nothing without a [source].
 */
@Composable
internal fun CatalogFrom(
    source: String?,
    modifier: Modifier = Modifier,
    tag: String? = null,
    /** False drops the word, where the line already sits under something that says what it is, such as the app head. */
    lead: Boolean = true,
) {
    if (source.isNullOrBlank()) return
    SourceLine(
        source,
        lead = if (lead) stringResource(R.string.source_from) else null,
        modifier = modifier,
        whole = false,
        fill = false,
        tag = tag,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
        leadColor = ZapText3,
        color = ZapText2,
    )
}

/** Where [SentenceWithSource] puts the source in a string. */
internal const val SOURCE_SLOT = "{source}"

/**
 * A sentence with where the app comes from inside it, as a logo and `owner/repo` that sit in the line of text and
 * wrap with it: [sentence] holds [SOURCE_SLOT] where the source goes. Without the slot it is plain text.
 */
@Composable
internal fun SentenceWithSource(
    sentence: String,
    source: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: androidx.compose.ui.graphics.Color = ZapText2,
    sourceColor: androidx.compose.ui.graphics.Color = ZapText,
) {
    val parts = sentence.split(SOURCE_SLOT, limit = 2)
    if (parts.size < 2) {
        Text(sentence, style = style, color = color, modifier = modifier)
        return
    }
    val info = installSource(source, whole = false)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = style.copy(color = sourceColor, fontWeight = if (info.forge != null) FontWeight.SemiBold else FontWeight.Normal)
    val markWidth = if (info.forge != null) 14.dp + 4.dp else 0.dp
    val width = with(density) {
        (measurer.measure(AnnotatedString(info.label), labelStyle, maxLines = 1).size.width.toDp() + markWidth + 2.dp).toSp()
    }
    val height = if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.4f
    val text = buildAnnotatedString {
        append(parts[0])
        appendInlineContent("source", info.label)
        append(parts[1])
    }
    Text(
        text,
        style = style,
        color = color,
        modifier = modifier,
        inlineContent = mapOf(
            "source" to InlineTextContent(Placeholder(width, height, PlaceholderVerticalAlign.TextCenter)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    info.forge?.let { forge ->
                        Image(
                            painterResource(forge.icon),
                            contentDescription = forge.title,
                            modifier = Modifier.size(14.dp).clip(CircleShape),
                        )
                    }
                    Text(info.label, style = labelStyle, maxLines = 1, softWrap = false)
                }
            },
        ),
    )
}
