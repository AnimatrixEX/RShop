package com.rshop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.rshop.domain.model.CoverStyle
import com.rshop.domain.model.ThemeSettings
import com.rshop.testing.testGame
import com.rshop.ui.theme.ActiveTheme
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.theme.RShopTheme
import com.rshop.ui.theme.ThemeBackground
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws the 3D game boxes (resting and in focus) to build/theme-previews, to look at them without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1000dp-h420dp-xhdpi")
class GameCasePreviewTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun boxes() {
        ActiveTheme.settings = ThemeSettings(coverStyle = CoverStyle.Box3d)
        val games = listOf("Advance Wars", "Neon Drift", "Turbo Kart", "Pixel Quest", "Moon Runner", "Mirror Maze")
            .mapIndexed { i, title -> testGame("g$i", title = title, platform = listOf("GBA", "SNES", "Mega Drive", "NES", "GBA", "SNES")[i]) }
        rule.setContent {
            RShopTheme {
                Box(Modifier.fillMaxSize()) {
                    ThemeBackground()
                    Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Resting boxes (turned), then one in focus (faces the player)", color = RShopColors.TextPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            games.take(4).forEach { game -> GameCard(game = game, onClick = {}, modifier = Modifier.width(132.dp)) }
                            GameCase(
                                seed = games[4].id, title = games[4].title, focused = true,
                                modifier = Modifier.width(150.dp).aspectRatio(0.75f),
                                front = { GameCover(game = games[4], showTitle = false, modifier = Modifier.fillMaxSize()) },
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/theme-previews").apply { mkdirs() }
        File(out, "Box3d.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bitmap.width > 100)
        ActiveTheme.settings = ThemeSettings()
    }
}
