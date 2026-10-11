package com.rshop.navigation

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.rshop.MainActivity
import com.rshop.data.demo.DemoCatalogSeeder
import com.rshop.data.source.SourceRepository
import com.rshop.domain.model.CoverStyle
import com.rshop.domain.model.ThemeBase
import com.rshop.domain.model.ThemeSettings
import com.rshop.scraper.config.ListRules
import com.rshop.scraper.config.ScraperConfig
import com.rshop.ui.home.HOME_LIST_TAG
import com.rshop.ui.theme.ActiveTheme
import com.rshop.ui.theme.ThemePalettes
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.inject.Inject

/**
 * Draws the screens shown in the README (demo catalogue, English, Night theme, 3D boxes) into
 * build/readme-screenshots: no device needed. Copy the PNGs to docs/screenshots when they change.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "en-w1000dp-h560dp-xhdpi")
class ReadmeScreenshotsTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val noAnimations = object : org.junit.rules.ExternalResource() {
        override fun before() {
            android.provider.Settings.Global.putFloat(
                ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                0f,
            )
            WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        }
    }

    @Suppress("DEPRECATION")
    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var demoCatalogSeeder: DemoCatalogSeeder

    @Inject
    lateinit var sourceRepository: SourceRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            demoCatalogSeeder.seedIfEmpty()
        }
        composeRule.runOnUiThread {
            ActiveTheme.settings = ThemeSettings(
                base = ThemeBase.Night,
                accent = ThemePalettes.recommendedAccent(ThemeBase.Night) ?: ActiveTheme.settings.accent,
                coverStyle = CoverStyle.Box3d,
            )
        }
        composeRule.waitForIdle()
    }

    private fun shoot(name: String) {
        composeRule.waitForIdle()
        val out = File("build/readme-screenshots").apply { mkdirs() }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun home() {
        shoot("home")
        composeRule.onNodeWithTag(HOME_LIST_TAG).performScrollToIndex(1)
        shoot("home-shelves")
    }

    @Test
    fun details() {
        composeRule.onNodeWithText("View game").performClick()
        shoot("details")
    }

    @Test
    fun sources() {
        // The demo games belong to the source "demo": the card counts them. Saved here only, so the game
        // pages of the other screens do not try to reach a site.
        runBlocking {
            sourceRepository.save(
                ScraperConfig(
                    id = "demo",
                    name = "Homebrew Hub",
                    baseUrl = "https://homebrew.example.org/",
                    listUrl = "/games?page={page}",
                    list = ListRules(item = listOf("div.card")),
                ),
            )
        }
        composeRule.onNodeWithTag("tab_Settings").performClick()
        composeRule.onNodeWithText("Catalogue sources").performClick()
        shoot("sources")
    }

    @Test
    fun appearance() {
        composeRule.onNodeWithTag("tab_Settings").performClick()
        composeRule.onNodeWithText("Appearance").performClick()
        shoot("appearance")
    }
}
