package com.rshop.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
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
                    val name = option.label ?: option.fileName ?: stringResource(R.string.details_file_number, index + 1)
                    val text = option.sizeBytes?.let { "$name  ·  ${formatSize(it)}" } ?: name
                    ConsoleButton(
                        text = text,
                        onClick = { onPick(option) },
                        modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(first) else it },
                        style = if (index == 0) ConsoleButtonStyle.Primary else ConsoleButtonStyle.Secondary,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            ConsoleButton(stringResource(R.string.action_cancel), onDismiss, style = ConsoleButtonStyle.Secondary)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
