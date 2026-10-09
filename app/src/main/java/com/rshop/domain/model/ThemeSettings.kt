package com.rshop.domain.model

/** How the app looks. Most bases are dark (the UI is read from 1–3 m on handhelds and TVs); Glass, PS2 and eShop are looks of their own. */
data class ThemeSettings(
    val base: ThemeBase = ThemeBase.Night,
    val accent: ThemeAccent = ThemeAccent.Blue,
    val focus: FocusStyle = FocusStyle.White,
    val textSize: TextSize = TextSize.Normal,
    /** Blurred artwork of the highlighted game behind Home and game pages. */
    val dynamicBackdrop: Boolean = true,
    /** How covers are shown: flat, or as game boxes in perspective. */
    val coverStyle: CoverStyle = CoverStyle.Flat,
    /** The Glass and PlayStation backgrounds drift slowly; off by default, since an animated screen keeps the GPU awake. */
    val animatedBackground: Boolean = false,
)

enum class CoverStyle { Flat, Box3d }

enum class ThemeBase { Night, Oled, Slate, Twilight, Glass, Ps2, Eshop }

enum class ThemeAccent { Blue, Violet, Cyan, Green, Orange, Pink, Red }

enum class FocusStyle { White, Accent }

enum class TextSize(val scale: Float) { Normal(1f), Large(1.12f) }
