package com.rshop.data.source

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads Google Drive with the Google account already signed in on the device, through Google Play
 * Services (the system's account picker and consent screen: no password is typed in RShop). Only
 * available on devices that have Play Services; elsewhere the browser sign-in is used instead.
 * Google matches the app by its package name and signing certificate, so no secret is involved.
 */
@Singleton
class SystemAccountAuth @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface Result {
        data class Token(val accessToken: String, val email: String?) : Result

        /** The player has to agree (and pick the account) on Google's screen: launch [intent]. */
        data class Consent(val intent: PendingIntent) : Result
        data class Failed(val reason: Reason, val message: String?) : Result
    }

    enum class Reason {
        /** The app's package and signature are not registered as an OAuth client in a Google Cloud project. */
        NotRegistered,
        Cancelled,
        Network,
        Other,
    }

    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_READONLY)))
        .build()

    /** Whether Google Play Services is there to do it. */
    val available: Boolean
        get() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** Asks for read-only Drive access. Blocks on Play Services: never call it on the main thread. */
    fun authorizeBlocking(): Result = try {
        toResult(Tasks.await(Identity.getAuthorizationClient(context).authorize(request), TIMEOUT_SECONDS, TimeUnit.SECONDS))
    } catch (e: ExecutionException) {
        failed(e.cause ?: e)
    } catch (e: TimeoutException) {
        Result.Failed(Reason.Network, "Google Play Services did not answer")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Result.Failed(Reason.Other, "interrupted")
    }

    suspend fun authorize(): Result = withContext(Dispatchers.IO) { authorizeBlocking() }

    /** Reads the answer of the consent screen. */
    fun fromIntent(data: Intent?): Result = try {
        toResult(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
    } catch (e: ApiException) {
        failed(e)
    }

    private fun toResult(result: AuthorizationResult): Result {
        if (result.hasResolution()) {
            return result.pendingIntent?.let { Result.Consent(it) } ?: Result.Failed(Reason.Other, "no consent screen")
        }
        val token = result.accessToken ?: return Result.Failed(Reason.Other, "no access token")
        return Result.Token(token, result.toGoogleSignInAccount()?.email)
    }

    private fun failed(cause: Throwable): Result.Failed {
        Timber.w(cause, "Google authorization failed")
        val code = (cause as? ApiException)?.statusCode
        val reason = when (code) {
            CommonStatusCodes.DEVELOPER_ERROR -> Reason.NotRegistered
            CommonStatusCodes.CANCELED, CommonStatusCodes.INTERRUPTED -> Reason.Cancelled
            CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT -> Reason.Network
            else -> Reason.Other
        }
        return Result.Failed(reason, cause.message ?: code?.let { "code $it" })
    }

    companion object {
        const val DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
        private const val TIMEOUT_SECONDS = 30L
    }
}
