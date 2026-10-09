package com.rshop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rshop.R
import com.rshop.data.storage.GamesFolder
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.FocusableSurface
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.folderLabel

/** The categories of the settings, one tab each. */
enum class SettingsTab(val labelRes: Int) {
    Catalog(R.string.settings_tab_catalog),
    Storage(R.string.settings_storage),
    Downloads(R.string.settings_downloads),
    Images(R.string.settings_artwork),
    Appearance(R.string.settings_appearance),
    App(R.string.settings_tab_app),
}

/**
 * The tab strip. Every tab is one focus stop; moving onto a tab shows its settings at once, so a
 * controller browses the categories with left/right and goes down into the list when it likes one.
 */
@Composable
fun SettingsTabs(selected: SettingsTab, onSelect: (SettingsTab) -> Unit, firstFocus: FocusRequester) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(SettingsTab.entries) { tab ->
            val isSelected = tab == selected
            FocusableSurface(
                onClick = { onSelect(tab) },
                modifier = Modifier
                    .let { if (isSelected) it.focusRequester(firstFocus) else it }
                    .onFocusChanged { if (it.hasFocus) onSelect(tab) },
                shape = CircleShape,
                focusedScale = 1.06f,
                containerColor = if (isSelected) RShopColors.Accent.copy(alpha = 0.32f) else RShopColors.SurfaceHigh,
                focusedContainerColor = if (isSelected) RShopColors.Accent.copy(alpha = 0.45f) else RShopColors.SurfaceHighest,
                glow = false,
            ) {
                Text(
                    stringResource(tab.labelRes),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) RShopColors.TextPrimary else RShopColors.TextSecondary,
                )
            }
        }
    }
}

/** What can be done with one games folder: make it the default, or forget it. */
@Composable
fun FolderActionsDialog(
    folder: GamesFolder,
    onDefault: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .background(RShopColors.SurfaceHigh, RoundedCornerShape(24.dp))
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.settings_games_dir), style = MaterialTheme.typography.labelMedium, color = RShopColors.AccentBright)
            Text(folderLabel(folder), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.folder_remove_note),
                style = MaterialTheme.typography.bodySmall,
                color = RShopColors.TextSecondary,
            )
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!folder.isDefault && folder.available) {
                    ConsoleButton(stringResource(R.string.folder_make_default), onClick = onDefault)
                }
                ConsoleButton(stringResource(R.string.folder_remove), onClick = onRemove, style = ConsoleButtonStyle.Secondary)
                ConsoleButton(stringResource(R.string.action_close), onClick = onDismiss, style = ConsoleButtonStyle.Secondary)
            }
        }
    }
}
