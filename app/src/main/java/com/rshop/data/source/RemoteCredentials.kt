package com.rshop.data.source

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rshop.data.artwork.SecretCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.remoteCredentialsStore: DataStore<Preferences> by preferencesDataStore(name = "remote_credentials")

/**
 * Passwords of FTP, FTPS and SFTP sources, one per source id, stored encrypted (see [SecretCipher]).
 * They are never part of a source's configuration, its export or a backup.
 */
@Singleton
class RemoteCredentials @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: SecretCipher,
) {
    private val store = context.remoteCredentialsStore

    suspend fun password(sourceId: String): String? =
        store.data.first()[key(sourceId)]?.let(cipher::decrypt)

    suspend fun hasPassword(sourceId: String): Boolean = store.data.first()[key(sourceId)] != null

    /** Saves the password of a source; null or blank removes it (anonymous servers have none). */
    suspend fun setPassword(sourceId: String, password: String?) {
        store.edit { prefs ->
            if (password.isNullOrEmpty()) prefs.remove(key(sourceId)) else prefs[key(sourceId)] = cipher.encrypt(password)
        }
    }

    suspend fun remove(sourceId: String) = setPassword(sourceId, null)

    private fun key(sourceId: String) = stringPreferencesKey("pw.$sourceId")
}
