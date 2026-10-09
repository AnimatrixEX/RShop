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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rshop.R
import com.rshop.data.storage.GamesFolder
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.FocusableSurface
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.folderLabel
import com.rshop.ui.util.formatSize

/**
 * Asks which games folder a game goes to, when more than one was added. Every folder is one focus
 * stop; the one already holding the game (an update) or the default one comes first.
 */
@Composable
fun FolderChooserDialog(
    title: String,
    choices: List<FolderChoice>,
    onPick: (GamesFolder) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .heightIn(max = 560.dp)
                .background(RShopColors.SurfaceHigh, RoundedCornerShape(24.dp))
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.folder_choose_title), style = MaterialTheme.typography.labelMedium, color = RShopColors.AccentBright)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                choices.forEachIndexed { index, choice ->
                    FocusableSurface(
                        onClick = { onPick(choice.folder) },
                        modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(first) else it },
                        shape = RoundedCornerShape(16.dp),
                        focusedScale = 1.03f,
                        containerColor = RShopColors.SurfaceHighest,
                        focusedContainerColor = RShopColors.Outline,
                    ) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = RShopColors.TextSecondary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(folderLabel(choice.folder), style = MaterialTheme.typography.titleSmall, color = RShopColors.TextPrimary)
                                val notes = listOfNotNull(
                                    stringResource(R.string.folder_holds_game).takeIf { choice.holdsGame },
                                    stringResource(R.string.folder_default).takeIf { choice.folder.isDefault },
                                    choice.freeBytes?.let { stringResource(R.string.folder_free, formatSize(it)) },
                                )
                                if (notes.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(notes.joinToString(" - "), style = MaterialTheme.typography.bodyMedium, color = RShopColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            ConsoleButton(stringResource(R.string.action_cancel), onDismiss, style = ConsoleButtonStyle.Secondary)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
