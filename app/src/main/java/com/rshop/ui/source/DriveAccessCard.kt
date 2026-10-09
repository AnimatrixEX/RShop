package com.rshop.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rshop.R
import com.rshop.data.source.DriveKeyStatus
import com.rshop.data.source.SignInState
import com.rshop.scraper.drive.auth.OAuthException
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.ControllerTextField
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors

/**
 * How RShop reaches Google Drive. The simple way is the player's own Google account (private and
 * shared-with-me folders too); a folder anyone with the link can read can also be read with an API key.
 * Secrets are stored encrypted and never shown back.
 */
@Composable
fun DriveAccessCard(
    status: DriveKeyStatus,
    signIn: SignInState,
    needed: Boolean,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSaveClient: (id: String, secret: String) -> Unit,
    onSaveKey: (String) -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = Dimens.ScreenPadding)
            .fillMaxWidth()
            .background(RShopColors.Surface, RoundedCornerShape(16.dp))
            .padding(18.dp),
    ) {
        Text(stringResource(R.string.drive_access_title), style = MaterialTheme.typography.titleLarge)
        if (needed) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.drive_key_needed), style = MaterialTheme.typography.bodyMedium, color = RShopColors.Warning)
        }
        Spacer(Modifier.height(14.dp))
        AccountSection(status, signIn, onSignIn, onCancelSignIn, onSignOut, onSaveClient)
        Spacer(Modifier.height(18.dp))
        ApiKeySection(status, onSaveKey)
    }
}

@Composable
private fun AccountSection(
    status: DriveKeyStatus,
    signIn: SignInState,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSaveClient: (String, String) -> Unit,
) {
    Text(stringResource(R.string.drive_account_title), style = MaterialTheme.typography.titleMedium)
    when {
        status.signedIn -> {
            Text(stringResource(R.string.drive_account_signed_in, status.email.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = RShopColors.Success)
            Spacer(Modifier.height(10.dp))
            ConsoleButton(stringResource(R.string.drive_sign_out), onClick = onSignOut, style = ConsoleButtonStyle.Secondary)
        }
        signIn is SignInState.Waiting -> {
            Text(stringResource(R.string.drive_sign_in_waiting), style = MaterialTheme.typography.bodyMedium, color = RShopColors.AccentBright)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(14.dp))
                ConsoleButton(stringResource(R.string.drive_sign_in_cancel), onClick = onCancelSignIn, style = ConsoleButtonStyle.Secondary)
            }
        }
        signIn is SignInState.Finishing -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.drive_sign_in_finishing), style = MaterialTheme.typography.bodyMedium, color = RShopColors.AccentBright)
        }
        status.clientConfigured -> {
            val message = when {
                signIn is SignInState.Failed && signIn.kind == OAuthException.Kind.Denied -> stringResource(R.string.drive_sign_in_denied) to true
                signIn is SignInState.Failed && signIn.kind == OAuthException.Kind.InvalidClient -> stringResource(R.string.drive_sign_in_bad_client) to true
                signIn is SignInState.Failed -> stringResource(R.string.drive_sign_in_failed, signIn.detail.orEmpty()) to true
                status.signInExpired -> stringResource(R.string.drive_account_expired) to true
                else -> stringResource(R.string.drive_account_signed_out) to false
            }
            Text(message.first, style = MaterialTheme.typography.bodyMedium, color = if (message.second) RShopColors.Warning else RShopColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConsoleButton(stringResource(R.string.drive_sign_in), onClick = onSignIn)
                Spacer(Modifier.width(12.dp))
                ConsoleButton(stringResource(R.string.drive_client_remove), onClick = { onSaveClient("", "") }, style = ConsoleButtonStyle.Secondary)
            }
        }
        else -> ClientForm(onSaveClient)
    }
}

/** The OAuth client of the player's Google Cloud project: needed once, before the first sign-in. */
@Composable
private fun ClientForm(onSave: (String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    Text(stringResource(R.string.drive_account_signed_out), style = MaterialTheme.typography.bodyMedium, color = RShopColors.TextSecondary)
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.drive_client_help), style = MaterialTheme.typography.bodySmall, color = RShopColors.TextTertiary)
    Spacer(Modifier.height(10.dp))
    TextField(id, { id = it }, stringResource(R.string.drive_client_id), secret = false, ImeAction.Next)
    Spacer(Modifier.height(10.dp))
    TextField(secret, { secret = it }, stringResource(R.string.drive_client_secret), secret = true, ImeAction.Done) {
        if (id.isNotBlank() && secret.isNotBlank()) onSave(id, secret)
    }
    Spacer(Modifier.height(10.dp))
    ConsoleButton(stringResource(R.string.drive_client_save), onClick = { if (id.isNotBlank() && secret.isNotBlank()) onSave(id, secret) })
}

@Composable
private fun ApiKeySection(status: DriveKeyStatus, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    Text(stringResource(R.string.drive_key_title), style = MaterialTheme.typography.titleMedium)
    Text(
        if (status.configured) stringResource(R.string.drive_key_configured, status.hint.orEmpty()) else stringResource(R.string.drive_key_missing),
        style = MaterialTheme.typography.bodyMedium,
        color = RShopColors.TextSecondary,
    )
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.drive_key_help), style = MaterialTheme.typography.bodySmall, color = RShopColors.TextTertiary)
    Spacer(Modifier.height(10.dp))
    val save = {
        if (key.isNotBlank()) onSave(key)
        key = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextField(key, { key = it }, stringResource(R.string.drive_key_label), secret = true, ImeAction.Done, Modifier.weight(1f)) { save() }
        Spacer(Modifier.width(12.dp))
        ConsoleButton(stringResource(R.string.drive_key_save), onClick = { save() })
        if (status.configured) {
            Spacer(Modifier.width(12.dp))
            ConsoleButton(stringResource(R.string.drive_key_remove), onClick = { onSave("") }, style = ConsoleButtonStyle.Secondary)
        }
    }
}

/** A text box a controller opens with A (see [ControllerTextField]). */
@Composable
private fun TextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    secret: Boolean,
    ime: ImeAction,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    ControllerTextField(shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) { fieldModifier ->
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = fieldModifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii, imeAction = ime),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RShopColors.Focus,
                unfocusedBorderColor = RShopColors.Outline,
                focusedContainerColor = RShopColors.SurfaceHigh,
                unfocusedContainerColor = RShopColors.Surface,
            ),
        )
    }
}
