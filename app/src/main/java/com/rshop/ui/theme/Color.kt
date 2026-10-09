package com.rshop.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rshop.domain.model.FocusStyle
import com.rshop.domain.model.ThemeAccent
import com.rshop.domain.model.ThemeBase
import com.rshop.domain.model.ThemeSettings

/** The colors of one theme: a base (dark, or light for the eShop style), an accent and the focus ring. */
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
    /** A light base: dark text on pale surfaces. */
    val isLight: Boolean = false,
)

/** What is drawn behind the screens. */
enum class BackdropStyle {
    /** The plain background color. */
    Plain,

    /** Soft colored lights behind translucent, frosted surfaces. */
    Glass,

    /** Deep blue with soft flowing light, like the PlayStation Store. */
    Ps2,
}

/** How a theme shapes its surfaces, beyond colors. */
@Immutable
data class ThemeLook(
    /** Corner of cards and dialogs. */
    val corner: Dp,
    /** Hairline around every surface; transparent for none. */
    val edge: Color,
    /** Soft shadow under surfaces at rest (cards that sit on the page). */
    val restingElevation: Dp,
    val backdrop: BackdropStyle,
    /** Buttons and chips with barely rounded corners instead of pills. */
    val squareControls: Boolean = false,
    /** How much a focused surface grows; flat designs barely move. */
    val focusScale: Float = 1.07f,
    /** Thickness of the focus frame. */
    val focusBorder: Dp = 3.dp,
    /** A fixed focus color (the Switch's cyan frame), whatever the accent. */
    val focusColor: Color? = null,
    /** Thin rules under the top bar and above the button hints, as on the Switch. */
    val hairlines: Boolean = false,
    /** Glass edge: a light line brighter at the top, like light catching the rim. */
    val glossy: Boolean = false,
    /** The top bar and the button hints sit on bands of the accent color (the Nintendo eShop's red). */
    val accentBars: Boolean = false,
    /** Section titles are underlined in the accent color. */
    val sectionRule: Boolean = false,
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
    /** Opaque color standing for the base in the settings (a translucent surface would not show). */
    val swatch: Color,
    val look: ThemeLook = PLAIN_LOOK,
    val isLight: Boolean = false,
)

private val PLAIN_LOOK = ThemeLook(corner = 14.dp, edge = Color.Transparent, restingElevation = 0.dp, backdrop = BackdropStyle.Plain)

object ThemePalettes {

    private fun base(base: ThemeBase) = when (base) {
        ThemeBase.Night -> Base(
            Color(0xFF0A0D13), Color(0xFF121722), Color(0xFF1A2130), Color(0xFF242D40), Color(0xFF2E3850),
            Color(0xFFF2F4F8), Color(0xFF9AA4B6), Color(0xFF677187), swatch = Color(0xFF1A2130),
        )
        // Pure black: true off pixels on OLED handhelds.
        ThemeBase.Oled -> Base(
            Color(0xFF000000), Color(0xFF0B0B0E), Color(0xFF15161B), Color(0xFF1F2027), Color(0xFF2A2C35),
            Color(0xFFF2F4F8), Color(0xFF9EA3AE), Color(0xFF6A6F7A), swatch = Color(0xFF15161B),
        )
        ThemeBase.Slate -> Base(
            Color(0xFF15171A), Color(0xFF1D2024), Color(0xFF262A30), Color(0xFF30353D), Color(0xFF3B414B),
            Color(0xFFF1F3F5), Color(0xFFA3A9B3), Color(0xFF6E7480), swatch = Color(0xFF262A30),
        )
        ThemeBase.Twilight -> Base(
            Color(0xFF0E0A16), Color(0xFF171123), Color(0xFF211830), Color(0xFF2C2140), Color(0xFF3A2C52),
            Color(0xFFF4F0FA), Color(0xFFA99FBC), Color(0xFF72688A), swatch = Color(0xFF211830),
        )
        // Frosted glass: white veils of growing strength over colored lights.
        ThemeBase.Glass -> Base(
            Color(0xFF070A18), Color(0x14FFFFFF), Color(0x24FFFFFF), Color(0x38FFFFFF), Color(0x55FFFFFF),
            Color(0xFFFFFFFF), Color(0xFFD0D8EE), Color(0xFF9AA6C6), swatch = Color(0xFF4B5C9A),
            look = ThemeLook(corner = 26.dp, edge = Color.Transparent, restingElevation = 0.dp, backdrop = BackdropStyle.Glass, glossy = true, focusScale = 1.05f),
        )
        // PlayStation 2 menu: near-black blue, thin blue lines, almost square corners.
        ThemeBase.Ps2 -> Base(
            Color(0xFF071B7A), Color(0x8C1F4FC4), Color(0xA62A5FD6), Color(0xC03B73E6), Color(0xFF7FA6FF),
            Color(0xFFF6F9FF), Color(0xFFC3D4FA), Color(0xFF93AEEA), swatch = Color(0xFF1F4FC4),
            look = ThemeLook(
                corner = 1.dp, edge = Color(0x59FFFFFF), restingElevation = 0.dp, backdrop = BackdropStyle.Ps2,
                squareControls = true, focusScale = 1.02f, focusBorder = 2.dp, focusColor = Color(0xFFFFFFFF),
            ),
        )
        // Nintendo eShop: light gray page, white rounded cards with a soft shadow.
        ThemeBase.Eshop -> Base(
            Color(0xFFFFFFFF), Color(0xFFF1F1F1), Color(0xFFEAEAEA), Color(0xFFDADADA), Color(0xFFC8C8C8),
            Color(0xFF2E2E2E), Color(0xFF666666), Color(0xFF8E8E8E), swatch = Color(0xFFE60012),
            look = ThemeLook(
                corner = 1.dp, edge = Color(0x1F000000), restingElevation = 0.dp, backdrop = BackdropStyle.Plain,
                squareControls = true, focusScale = 1.03f, focusBorder = 3.dp, accentBars = true, sectionRule = true,
            ),
            isLight = true,
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
        ThemeAccent.Red -> Color(0xFFE60012) to Color(0xFFFF6B75)
    }

    /** The accent that suits a style (the eShop is red, the PS2 menu blue…); null keeps the user's. */
    fun recommendedAccent(base: ThemeBase): ThemeAccent? = when (base) {
        ThemeBase.Glass -> ThemeAccent.Cyan
        ThemeBase.Ps2 -> ThemeAccent.Cyan
        ThemeBase.Eshop -> ThemeAccent.Red
        else -> null
    }

    /** Swatch shown for a base in the settings. */
    fun swatch(base: ThemeBase): Color = base(base).swatch

    fun look(base: ThemeBase): ThemeLook = base(base).look

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
            // On a light page the "bright" variant would vanish: marks use the main accent.
            accentBright = if (base.isLight) accent else bright,
            accentSecondary = secondary,
            textPrimary = base.textPrimary,
            textSecondary = base.textSecondary,
            textTertiary = base.textTertiary,
            // White reads best from a distance on any artwork (on a light page, the accent does); the accent is the user's choice.
            focus = when {
                base.look.focusColor != null -> base.look.focusColor
                base.isLight -> accent
                settings.focus == FocusStyle.Accent -> bright
                else -> Color(0xFFFFFFFF)
            },
            isLight = base.isLight,
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
    val look: ThemeLook by derivedStateOf { ThemePalettes.look(settings.base) }
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
    /** Label on an accent-colored surface (buttons, selected chips): white on any theme. */
    val OnAccent = Color(0xFFFFFFFF)
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
