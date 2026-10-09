package com.rshop

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rshop.navigation.RShopRoot
import com.rshop.ui.theme.RShopTheme
import androidx.lifecycle.lifecycleScope
import com.rshop.domain.repository.SettingsRepository
import com.rshop.ui.theme.ActiveTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Shoulder-button tab switches (-1 = previous, +1 = next), handled here so they work even with nothing focused. */
    private val tabSwitches = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** The controller's X button: go to the Store's search. */
    private val searchRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Inject lateinit var settingsRepository: SettingsRepository

    private var themeLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash stays until the saved theme is known, so the UI never flashes the default one.
        installSplashScreen().setKeepOnScreenCondition { !themeLoaded }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()
        lifecycleScope.launch {
            settingsRepository.settings.map { it.theme }.distinctUntilChanged().collect { theme ->
                ActiveTheme.settings = theme
                themeLoaded = true
            }
        }

        setContent {
            RShopTheme {
                RShopRoot(tabSwitches = tabSwitches, searchRequests = searchRequests)
            }
        }
    }

    /**
     * Shoulder buttons are intercepted before the view hierarchy: Compose would otherwise get
     * the first chance at them (and may consume them), wherever the focus currently is.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_X) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) searchRequests.tryEmit(Unit)
            return true
        }
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> -1
            KeyEvent.KEYCODE_BUTTON_R1 -> 1
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            tabSwitches.tryEmit(step)
        }
        return true
    }

    /** Console-style fullscreen; bars come back temporarily with a swipe from the edge. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
