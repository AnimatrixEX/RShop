package com.rshop.ui.lists

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
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
import com.rshop.domain.model.GameList
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.ControllerTextField
import com.rshop.ui.components.FocusableSurface
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors

/** Asks for a list name (new list or rename). The keyboard only opens when the field is activated. */
@Composable
fun ListNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val fieldFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .background(RShopColors.SurfaceHigh, RoundedCornerShape(24.dp))
                .padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            ControllerTextField(shape = Dimens.PillShape, modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)) { fieldModifier ->
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    modifier = fieldModifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.list_name_hint)) },
                    singleLine = true,
                    shape = Dimens.PillShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RShopColors.Focus,
                        unfocusedBorderColor = RShopColors.Outline,
                        focusedContainerColor = RShopColors.SurfaceHighest,
                        unfocusedContainerColor = RShopColors.Surface,
                    ),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ConsoleButton(confirmLabel, onClick = { if (name.isNotBlank()) onConfirm(name) })
                ConsoleButton(stringResource(R.string.action_cancel), onDismiss, style = ConsoleButtonStyle.Secondary)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
}

/** Ticks the lists a game belongs to; each press adds or removes it at once. */
@Composable
fun AddToListDialog(
    gameTitle: String,
    lists: List<GameList>,
    memberOf: Set<Long>,
    onToggle: (GameList) -> Unit,
    onCreate: () -> Unit,
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
            Text(stringResource(R.string.list_picker_title), style = MaterialTheme.typography.labelMedium, color = RShopColors.AccentBright)
            Text(gameTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (lists.isEmpty()) {
                    Text(stringResource(R.string.list_picker_none), color = RShopColors.TextSecondary)
                }
                lists.forEachIndexed { index, list ->
                    val member = list.id in memberOf
                    FocusableSurface(
                        onClick = { onToggle(list) },
                        modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(first) else it },
                        shape = Dimens.PillShape,
                        focusedScale = 1.03f,
                        containerColor = RShopColors.SurfaceHighest,
                        focusedContainerColor = RShopColors.Outline,
                    ) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(list.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            if (member) Icon(Icons.Filled.Check, contentDescription = null, tint = RShopColors.AccentBright, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ConsoleButton(
                    stringResource(R.string.list_new),
                    onCreate,
                    modifier = if (lists.isEmpty()) Modifier.focusRequester(first) else Modifier,
                    style = ConsoleButtonStyle.Secondary,
                )
                ConsoleButton(stringResource(R.string.action_close), onDismiss, style = ConsoleButtonStyle.Secondary)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

private const val MAX_NAME = 40
