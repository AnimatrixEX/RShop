package com.rshop.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rshop.R
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.ControllerTextField
import com.rshop.ui.theme.RShopColors

/** Consoles offered first; any other name can be typed. Names the artwork lookups recognise. */
private val COMMON_PLATFORMS = listOf(
    "NES", "SNES", "Nintendo 64", "GameCube", "Wii", "Wii U", "Switch",
    "Game Boy", "Game Boy Color", "Game Boy Advance", "Nintendo DS", "Nintendo 3DS",
    "Master System", "Mega Drive", "Mega-CD", "Saturn", "Dreamcast", "Game Gear",
    "PlayStation", "PlayStation 2", "PlayStation 3", "PSP", "PS Vita",
    "PC Engine", "Neo Geo", "Neo Geo Pocket Color", "Arcade", "Atari 2600", "WonderSwan Color",
)

/**
 * Asks which console the games of a Drive belong to, for a Drive whose folders are game names only.
 * Every console is one focus stop; a typed name is accepted too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformPickerDialog(
    current: String?,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf(current.orEmpty()) }
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(min = 360.dp, max = 720.dp)
                .heightIn(max = 600.dp)
                .background(RShopColors.SurfaceHigh, RoundedCornerShape(24.dp))
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.drive_platform_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.drive_platform_body), style = MaterialTheme.typography.bodySmall, color = RShopColors.TextSecondary)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    COMMON_PLATFORMS.forEachIndexed { index, name ->
                        ConsoleButton(
                            name,
                            onClick = { onChoose(name) },
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            style = if (name.equals(current, ignoreCase = true)) ConsoleButtonStyle.Primary else ConsoleButtonStyle.Secondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControllerTextField(shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { fieldModifier ->
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        modifier = fieldModifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.drive_platform_other)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (custom.isNotBlank()) onChoose(custom) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RShopColors.Focus,
                            unfocusedBorderColor = RShopColors.Outline,
                        ),
                    )
                }
                Spacer(Modifier.width(12.dp))
                ConsoleButton(stringResource(R.string.drive_platform_use), onClick = { if (custom.isNotBlank()) onChoose(custom) })
            }
            Spacer(Modifier.height(12.dp))
            ConsoleButton(stringResource(R.string.action_cancel), onClick = onDismiss, style = ConsoleButtonStyle.Secondary)
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}
