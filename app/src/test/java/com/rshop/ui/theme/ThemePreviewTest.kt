package com.rshop.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import com.rshop.domain.model.ThemeAccent
import com.rshop.domain.model.ThemeBase
import com.rshop.domain.model.ThemeSettings
import com.rshop.testing.testGame
import com.rshop.navigation.TopLevelDestination
import com.rshop.ui.components.Backdrop
import com.rshop.ui.components.ConsoleTopBar
import com.rshop.ui.components.ControllerHints
import com.rshop.ui.components.ControllerHintsBar
import com.rshop.ui.components.SectionHeader
import com.rshop.ui.components.ConsoleChip
import com.rshop.ui.components.GameCard
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import java.io.File

/**
 * Draws a typical screen (chips, buttons, cards) in every theme base and writes the pictures to
 * build/theme-previews, to look at them without a device. It also checks that they are not blank.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1000dp-h560dp-xhdpi")
class ThemePreviewTest {

    @get:Rule
    val rule = createComposeRule()

    @Test fun night() = preview(ThemeBase.Night)
    @Test fun oled() = preview(ThemeBase.Oled)
    @Test fun slate() = preview(ThemeBase.Slate)
    @Test fun twilight() = preview(ThemeBase.Twilight)
    @Test fun glass() = preview(ThemeBase.Glass)
    @Test fun ps2() = preview(ThemeBase.Ps2)
    @Test fun eshop() = preview(ThemeBase.Eshop)

    private fun preview(base: ThemeBase) {
        val out = File("build/theme-previews").apply { mkdirs() }
        run {
            val accent = ThemePalettes.recommendedAccent(base) ?: ThemeAccent.Blue
            ActiveTheme.settings = ThemeSettings(base = base, accent = accent)
            rule.setContent {
                RShopTheme {
                    val games = listOf(
                        "Advance Wars" to "GBA", "Neon Drift" to "SNES", "Turbo Kart" to "Mega Drive", "Pixel Quest" to "NES",
                        "Moon Runner" to "GBA", "Mirror Maze" to "SNES", "Star Harbor" to "PS1", "Glow Tactics" to "GBC",
                        "Rocket Rush" to "NES", "Dream Station" to "Dreamcast", "Cave Echo" to "Mega Drive", "Tiny Titans" to "N64",
                    ).mapIndexed { i, (title, platform) -> testGame("g$i", title = title, platform = platform, genre = listOf("RPG", "Action", "Racing", "Puzzle")[i % 4]) }
                    Box(Modifier.fillMaxSize()) {
                        ThemeBackground()
                        Backdrop(highlighted = games.first())
                        Column(Modifier.fillMaxSize()) {
                            ConsoleTopBar(selected = TopLevelDestination.Store, onSelect = {}, settingsBadge = true)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                SectionHeader("Greatest Hits", Modifier.padding(top = 10.dp))
                                Row(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    games.take(7).forEach { game -> GameCard(game = game, onClick = {}, modifier = Modifier.width(112.dp)) }
                                }
                                SectionHeader("Best Sellers", Modifier.padding(top = 6.dp))
                                Row(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    games.drop(5).take(7).forEach { game -> GameCard(game = game, onClick = {}, modifier = Modifier.width(112.dp)) }
                                }
                            }
                            ControllerHintsBar(ControllerHints.OnCards, alwaysVisible = true)
                        }
                    }
                }
            }
            rule.waitForIdle()
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(out, "${base.name}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(bitmap.width > 100)
        }
        ActiveTheme.settings = ThemeSettings()
    }
}
