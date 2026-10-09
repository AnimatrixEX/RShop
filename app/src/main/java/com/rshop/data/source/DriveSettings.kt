package com.rshop.data.source

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rshop.data.artwork.SecretCipher
import com.rshop.di.ApplicationScope
import com.rshop.scraper.drive.DriveCredentials
import com.rshop.scraper.drive.auth.GoogleOAuth
import com.rshop.scraper.drive.auth.OAuthClient
import com.rshop.scraper.drive.auth.OAuthException
import com.rshop.scraper.drive.auth.OAuthTokens
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.driveDataStore: DataStore<Preferences> by preferencesDataStore(name = "drive")

/** What the player has set up to let RShop read Google Drive. */
data class DriveKeyStatus(
    /** An API key is saved (for folders anyone with the link can read). */
    val configured: Boolean = false,
    /** Last 4 characters of the key, to tell keys apart without showing them. */
    val hint: String? = null,
    /** An OAuth client (id and secret from the player's Google Cloud project) is saved. */
    val clientConfigured: Boolean = false,
    /** The Google account signed in, null when none. */
    val email: String? = null,
    /** The sign-in stopped working (revoked, or expired after 7 days for an app in testing): sign in again. */
    val signInExpired: Boolean = false,
) {
    val signedIn: Boolean get() = email != null
}

/**
 * How RShop is allowed to read Google Drive: the Google account the player signed in with (private and
 * shared-with-me folders), or the API key of their own Google Cloud project (public folders). Secrets
 * are stored encrypted (see [SecretCipher]). Network interceptors read [credentials] synchronously,
 * so what they need is kept in memory once loaded, and an expired access token is renewed there.
 */
@Singleton
class DriveSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SecretCipher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val dataStore = context.driveDataStore

    /** A plain client of its own: the app's client carries the interceptor that calls back into this class. */
    val oauth = GoogleOAuth(
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    )

    @Volatile private var keyCredentials: DriveCredentials? = null
    @Volatile private var client: OAuthClient? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAt = 0L
    private val refreshLock = Any()

    /** SHA-1 of the signing certificate, for keys restricted to this Android app. */
    private val certificate: String? by lazy { signingCertSha1() }

    val status: Flow<DriveKeyStatus> = dataStore.data.map { prefs ->
        val key = prefs[ApiKey]?.let(cipher::decrypt)
        DriveKeyStatus(
            configured = key != null,
            hint = key?.takeLast(4),
            clientConfigured = prefs[ClientId] != null && prefs[ClientSecret]?.let(cipher::decrypt) != null,
            email = prefs[Email]?.takeIf { prefs[RefreshToken] != null },
            signInExpired = prefs[Expired] == true,
        )
    }

    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) {
            dataStore.data.collect { prefs ->
                keyCredentials = prefs[ApiKey]?.let(cipher::decrypt)?.let { DriveCredentials(it, context.packageName, certificate) }
                val id = prefs[ClientId]
                val secret = prefs[ClientSecret]?.let(cipher::decrypt)
                client = if (id != null && secret != null) OAuthClient(id, secret) else null
                val stored = prefs[RefreshToken]?.let(cipher::decrypt)
                if (stored != refreshToken) accessToken = null
                refreshToken = stored
                loaded.complete(Unit)
            }
        }
    }

    /** The credentials to use now, once the stored settings are loaded. Renewing a token may call the network. */
    suspend fun awaitCredentials(): DriveCredentials? {
        loaded.await()
        return withContext(Dispatchers.IO) { credentials() }
    }

    /**
     * For OkHttp interceptors (never on the main thread): the signed-in account when there is one,
     * else the API key, else null.
     */
    fun credentials(): DriveCredentials? {
        if (!loaded.isCompleted) runBlocking { loaded.await() }
        bearerToken()?.let { return DriveCredentials(bearerToken = it) }
        return keyCredentials
    }

    private fun bearerToken(): String? {
        val now = System.currentTimeMillis()
        accessToken?.takeIf { accessExpiresAt - now > RENEW_MARGIN_MS }?.let { return it }
        val token = refreshToken ?: return null
        val oauthClient = client ?: return null
        synchronized(refreshLock) {
            accessToken?.takeIf { accessExpiresAt - System.currentTimeMillis() > RENEW_MARGIN_MS }?.let { return it }
            return try {
                val renewed = oauth.refresh(oauthClient, token)
                accessToken = renewed.accessToken
                accessExpiresAt = renewed.expiresAtMs
                renewed.accessToken
            } catch (e: OAuthException) {
                Timber.w(e, "Cannot renew the Google access token")
                if (e.kind == OAuthException.Kind.InvalidGrant || e.kind == OAuthException.Kind.InvalidClient) {
                    // Revoked or expired: the player has to sign in again.
                    refreshToken = null
                    accessToken = null
                    scope.launch { markExpired() }
                    null
                } else {
                    // Offline or Google unreachable: the last token may still be good for a moment.
                    accessToken?.takeIf { accessExpiresAt > System.currentTimeMillis() }
                }
            }
        }
    }

    /** Blank removes the key. */
    suspend fun setApiKey(key: String) {
        val trimmed = key.trim()
        dataStore.edit { prefs -> if (trimmed.isEmpty()) prefs.remove(ApiKey) else prefs[ApiKey] = cipher.encrypt(trimmed) }
        keyCredentials = trimmed.takeIf { it.isNotEmpty() }?.let { DriveCredentials(it, context.packageName, certificate) }
    }

    fun oauthClient(): OAuthClient? = client

    /** The OAuth client of the player's Google Cloud project; blank values remove it (and sign out). */
    suspend fun setOAuthClient(id: String, secret: String) {
        signOut()
        val cleanId = id.trim()
        val cleanSecret = secret.trim()
        dataStore.edit { prefs ->
            if (cleanId.isEmpty() || cleanSecret.isEmpty()) {
                prefs.remove(ClientId)
                prefs.remove(ClientSecret)
            } else {
                prefs[ClientId] = cleanId
                prefs[ClientSecret] = cipher.encrypt(cleanSecret)
            }
        }
        client = if (cleanId.isNotEmpty() && cleanSecret.isNotEmpty()) OAuthClient(cleanId, cleanSecret) else null
    }

    /** Keeps what a finished sign-in gave: the refresh token (encrypted) and the account shown to the player. */
    suspend fun saveSignIn(tokens: OAuthTokens) {
        val refresh = tokens.refreshToken ?: refreshToken
            ?: throw OAuthException(OAuthException.Kind.Other, "Google sent no refresh token")
        dataStore.edit { prefs ->
            prefs[RefreshToken] = cipher.encrypt(refresh)
            tokens.email?.let { prefs[Email] = it }
            prefs.remove(Expired)
        }
        refreshToken = refresh
        accessToken = tokens.accessToken
        accessExpiresAt = tokens.expiresAtMs
    }

    /** Forgets the account here and tells Google to cancel the grant. */
    suspend fun signOut() {
        val token = refreshToken
        withContext(Dispatchers.IO) { token?.let(oauth::revoke) }
        dataStore.edit { prefs ->
            prefs.remove(RefreshToken)
            prefs.remove(Email)
            prefs.remove(Expired)
        }
        refreshToken = null
        accessToken = null
    }

    private suspend fun markExpired() {
        dataStore.edit { prefs ->
            prefs.remove(RefreshToken)
            prefs[Expired] = true
        }
    }

    private fun signingCertSha1(): String? = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()
        signer?.let { MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).joinToString("") { b -> "%02X".format(b) } }
    } catch (e: Exception) {
        Timber.w(e, "Cannot read the signing certificate")
        null
    }

    private companion object {
        /** An access token is renewed a minute before it ends. */
        const val RENEW_MARGIN_MS = 60_000L
        val ApiKey = stringPreferencesKey("drive_api_key")
        val ClientId = stringPreferencesKey("google_client_id")
        val ClientSecret = stringPreferencesKey("google_client_secret")
        val RefreshToken = stringPreferencesKey("google_refresh_token")
        val Email = stringPreferencesKey("google_email")
        val Expired = booleanPreferencesKey("google_sign_in_expired")
    }
}
