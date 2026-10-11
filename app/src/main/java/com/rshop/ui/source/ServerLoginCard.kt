package com.rshop.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rshop.R
import com.rshop.scraper.remote.RemoteProtocol
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.ControllerTextField
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors

/**
 * Login of the FTP, FTPS or SFTP server whose address was typed. Leaving the user name empty means
 * "anonymous"; the password is kept encrypted and never leaves the device.
 */
@Composable
fun ServerLoginCard(
    protocol: RemoteProtocol,
    user: String,
    password: String,
    onUserChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = Dimens.ScreenPadding)
            .fillMaxWidth()
            .background(RShopColors.Surface, RoundedCornerShape(16.dp))
            .padding(18.dp),
    ) {
        Text(stringResource(R.string.server_login_title), style = MaterialTheme.typography.titleMedium)
        if (!protocol.encrypted) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.server_plain_warning), style = MaterialTheme.typography.bodySmall, color = RShopColors.Warning)
        }
        Spacer(Modifier.height(10.dp))
        LoginField(user, onUserChange, stringResource(R.string.server_user_label), "anonymous", secret = false)
        Spacer(Modifier.height(10.dp))
        LoginField(password, onPasswordChange, stringResource(R.string.server_password_label), null, secret = true)
    }
}

@Composable
private fun LoginField(value: String, onChange: (String) -> Unit, label: String, placeholder: String?, secret: Boolean) {
    ControllerTextField(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { fieldModifier ->
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = fieldModifier.fillMaxWidth(),
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RShopColors.Focus,
                unfocusedBorderColor = RShopColors.Outline,
                focusedContainerColor = RShopColors.SurfaceHigh,
                unfocusedContainerColor = RShopColors.Surface,
            ),
        )
    }
}

/** An SFTP server met for the first time: the user checks its key fingerprint before trusting it. */
@Composable
fun ServerTrustCard(host: String, fingerprint: String, busy: Boolean, onTrust: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = Dimens.ScreenPadding)
            .fillMaxWidth()
            .background(RShopColors.Surface, RoundedCornerShape(16.dp))
            .padding(18.dp),
    ) {
        Text(stringResource(R.string.server_trust_title, host), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.server_trust_body), style = MaterialTheme.typography.bodyMedium, color = RShopColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        Text(fingerprint, style = MaterialTheme.typography.bodyMedium, color = RShopColors.AccentBright)
        Spacer(Modifier.height(12.dp))
        if (busy) CircularProgressIndicator() else ConsoleButton(stringResource(R.string.server_trust_button), onClick = onTrust, style = ConsoleButtonStyle.Primary)
    }
}
