package com.rshop.data.source

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rshop.data.artwork.SecretCipher
import com.rshop.di.ApplicationScope
import com.rshop.scraper.drive.DriveCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.driveDataStore: DataStore<Preferences> by preferencesDataStore(name = "drive")

data class DriveKeyStatus(
    val configured: Boolean = false,
    /** Last 4 characters, to tell keys apart without showing them. */
    val hint: String? = null,
)

/**
 * The user's Google Cloud API key for the Drive API, stored encrypted (see [SecretCipher]). Network
 * interceptors read [credentials] synchronously, so the decrypted key is kept in memory once loaded.
 */
@Singleton
class DriveSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SecretCipher,
    @ApplicationScope scope: CoroutineScope,
) {
    private val dataStore = context.driveDataStore

    @Volatile
    private var cached: DriveCredentials? = null

    /** SHA-1 of the signing certificate, for keys restricted to this Android app. */
    private val certificate: String? by lazy { signingCertSha1() }

    val status: Flow<DriveKeyStatus> = dataStore.data.map { prefs ->
        val key = prefs[ApiKey]?.let(cipher::decrypt)
        DriveKeyStatus(configured = key != null, hint = key?.takeLast(4))
    }

    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) {
            dataStore.data.collect { prefs ->
                cached = prefs[ApiKey]?.let(cipher::decrypt)?.let(::credentialsOf)
                loaded.complete(Unit)
            }
        }
    }

    /** The key with the app's identity, or null when none is entered. */
    suspend fun awaitCredentials(): DriveCredentials? {
        loaded.await()
        return cached
    }

    /**
     * Same, for OkHttp interceptors (never on the main thread). Requests made through the Drive
     * client wait for [awaitCredentials] first, so this does not block in practice.
     */
    fun credentials(): DriveCredentials? {
        if (!loaded.isCompleted) runBlocking { loaded.await() }
        return cached
    }

    /** Blank removes the key. */
    suspend fun setApiKey(key: String) {
        val trimmed = key.trim()
        dataStore.edit { prefs -> if (trimmed.isEmpty()) prefs.remove(ApiKey) else prefs[ApiKey] = cipher.encrypt(trimmed) }
        cached = trimmed.takeIf { it.isNotEmpty() }?.let(::credentialsOf)
    }

    private fun credentialsOf(key: String) = DriveCredentials(key, context.packageName, certificate)

    private fun signingCertSha1(): String? = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()
        signer?.let { MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).joinToString("") { b -> "%02X".format(b) } }
    } catch (e: Exception) {
        Timber.w(e, "Cannot read the signing certificate")
        null
    }

    private companion object {
        val ApiKey = stringPreferencesKey("drive_api_key")
    }
}
