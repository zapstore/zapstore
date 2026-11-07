package dev.zapstore.app.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import dev.zapstore.app.R
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText3
import dev.zapstore.app.screens.downloadLabel

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
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        lead?.let { Text(it, color = leadColor, style = style, maxLines = 1) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            source.forge?.let { forge ->
                Image(
                    painterResource(forge.icon),
                    contentDescription = forge.title,
                    modifier = Modifier.size(16.dp).clip(CircleShape).testTag("forge:${forge.name}"),
                )
            }
            // The name is one string to a reader, but drawn in two so a long owner gives way first: the repository
            // after the slash keeps its room.
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
                    Text(
                        source.label.substring(0, slash),
                        color = color,
                        style = textStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(source.label.substring(slash), color = color, style = textStyle, maxLines = 1, softWrap = false)
                } else {
                    Text(source.label, color = color, style = textStyle, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
        }
    }
}
