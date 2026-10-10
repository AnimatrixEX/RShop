package com.rshop.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.rshop.domain.model.FocusStyle
import com.rshop.domain.model.ThemeAccent
import com.rshop.domain.model.ThemeBase
import com.rshop.domain.model.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePalettesTest {

    private fun palette(base: ThemeBase, accent: ThemeAccent = ThemeAccent.Blue, focus: FocusStyle = FocusStyle.White) =
        ThemePalettes.build(ThemeSettings(base = base, accent = accent, focus = focus))

    @Test
    fun `only the eShop is light, and its text is readable on its page`() {
        for (base in ThemeBase.entries) {
            val palette = palette(base)
            assertEquals("$base", base == ThemeBase.Eshop, palette.isLight)
            // Text and page are far apart in brightness, whichever way round.
            val background = if (palette.background.alpha < 1f) Color(0xFF0A0F24) else palette.background
            assertTrue("$base", kotlin.math.abs(palette.textPrimary.luminance() - background.luminance()) > 0.5f)
        }
    }

    @Test
    fun `the focus ring stays visible on the light theme`() {
        // White would vanish on the light page: the accent frames the selection, whatever the setting.
        val light = palette(ThemeBase.Eshop, ThemeAccent.Red, FocusStyle.White)
        assertEquals(light.accent, light.focus)
        // The PlayStation style frames the selection in white.
        assertEquals(Color.White, palette(ThemeBase.Ps2).focus)
        assertEquals(Color.White, palette(ThemeBase.Night).focus)
    }

    @Test
    fun `styles come with the accent that suits them`() {
        assertEquals(ThemeAccent.Red, ThemePalettes.recommendedAccent(ThemeBase.Eshop))
        assertEquals(ThemeAccent.Red, ThemePalettes.recommendedAccent(ThemeBase.EshopDark))
        assertEquals(ThemeAccent.Cyan, ThemePalettes.recommendedAccent(ThemeBase.Ps2))
        assertNull(ThemePalettes.recommendedAccent(ThemeBase.Night))
    }

    @Test
    fun `each look has its own shape and backdrop`() {
        assertEquals(BackdropStyle.Glass, ThemePalettes.look(ThemeBase.Glass).backdrop)
        assertEquals(BackdropStyle.Ps2, ThemePalettes.look(ThemeBase.Ps2).backdrop)
        assertEquals(BackdropStyle.Plain, ThemePalettes.look(ThemeBase.Eshop).backdrop)
        assertTrue(ThemePalettes.look(ThemeBase.Ps2).squareControls)
        assertTrue(ThemePalettes.look(ThemeBase.Eshop).accentBars)
        assertTrue(ThemePalettes.look(ThemeBase.Eshop).sectionRule)
        // The dark eShop keeps the eShop's shape, on a plain dark page.
        val dark = ThemePalettes.look(ThemeBase.EshopDark)
        assertTrue(dark.accentBars && dark.sectionRule && dark.squareControls && !dark.artBackdrop)
        assertTrue(ThemePalettes.look(ThemeBase.Ps2).corner < ThemePalettes.look(ThemeBase.Night).corner)
    }
}
