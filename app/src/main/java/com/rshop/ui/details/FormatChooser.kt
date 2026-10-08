package com.rshop.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rshop.R
import com.rshop.domain.model.DownloadOption
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.FocusableSurface
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.formatSize

/** Lets the player pick one of the files a game page offers (formats, discs, regions). */
@Composable
fun FormatChooserDialog(
    title: String,
    options: List<DownloadOption>,
    onPick: (DownloadOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .heightIn(max = 520.dp)
                .background(RShopColors.SurfaceHigh, RoundedCornerShape(24.dp))
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.details_choose_file), style = MaterialTheme.typography.labelMedium, color = RShopColors.AccentBright)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                options.forEachIndexed { index, option ->
                    OptionRow(
                        display = OptionDisplayFactory.of(option),
                        fallbackName = stringResource(R.string.details_file_number, index + 1),
                        onClick = { onPick(option) },
                        modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(first) else it },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            ConsoleButton(stringResource(R.string.action_cancel), onDismiss, style = ConsoleButtonStyle.Secondary)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** One file: its readable name over as many lines as it needs, then format and size as badges. */
@Composable
private fun OptionRow(display: OptionDisplay, fallbackName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.03f,
        containerColor = RShopColors.SurfaceHighest,
        focusedContainerColor = RShopColors.Outline,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
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
