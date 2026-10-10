package com.rshop.navigation

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.rshop.MainActivity
import com.rshop.data.demo.DemoCatalogSeeder
import com.rshop.domain.model.CoverStyle
import com.rshop.domain.model.ThemeBase
import com.rshop.domain.model.ThemeSettings
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

/** Draws the real screens (demo catalogue) in a theme to build/screen-previews, to look for color bugs without a device. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "w1000dp-h560dp-xhdpi")
class ScreensPreviewTest {

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
            // The app reads WorkManager as it starts (sync state on the Settings button).
            WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        }
    }

    @Suppress("DEPRECATION")
    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var demoCatalogSeeder: DemoCatalogSeeder

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking { demoCatalogSeeder.seedIfEmpty() }
        composeRule.waitForIdle()
    }

    private fun shoot(name: String) {
        composeRule.waitForIdle()
        val out = File("build/screen-previews").apply { mkdirs() }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screens(base: ThemeBase, cover: CoverStyle = CoverStyle.Flat) {
        composeRule.runOnUiThread {
            ActiveTheme.settings = ThemeSettings(base = base, accent = ThemePalettes.recommendedAccent(base) ?: ActiveTheme.settings.accent, coverStyle = cover)
        }
        composeRule.waitForIdle()
        shoot("${base.name}-home")
        composeRule.onNodeWithTag("tab_Store").performClick()
        shoot("${base.name}-store")
        composeRule.onNodeWithTag("tab_Settings").performClick()
        shoot("${base.name}-settings")
        composeRule.onNodeWithTag("tab_Library").performClick()
        shoot("${base.name}-library")
    }

    @Test fun eshop() = screens(ThemeBase.Eshop)
    @Test fun playstation() = screens(ThemeBase.Ps2)
    @Test fun glass() = screens(ThemeBase.Glass)
    @Test fun night3d() = screens(ThemeBase.Night, CoverStyle.Box3d)
}
