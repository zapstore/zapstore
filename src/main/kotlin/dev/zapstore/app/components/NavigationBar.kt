package dev.zapstore.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zapstore.app.R
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapActionTint
import dev.zapstore.app.ZapBadge
import dev.zapstore.app.ZapLineStrong
import dev.zapstore.app.ZapSurface2
import dev.zapstore.app.ZapSurface4
import dev.zapstore.app.ZapText2
import dev.zapstore.app.screens.Routes

private data class Destination(val route: String, val label: Int, val icon: Int, val tag: String)

private val destinations = listOf(
    Destination(Routes.HOME, R.string.home, R.drawable.ic_nav_search, "tabHome"),
    Destination(Routes.UPDATES, R.string.my_apps, R.drawable.ic_apps, "tabMyApps"),
    Destination(Routes.ME, R.string.profile, R.drawable.ic_nav_person, "tabProfile"),
)

/** The signed-in user's picture for the Profile tab. */
data class NavAvatar(val pubkey: String, val pictureUrl: String?, val version: String?)

/**
 * Icon-only bar: Home (with search), My apps, Profile. Labels are
 * spoken, not shown. My apps carries a red update count; Profile shows the signed-in picture.
 * The selection pill follows the selected tab on a spring.
 */
@Composable
fun ZapNavigationBar(
    current: String,
    updateCount: Int,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    avatar: NavAvatar? = null,
) {
    val animate = animationsEnabled()
    val selectedIndex = destinations.indexOfFirst { it.route == current }.coerceAtLeast(0)
    val slide by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (animate) spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow) else snap(),
        label = "navPill",
    )
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(ZapSurface2)
            .drawBehind { drawLine(ZapLineStrong, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
    ) {
        val slot = maxWidth / destinations.size
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .fillMaxWidth()
                .height(BAR_HEIGHT),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = slot * slide + (slot - PILL_WIDTH) / 2)
                    .size(width = PILL_WIDTH, height = PILL_HEIGHT)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ZapActionTint)
                    .testTag("navIndicator"),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
                destinations.forEach { destination ->
                    val selected = destination.route == current
                    val label = stringResource(destination.label)
                    val tint by animateColorAsState(
                        if (selected) ZapActionText else ZapText2,
                        if (animate) ZapMotion.base() else snap(),
                        label = "navTint",
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination.route) })
                            .semantics { contentDescription = label }
                            .testTag(destination.tag),
                    ) {
                        Box {
                            if (destination.route == Routes.ME && avatar != null) {
                                NavProfileImage(avatar, selected, tint)
                            } else {
                                Icon(painterResource(destination.icon), contentDescription = null, tint = tint, modifier = Modifier.size(ICON_SIZE))
                            }
                            if (destination.route == Routes.UPDATES && updateCount > 0) {
                                NavCountBadge(updateCount, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).testTag("updatesBadge"))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavProfileImage(avatar: NavAvatar, selected: Boolean, tint: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ICON_SIZE)
            .clip(CircleShape)
            .background(ZapSurface4)
            .testTag("navAvatar"),
    ) {
        Icon(painterResource(R.drawable.ic_nav_person), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        ProfileImage(
            pubkey = avatar.pubkey,
            pictureUrl = avatar.pictureUrl,
            profileVersion = avatar.version,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
        )
        if (selected) Box(Modifier.matchParentSize().border(2.dp, ZapActionText, CircleShape))
    }
}

@Composable
private fun NavCountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(ZapBadge)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Color.White,
            textAlign = TextAlign.Center,
            style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold),
        )
    }
}

private val BAR_HEIGHT = 56.dp
private val ICON_SIZE = 24.dp
private val PILL_WIDTH = 60.dp
private val PILL_HEIGHT = 44.dp
