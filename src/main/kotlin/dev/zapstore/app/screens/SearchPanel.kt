package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.zapstore.app.R
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3
import dev.zapstore.app.ZapTitle3Style
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.SettingsGroup
import dev.zapstore.app.components.SwitchRow
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapChip
import dev.zapstore.app.components.ZapSearchField
import dev.zapstore.app.facts.label
import dev.zapstore.iolite.SearchFact

/**
 * The search field of Home and of My apps: a magnifier, the text, and an X once there is text or a result.
 */
@Composable
internal fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    placeholder: String,
    hasResults: Boolean,
    modifier: Modifier = Modifier,
    clearTag: String = "clearSearch",
) {
    ZapSearchField(
        value = value,
        onValueChange = onValueChange,
        onSearch = onSearch,
        placeholder = placeholder,
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = ZapText3,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = {
            if (value.isNotEmpty() || hasResults) {
                val clearDescription = stringResource(R.string.clear_search)
                IconButton(
                    onClick = onClear,
                    modifier = Modifier
                        .size(ZapSize.control)
                        .semantics { contentDescription = clearDescription }
                        .testTag(clearTag),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                        tint = ZapText3,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * Submitted results as one gray panel, as on the web: the filter chips, then the rows [content] draws, or
 * what to relax when [shown] is empty and nothing else matched ([hasOthers]). Clearing is the field's X, so
 * the panel has no link for it. [hideInstalled] is null where that filter does not exist.
 */
@Composable
internal fun SearchPanel(
    facts: List<SearchFact>,
    shown: List<*>,
    hasOthers: Boolean,
    onRemoveFilter: (SearchFact) -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
    hideInstalled: Boolean? = null,
    onShowInstalled: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = ZapSpacing.space4)
            .clip(RoundedCornerShape(ZapRadius.lg))
            .background(ZapSurface1)
            .padding(horizontal = ZapSpacing.space4, vertical = ZapSpacing.space3)
            .testTag("searchPanel"),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
            modifier = Modifier.padding(bottom = ZapSpacing.space1).testTag("searchFacts"),
        ) {
            facts.forEach { fact ->
                val label = fact.label()
                val remove = stringResource(R.string.remove_filter, label)
                ZapChip(
                    text = label,
                    onClick = { onRemoveFilter(fact) },
                    selected = true,
                    trailingIcon = R.drawable.ic_close,
                    modifier = Modifier
                        .semantics { contentDescription = remove }
                        .testTag("filter:${fact.name}"),
                )
            }
            if (hideInstalled == true) {
                val label = stringResource(R.string.hide_installed)
                val removeInstalled = stringResource(R.string.remove_filter, label)
                ZapChip(
                    text = label,
                    onClick = onShowInstalled,
                    selected = true,
                    trailingIcon = R.drawable.ic_close,
                    modifier = Modifier
                        .semantics { contentDescription = removeInstalled }
                        .testTag("filter:installed"),
                )
            }
            ZapChip(
                text = stringResource(R.string.filters),
                onClick = onOpenFilters,
                trailingIcon = R.drawable.ic_chevron_down,
                modifier = Modifier.testTag("openFilters"),
            )
        }
        val narrowed = facts.isNotEmpty() || hideInstalled == true
        if (shown.isEmpty() && (narrowed || !hasOthers)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
                modifier = Modifier.padding(top = ZapSpacing.space2).testTag("noResults"),
            ) {
                val message = if (narrowed) R.string.no_filtered_results else R.string.no_search_results
                EmptyRow(stringResource(message), icon = R.drawable.ic_search)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                    verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                ) {
                    facts.forEach { fact ->
                        ZapButton(
                            text = stringResource(R.string.remove_filter, fact.label()),
                            onClick = { onRemoveFilter(fact) },
                            style = ZapButtonStyle.Tonal,
                            modifier = Modifier.testTag("relax:${fact.name}"),
                        )
                    }
                    if (hideInstalled == true) {
                        ZapButton(
                            text = stringResource(R.string.remove_filter, stringResource(R.string.hide_installed)),
                            onClick = onShowInstalled,
                            style = ZapButtonStyle.Tonal,
                            modifier = Modifier.testTag("relax:installed"),
                        )
                    }
                }
            }
        }
        content()
    }
}

/** The facts to filter by, one switch each; [hideInstalled] adds the switch for Home's own filter, and is null elsewhere. */
@Composable
internal fun FilterSheet(
    active: List<SearchFact>,
    onToggle: (SearchFact) -> Unit,
    onDismiss: () -> Unit,
    hideInstalled: Boolean? = null,
    onToggleHideInstalled: () -> Unit = {},
) {
    ZapSheet(onDismiss = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
            modifier = Modifier.padding(start = ZapSpacing.space4, end = ZapSpacing.space4, bottom = ZapSpacing.space6),
        ) {
            Text(stringResource(R.string.filters), style = ZapTitle3Style)
            Text(stringResource(R.string.filters_hint), color = ZapText2, style = MaterialTheme.typography.bodyMedium)
            SettingsGroup(Modifier.padding(top = ZapSpacing.space2)) {
                SearchFact.entries.forEachIndexed { index, fact ->
                    if (index > 0) HorizontalDivider(color = ZapLine)
                    SwitchRow(
                        title = fact.label(),
                        checked = fact in active,
                        onCheckedChange = { onToggle(fact) },
                        modifier = Modifier.testTag("sheetFilter:${fact.name}"),
                    )
                }
                if (hideInstalled != null) {
                    HorizontalDivider(color = ZapLine)
                    SwitchRow(
                        title = stringResource(R.string.hide_installed),
                        checked = hideInstalled,
                        onCheckedChange = { onToggleHideInstalled() },
                        modifier = Modifier.testTag("sheetFilter:installed"),
                    )
                }
            }
        }
    }
}
