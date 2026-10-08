package com.rshop.navigation

import android.view.KeyEvent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.rshop.MainActivity
import com.rshop.data.demo.DemoCatalogSeeder
import com.rshop.ui.home.HOME_LIST_TAG
import com.rshop.R
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
import javax.inject.Inject

/** Drives the real activity, nav graph and view models (with the sample catalogue). */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AppNavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Before the activity starts: "remove animations", so the featured carousel's progress bar
    // does not animate forever and Compose can go idle between steps.
    @get:Rule(order = 1)
    val noAnimations = object : org.junit.rules.ExternalResource() {
        override fun before() {
            android.provider.Settings.Global.putFloat(
                androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                0f,
            )
        }
    }

    // v1 rule on purpose: under the v2 rule's StandardTestDispatcher, a key event dispatched to the
    // activity never resumes the tab-switch collector. To revisit when migrating to v2.
    @Suppress("DEPRECATION")
    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    private fun pressKey(keyCode: Int) {
        composeRule.runOnUiThread {
            composeRule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            composeRule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        composeRule.waitForIdle()
    }

    private fun waitForNode(nodes: AndroidComposeTestRule<*, *>.() -> SemanticsNodeInteractionCollection) {
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.nodes().fetchSemanticsNodes().isNotEmpty() }
    }

    @Inject
    lateinit var demoCatalogSeeder: DemoCatalogSeeder

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        hiltRule.inject()
        // HiltTestApplication does not run RShopApp.onCreate, so seed the demo catalogue here.
        runBlocking { demoCatalogSeeder.seedIfEmpty() }
        composeRule.waitForIdle()
    }

    @Test
    fun startsOnHomeWithFeaturedGame() {
        composeRule.onNodeWithText(text(R.string.home_featured).uppercase()).assertIsDisplayed()
    }

    @Test
    fun clickingTabsSwitchesScreens() {
        composeRule.onNodeWithTag("tab_Library").performClick()
        composeRule.onNodeWithText(text(R.string.library_empty_title)).assertIsDisplayed()

        composeRule.onNodeWithTag("tab_Settings").performClick()
        composeRule.onNodeWithText(text(R.string.settings_games_dir)).assertIsDisplayed()
    }

    @Test
    fun shoulderButtonsCycleTabs() {
        pressKey(KeyEvent.KEYCODE_BUTTON_R1)
        composeRule.onNodeWithText(text(R.string.store_search_hint)).assertIsDisplayed()

        pressKey(KeyEvent.KEYCODE_BUTTON_R1)
        composeRule.onNodeWithText(text(R.string.favorites_empty_title)).assertIsDisplayed()

        pressKey(KeyEvent.KEYCODE_BUTTON_R1)
        composeRule.onNodeWithText(text(R.string.library_empty_title)).assertIsDisplayed()

        pressKey(KeyEvent.KEYCODE_BUTTON_L1)
        pressKey(KeyEvent.KEYCODE_BUTTON_L1)
        pressKey(KeyEvent.KEYCODE_BUTTON_L1)
        composeRule.onNodeWithText(text(R.string.home_featured).uppercase()).assertIsDisplayed()

        // Wraps around from Home to Settings.
        pressKey(KeyEvent.KEYCODE_BUTTON_L1)
        composeRule.onNodeWithText(text(R.string.settings_games_dir)).assertIsDisplayed()
    }

    @Test
    fun openingAGameShowsDetailsAndBackReturnsHome() {
        composeRule.onNodeWithText(text(R.string.action_view_game)).performClick()
        waitForNode { onAllNodesWithText(text(R.string.action_install)) }
        composeRule.onNodeWithText(text(R.string.action_install)).assertIsDisplayed()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText(text(R.string.home_featured).uppercase()).assertIsDisplayed()
    }

    @Test
    fun favoriteFromGamePageAppearsOnHome() {
        // The label is also the Favorites tab: only the tab shows it until a favorite exists.
        composeRule.onAllNodesWithText(text(R.string.home_favorites)).assertCountEquals(1)

        composeRule.onNodeWithText(text(R.string.action_view_game)).performClick()
        waitForNode { onAllNodesWithContentDescription(text(R.string.action_favorite)) }
        composeRule.onNodeWithContentDescription(text(R.string.action_favorite)).performClick()
        // Room writes on its own executor, which waitForIdle does not track.
        waitForNode { onAllNodesWithContentDescription(text(R.string.action_unfavorite)) }
        composeRule.onNodeWithContentDescription(text(R.string.action_unfavorite)).assertIsDisplayed()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        // Shelves below the hero are off-screen, and lazy lists only compose what is visible.
        waitForNode { onAllNodesWithTag(HOME_LIST_TAG) }
        composeRule.onNodeWithTag(HOME_LIST_TAG).performScrollToKey("viewed")
        waitForNode { onAllNodesWithText(text(R.string.home_recently_viewed)) }
        composeRule.onNodeWithTag(HOME_LIST_TAG).performScrollToKey("favorites")
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(text(R.string.home_favorites)).fetchSemanticsNodes().size == 2 }
    }
}
