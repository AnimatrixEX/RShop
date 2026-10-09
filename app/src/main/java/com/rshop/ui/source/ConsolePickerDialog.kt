package com.rshop.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rshop.R
import com.rshop.scraper.model.CatalogSection
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.FocusableSurface
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.sourceErrorText

/** Which source the open console choice is for. */
sealed interface ConsolePickerTarget {
    /** A source being added: the choice goes with the analysis result. */
    data object Analysis : ConsolePickerTarget

    /** A configured source: the choice is applied at once. */
    data class Source(val id: String, val name: String) : ConsolePickerTarget
}

data class ConsolePickerState(
    val target: ConsolePickerTarget,
    val loading: Boolean = false,
    val error: com.rshop.data.sync.SourceError? = null,
    val sections: List<CatalogSection> = emptyList(),
    val selected: Set<String> = emptySet(),
)

/**
 * Checklist of the consoles a site offers. Every row is one focus stop (A toggles it), so the
 * whole choice can be made with a controller.
 */
@Composable
fun ConsolePickerDialog(
    state: ConsolePickerState,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onRetry: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val ready = !state.loading && state.error == null && state.sections.isNotEmpty()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(min = 360.dp, max = 640.dp)
                .heightIn(max = 560.dp)
                .background(RShopColors.SurfaceHigh, RoundedCornerShape(24.dp))
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.console_picker_title), style = MaterialTheme.typography.titleLarge)
            (state.target as? ConsolePickerTarget.Source)?.let {
                Text(it.name, style = MaterialTheme.typography.bodyMedium, color = RShopColors.AccentBright)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.console_picker_body),
                style = MaterialTheme.typography.bodySmall,
                color = RShopColors.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            when {
                state.loading -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.console_picker_loading), color = RShopColors.TextSecondary)
                }
                state.error != null -> Column(Modifier.padding(vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.console_picker_error, sourceErrorText(state.error)),
                        color = RShopColors.Warning,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    ConsoleButton(stringResource(R.string.action_retry), onRetry, Modifier.focusRequester(firstFocus))
                }
                state.sections.isEmpty() -> Text(
                    stringResource(R.string.console_picker_none_found),
                    color = RShopColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                else -> {
                    LazyColumn(
                        Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.sections, key = { it.url }) { section ->
                            ConsoleRow(
                                name = section.name,
                                checked = section.url in state.selected,
                                onClick = { onToggle(section.url) },
                                modifier = if (section == state.sections.first()) Modifier.focusRequester(firstFocus) else Modifier,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (ready) {
                Text(
                    stringResource(R.string.console_picker_count, state.selected.size, state.sections.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.selected.isEmpty()) RShopColors.Warning else RShopColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ready) {
                    ConsoleButton(stringResource(R.string.console_picker_all), onSelectAll, style = ConsoleButtonStyle.Secondary)
                    ConsoleButton(stringResource(R.string.console_picker_none), onSelectNone, style = ConsoleButtonStyle.Secondary)
                    Spacer(Modifier.weight(1f))
                    if (state.selected.isNotEmpty()) ConsoleButton(stringResource(R.string.console_picker_apply), onApply)
                } else {
                    Spacer(Modifier.weight(1f))
                }
                ConsoleButton(stringResource(R.string.action_cancel), onDismiss, style = ConsoleButtonStyle.Secondary)
            }
        }
    }
    LaunchedEffect(ready, state.error) {
        if (ready || state.error != null) runCatching { firstFocus.requestFocus() }
    }
}

@Composable
private fun ConsoleRow(name: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.01f,
        containerColor = if (checked) RShopColors.Accent.copy(alpha = 0.18f) else RShopColors.Surface,
        focusedContainerColor = if (checked) RShopColors.Accent.copy(alpha = 0.32f) else RShopColors.SurfaceHighest,
        glow = false,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(if (checked) RShopColors.Accent else RShopColors.SurfaceHighest, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = RShopColors.TextPrimary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = if (checked) RShopColors.TextPrimary else RShopColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
