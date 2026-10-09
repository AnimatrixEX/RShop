package com.rshop.data.source

import com.rshop.di.ApplicationScope
import com.rshop.scraper.drive.auth.LoopbackReceiver
import com.rshop.scraper.drive.auth.OAuthException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Where a Google sign-in stands, for the screen that started it. */
sealed interface SignInState {
    data object Idle : SignInState

    /** The browser page is open; RShop waits for Google to send the player back. */
    data object Waiting : SignInState

    /** Google answered; the code is being exchanged for the tokens. */
    data object Finishing : SignInState

    data class Failed(val kind: OAuthException.Kind, val detail: String?) : SignInState
}

/**
 * Signs the player in with their Google account: the sign-in page opens in the device's browser, and
 * Google sends the player back to a one-shot server on the loopback address that RShop runs while
 * waiting (see [LoopbackReceiver]). Runs in the application scope, so leaving the screen for the
 * browser does not cancel it.
 */
@Singleton
class GoogleSignIn @Inject constructor(
    private val settings: DriveSettings,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()
    private var job: Job? = null
    private var receiver: LoopbackReceiver? = null

    /** Starts a sign-in and returns the page to open in the browser; null when no OAuth client is saved. */
    suspend fun begin(): String? {
        val client = settings.oauthClient() ?: return null
        cancel()
        val receiver = try {
            LoopbackReceiver()
        } catch (e: IOException) {
            _state.value = SignInState.Failed(OAuthException.Kind.Other, e.message)
            return null
        }
        this.receiver = receiver
        val session = settings.oauth.begin(client, receiver.redirectUri)
        _state.value = SignInState.Waiting
        job = scope.launch(Dispatchers.IO) {
            try {
                when (val answer = receiver.await(session.state, TIMEOUT_MS)) {
                    is LoopbackReceiver.Result.Code -> {
                        _state.value = SignInState.Finishing
                        val tokens = settings.oauth.exchange(client, session, answer.code)
                        settings.saveSignIn(tokens)
                        _state.value = SignInState.Idle
                    }
                    is LoopbackReceiver.Result.Failure -> _state.value = SignInState.Failed(
                        if (answer.error == "access_denied") OAuthException.Kind.Denied else OAuthException.Kind.Other,
                        answer.error,
                    )
                }
            } catch (e: OAuthException) {
                Timber.w(e, "Google sign-in failed")
                _state.value = SignInState.Failed(e.kind, e.message)
            } catch (e: IOException) {
                // Cancelled by the player: the waiting server was closed under it, which is no failure.
                if (!isActive) return@launch
                Timber.w(e, "Google sign-in did not come back")
                _state.value = SignInState.Failed(OAuthException.Kind.Network, e.message)
            } finally {
                receiver.close()
            }
        }
        return session.authUrl
    }

    /** Gives up the sign-in in progress (the player closed the browser page and went back). */
    fun cancel() {
        job?.cancel()
        job = null
        // The server waits in a blocking call: closing it is what ends the wait.
        receiver?.close()
        receiver = null
        if (_state.value is SignInState.Waiting || _state.value is SignInState.Finishing) _state.value = SignInState.Idle
    }

    fun dismissError() {
        if (_state.value is SignInState.Failed) _state.value = SignInState.Idle
    }

    private companion object {
        const val TIMEOUT_MS = 5 * 60_000L
    }
}
