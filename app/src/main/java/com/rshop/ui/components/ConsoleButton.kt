package com.rshop.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors

enum class ConsoleButtonStyle { Primary, Secondary }

@Composable
fun ConsoleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    style: ConsoleButtonStyle = ConsoleButtonStyle.Primary,
) {
    val (container, focusedContainer) = when (style) {
        // Only slightly lighter when focused: the bright accent would wash out the white label.
        ConsoleButtonStyle.Primary -> RShopColors.Accent to lerp(RShopColors.Accent, Color.White, 0.14f)
        ConsoleButtonStyle.Secondary -> RShopColors.SurfaceHighest to RShopColors.Outline
    }
    val labelColor = if (style == ConsoleButtonStyle.Primary) RShopColors.OnAccent else RShopColors.TextPrimary
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = Dimens.PillShape,
        focusedScale = 1.05f,
        containerColor = container,
        focusedContainerColor = focusedContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = labelColor)
                Spacer(Modifier.width(8.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge, color = labelColor)
        }
    }
}

/** Round icon-only action, e.g. the favorite toggle next to a primary button. */
@Composable
fun ConsoleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.size(46.dp),
        shape = CircleShape,
        focusedScale = 1.08f,
        containerColor = if (active) RShopColors.Accent else RShopColors.SurfaceHighest,
        focusedContainerColor = if (active) RShopColors.AccentBright else RShopColors.Outline,
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (active) RShopColors.OnAccent else RShopColors.TextPrimary)
    }
}
