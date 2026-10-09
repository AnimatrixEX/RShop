package com.rshop.ui.details

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rshop.R
import com.rshop.domain.model.DownloadOption
import com.rshop.domain.model.GameParts
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.FocusableSurface
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.formatSize

/**
 * Lets the player pick the file a game page offers (formats, discs, regions), or several at once
 * for a game made of more than one file. When the files look like parts of one game (disc 1,
 * disc 2; bin + cue) "download all" comes first.
 */
@Composable
fun FormatChooserDialog(
    title: String,
    options: List<DownloadOption>,
    onPick: (DownloadOption) -> Unit,
    onPickMany: (List<DownloadOption>) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val allAreParts = remember(options) { GameParts.looksLikeParts(options) }
    var several by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .heightIn(max = 560.dp)
                .background(RShopColors.SurfaceHigh, RoundedCornerShape(24.dp))
                .padding(24.dp),
        ) {
            Text(
                stringResource(if (several) R.string.details_choose_files else R.string.details_choose_file),
                style = MaterialTheme.typography.labelMedium,
                color = RShopColors.AccentBright,
            )
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (allAreParts && !several) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.details_parts_hint), style = MaterialTheme.typography.bodySmall, color = RShopColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                ConsoleButton(
                    pluralStringResource(R.plurals.details_download_all, options.size, options.size),
                    onClick = { onPickMany(options) },
                    modifier = Modifier.focusRequester(first),
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                options.forEachIndexed { index, option ->
                    OptionRow(
                        display = OptionDisplayFactory.of(option),
                        fallbackName = stringResource(R.string.details_file_number, index + 1),
                        selected = if (several) option.url in chosen else null,
                        onClick = {
                            if (several) {
                                chosen = if (option.url in chosen) chosen - option.url else chosen + option.url
                            } else {
                                onPick(option)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().let { if (index == 0 && !(allAreParts && !several)) it.focusRequester(first) else it },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ConsoleButton(
                    stringResource(if (several) R.string.details_one_file else R.string.details_several_files),
                    onClick = {
                        several = !several
                        chosen = emptySet()
                    },
                    style = ConsoleButtonStyle.Secondary,
                )
                if (several && chosen.isNotEmpty()) {
                    ConsoleButton(
                        pluralStringResource(R.plurals.details_download_selected, chosen.size, chosen.size),
                        onClick = {
                            // Installed in the order the page lists them.
                            onPickMany(options.filter { it.url in chosen })
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                ConsoleButton(stringResource(R.string.action_cancel), onDismiss, style = ConsoleButtonStyle.Secondary)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** One file: its readable name over as many lines as it needs, then format and size as badges. */
@Composable
private fun OptionRow(
    display: OptionDisplay,
    fallbackName: String,
    selected: Boolean?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.03f,
        containerColor = if (selected == true) RShopColors.Accent.copy(alpha = 0.22f) else RShopColors.SurfaceHighest,
        focusedContainerColor = if (selected == true) RShopColors.Accent.copy(alpha = 0.36f) else RShopColors.Outline,
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) {
                Box(
                    Modifier
                        .size(24.dp)
                        .background(if (selected) RShopColors.Accent else RShopColors.SurfaceHigh, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    display.name ?: display.format ?: fallbackName,
                    style = MaterialTheme.typography.titleSmall,
                    color = RShopColors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (display.format != null || display.sizeBytes != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        display.format?.let { format ->
                            Text(
                                format,
                                modifier = Modifier.background(RShopColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                            )
                        }
                        display.sizeBytes?.let { Text(formatSize(it), style = MaterialTheme.typography.bodyMedium, color = RShopColors.TextSecondary) }
                    }
                }
            }
        }
    }
}
