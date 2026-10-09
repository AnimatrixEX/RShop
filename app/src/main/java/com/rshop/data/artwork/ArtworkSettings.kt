package com.rshop.data.artwork

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.artworkDataStore: DataStore<Preferences> by preferencesDataStore(name = "artwork")

data class ArtworkStatus(
    val keyConfigured: Boolean = false,
    /** Last 4 characters, to tell keys apart without showing them. */
    val keyHint: String? = null,
    val invalidKey: Boolean = false,
    /** Box art from the Libretro thumbnails server is used (no key needed). */
    val libretroEnabled: Boolean = true,
    /** Descriptions from Wikipedia are used for games the source describes nothing of. */
    val metadataEnabled: Boolean = true,
)

/** The user's SteamGridDB API key, stored encrypted (see [SecretCipher]). */
@Singleton
class ArtworkSettings @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: SecretCipher,
) {
    private val dataStore = context.artworkDataStore

    val status: Flow<ArtworkStatus> = dataStore.data.map { prefs ->
        val key = prefs[Keys.ApiKey]?.let(cipher::decrypt)
        ArtworkStatus(
            keyConfigured = key != null,
            keyHint = key?.takeLast(4),
            invalidKey = prefs[Keys.InvalidKey] ?: false,
            libretroEnabled = prefs[Keys.Libretro] ?: true,
            metadataEnabled = prefs[Keys.Metadata] ?: true,
        )
    }

    /** The key, unless SteamGridDB already rejected it (the user must enter a new one). */
    suspend fun usableApiKey(): String? {
        val prefs = dataStore.data.first()
        if (prefs[Keys.InvalidKey] == true) return null
        return prefs[Keys.ApiKey]?.let(cipher::decrypt)
    }

    suspend fun libretroEnabled(): Boolean = dataStore.data.first()[Keys.Libretro] ?: true

    suspend fun metadataEnabled(): Boolean = dataStore.data.first()[Keys.Metadata] ?: true

    suspend fun setMetadataEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.Metadata] = enabled }
    }

    suspend fun setLibretroEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.Libretro] = enabled }
    }

    /**
     * True once: Libretro covers did not exist before, so games that were asked and found nothing
     * are asked again (only the ones still without a cover).
     */
    suspend fun consumeLibretroUpgrade(): Boolean {
        var first = false
        dataStore.edit { prefs ->
            if (prefs[Keys.LibretroSeen] != true) {
                prefs[Keys.LibretroSeen] = true
                first = true
            }
        }
        return first
    }

    /** Blank removes the key. */
    suspend fun setApiKey(key: String) {
        val trimmed = key.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(Keys.ApiKey) else prefs[Keys.ApiKey] = cipher.encrypt(trimmed)
            prefs.remove(Keys.InvalidKey)
        }
    }

    /** True once per [ArtworkTitle.MATCHER_VERSION]: covers matched by older rules are redone. */
    suspend fun consumeMatcherUpgrade(): Boolean {
        var upgraded = false
        dataStore.edit { prefs ->
            if ((prefs[Keys.MatcherVersion] ?: 1) < ArtworkTitle.MATCHER_VERSION) {
                prefs[Keys.MatcherVersion] = ArtworkTitle.MATCHER_VERSION
                upgraded = true
            }
        }
        return upgraded
    }

    suspend fun markInvalidKey() {
        dataStore.edit { it[Keys.InvalidKey] = true }
    }

    private object Keys {
        val ApiKey = stringPreferencesKey("steamgriddb_api_key")
        val InvalidKey = booleanPreferencesKey("steamgriddb_invalid_key")
        val Libretro = booleanPreferencesKey("libretro_enabled")
        val Metadata = booleanPreferencesKey("metadata_enabled")
        val LibretroSeen = booleanPreferencesKey("libretro_seen")
        val MatcherVersion = intPreferencesKey("steamgriddb_matcher_version")
    }
}
