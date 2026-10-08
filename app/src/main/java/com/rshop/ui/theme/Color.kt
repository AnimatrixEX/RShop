package com.rshop.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.rshop.domain.model.FocusStyle
import com.rshop.domain.model.ThemeAccent
import com.rshop.domain.model.ThemeBase
import com.rshop.domain.model.ThemeSettings

/** The colors of one theme: a dark base, an accent and the focus ring. */
@Immutable
data class RShopPalette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val outline: Color,
    val accent: Color,
    val accentBright: Color,
    val accentSecondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val focus: Color,
)

private data class Base(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
)

object ThemePalettes {

    private fun base(base: ThemeBase) = when (base) {
        ThemeBase.Night -> Base(
            Color(0xFF0A0D13), Color(0xFF121722), Color(0xFF1A2130), Color(0xFF242D40), Color(0xFF2E3850),
            Color(0xFFF2F4F8), Color(0xFF9AA4B6), Color(0xFF677187),
        )
        // Pure black: true off pixels on OLED handhelds.
        ThemeBase.Oled -> Base(
            Color(0xFF000000), Color(0xFF0B0B0E), Color(0xFF15161B), Color(0xFF1F2027), Color(0xFF2A2C35),
            Color(0xFFF2F4F8), Color(0xFF9EA3AE), Color(0xFF6A6F7A),
        )
        ThemeBase.Slate -> Base(
            Color(0xFF15171A), Color(0xFF1D2024), Color(0xFF262A30), Color(0xFF30353D), Color(0xFF3B414B),
            Color(0xFFF1F3F5), Color(0xFFA3A9B3), Color(0xFF6E7480),
        )
        ThemeBase.Twilight -> Base(
            Color(0xFF0E0A16), Color(0xFF171123), Color(0xFF211830), Color(0xFF2C2140), Color(0xFF3A2C52),
            Color(0xFFF4F0FA), Color(0xFFA99FBC), Color(0xFF72688A),
        )
    }

    /**
     * Main and brighter accent: the bright one is for text and small marks on dark surfaces. Main
     * accents stay dark enough for the white label of primary buttons.
     */
    fun accent(accent: ThemeAccent): Pair<Color, Color> = when (accent) {
        ThemeAccent.Blue -> Color(0xFF3D7BFF) to Color(0xFF6E9DFF)
        ThemeAccent.Violet -> Color(0xFF7C4DFF) to Color(0xFFA98BFF)
        ThemeAccent.Cyan -> Color(0xFF0B8796) to Color(0xFF4FD6E3)
        ThemeAccent.Green -> Color(0xFF16894F) to Color(0xFF5BDB9E)
        ThemeAccent.Orange -> Color(0xFFC75A1C) to Color(0xFFFFA66E)
        ThemeAccent.Pink -> Color(0xFFD03380) to Color(0xFFFF80B7)
        ThemeAccent.Red -> Color(0xFFE03A47) to Color(0xFFFF7A82)
    }

    /** Swatch shown for a base in the settings: its surface color. */
    fun swatch(base: ThemeBase): Color = base(base).surfaceHigh

    fun build(settings: ThemeSettings): RShopPalette {
        val base = base(settings.base)
        val (accent, bright) = accent(settings.accent)
        val secondary = if (settings.accent == ThemeAccent.Violet) accent(ThemeAccent.Blue).first else accent(ThemeAccent.Violet).first
        return RShopPalette(
            background = base.background,
            surface = base.surface,
            surfaceHigh = base.surfaceHigh,
            surfaceHighest = base.surfaceHighest,
            outline = base.outline,
            accent = accent,
            accentBright = bright,
            accentSecondary = secondary,
            textPrimary = base.textPrimary,
            textSecondary = base.textSecondary,
            textTertiary = base.textTertiary,
            // White reads best from a distance on any artwork; the accent is the user's choice.
            focus = if (settings.focus == FocusStyle.Accent) bright else Color(0xFFFFFFFF),
        )
    }
}

/**
 * The theme in use, app-wide. Snapshot state: changing it in the settings recolors every screen
 * at once (composition and drawing both observe it).
 */
object ActiveTheme {
    var settings by mutableStateOf(ThemeSettings())
    val palette: RShopPalette by derivedStateOf { ThemePalettes.build(settings) }
}

object RShopColors {
    private val palette get() = ActiveTheme.palette

    val Background get() = palette.background
    val Surface get() = palette.surface
    val SurfaceHigh get() = palette.surfaceHigh
    val SurfaceHighest get() = palette.surfaceHighest
    val Outline get() = palette.outline

    val Accent get() = palette.accent
    val AccentBright get() = palette.accentBright
    val AccentSecondary get() = palette.accentSecondary
    val Success = Color(0xFF2FD47F)
    val Warning = Color(0xFFFFB547)
    val Error = Color(0xFFFF5C6C)

    val TextPrimary get() = palette.textPrimary
    val TextSecondary get() = palette.textSecondary
    val TextTertiary get() = palette.textTertiary

    /** Focus ring: white by default, or the accent. */
    val Focus get() = palette.focus

    /**
     * Gradient pairs used as cover placeholders and backdrops when a game has no artwork.
     * Picked deterministically from the game id so a game always keeps the same colors.
     */
    val ArtworkGradients = listOf(
        Color(0xFF1E3C72) to Color(0xFF2A5298),
        Color(0xFF42275A) to Color(0xFF734B6D),
        Color(0xFF0F3443) to Color(0xFF34E89E),
        Color(0xFF3A1C71) to Color(0xFFD76D77),
        Color(0xFF16222A) to Color(0xFF3A6073),
        Color(0xFF4B134F) to Color(0xFFC94B4B),
        Color(0xFF134E5E) to Color(0xFF71B280),
        Color(0xFF232526) to Color(0xFF5B6574),
        Color(0xFF1F1C2C) to Color(0xFF928DAB),
        Color(0xFF7A2E0E) to Color(0xFFD9822B),
    )

    fun artworkGradient(key: String): Pair<Color, Color> =
        ArtworkGradients[Math.floorMod(key.hashCode(), ArtworkGradients.size)]
}
