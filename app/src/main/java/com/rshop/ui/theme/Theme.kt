package com.rshop.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private fun colorScheme(palette: RShopPalette) = (if (palette.isLight) lightColorScheme() else darkColorScheme()).copy(
    primary = palette.accent,
    onPrimary = RShopColors.OnAccent,
    primaryContainer = palette.accent.copy(alpha = 0.25f),
    onPrimaryContainer = palette.textPrimary,
    secondary = palette.accentSecondary,
    onSecondary = palette.textPrimary,
    background = palette.background,
    onBackground = palette.textPrimary,
    surface = palette.surface,
    onSurface = palette.textPrimary,
    surfaceVariant = palette.surfaceHigh,
    onSurfaceVariant = palette.textSecondary,
    surfaceContainer = palette.surfaceHigh,
    surfaceContainerHigh = palette.surfaceHighest,
    outline = palette.outline,
    error = RShopColors.Error,
)

/** Applies [ActiveTheme]: colors, plus the chosen text size on top of the system font scale. */
@Composable
fun RShopTheme(content: @Composable () -> Unit) {
    val palette = ActiveTheme.palette
    val scheme = remember(palette) { colorScheme(palette) }
    val density = LocalDensity.current
    val textScale = ActiveTheme.settings.textSize.scale
    val scaled = remember(density, textScale) { Density(density.density, density.fontScale * textScale) }
    MaterialTheme(colorScheme = scheme, typography = RShopTypography) {
        // Screens draw on plain backgrounds rather than Surfaces: text must default to light.
        CompositionLocalProvider(
            LocalContentColor provides palette.textPrimary,
            LocalDensity provides scaled,
            content = content,
        )
    }
}
