package com.rshop.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rshop.scraper.ScraperException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Why a sync or a page read failed, in terms the UI can explain. */
enum class SourceErrorKind { Network, AccessDenied, Robots, Structure, Http, InvalidContent, NoSource, Unknown }

data class SourceError(val kind: SourceErrorKind, val detail: String?)

fun Throwable.toSourceError(): SourceError = when (this) {
    is ScraperException.Network -> SourceError(SourceErrorKind.Network, cause?.message)
    is ScraperException.AccessDenied -> SourceError(SourceErrorKind.AccessDenied, "HTTP $code")
    is ScraperException.BlockedByRobots -> SourceError(SourceErrorKind.Robots, url)
    is ScraperException.StructureChanged -> SourceError(SourceErrorKind.Structure, message)
    is ScraperException.Http -> SourceError(SourceErrorKind.Http, "HTTP $code")
    is ScraperException.InvalidContent -> SourceError(SourceErrorKind.InvalidContent, message)
    is NoSourceConfiguredException -> SourceError(SourceErrorKind.NoSource, null)
    is IOException -> SourceError(SourceErrorKind.Network, message)
    else -> SourceError(SourceErrorKind.Unknown, message)
}

data class SyncRecord(
    val lastSuccessAt: Instant?,
    /** End of the last scan that reached the whole catalogue; later syncs only look for new games. */
    val lastFullScanAt: Instant? = null,
    val lastGameCount: Int,
    val lastError: SourceError?,
    val lastErrorAt: Instant?,
)

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_status")

/** Last sync outcome of every source, keyed by source id. */
@Singleton
class SyncStatusStore @Inject constructor(
    @ApplicationContext context: Context,
    private val clock: Clock,
) {
    private val store = context.syncDataStore

    val records: Flow<Map<String, SyncRecord>> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val ids = prefs.asMap().keys.mapNotNull { key -> key.name.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.substringBeforeLast('.') }
            ids.distinct().associateWith { id ->
                val keys = Keys(id)
                SyncRecord(
                    lastSuccessAt = prefs[keys.lastSuccess]?.let(Instant::ofEpochMilli),
                    // Syncs before 0.1.1 were all full scans.
                    lastFullScanAt = (prefs[keys.lastFull] ?: prefs[keys.lastSuccess])?.let(Instant::ofEpochMilli),
                    lastGameCount = prefs[keys.lastCount] ?: 0,
                    lastError = prefs[keys.errorKind]?.let { kind ->
                        SourceError(runCatching { SourceErrorKind.valueOf(kind) }.getOrDefault(SourceErrorKind.Unknown), prefs[keys.errorDetail])
                    },
                    lastErrorAt = prefs[keys.errorAt]?.let(Instant::ofEpochMilli),
                )
            }
        }

    suspend fun current(sourceId: String): SyncRecord? = records.first()[sourceId]

    /** [fullScan]: the run went through the whole catalogue (not an incremental run, not cut short). */
    suspend fun recordSuccess(sourceId: String, count: Int, fullScan: Boolean = true) {
        val keys = Keys(sourceId)
        store.edit {
            it[keys.lastSuccess] = clock.millis()
            if (fullScan) it[keys.lastFull] = clock.millis()
            it[keys.lastCount] = count
            it.remove(keys.errorKind)
            it.remove(keys.errorDetail)
            it.remove(keys.errorAt)
        }
    }

    suspend fun recordError(sourceId: String, error: SourceError) {
        val keys = Keys(sourceId)
        store.edit {
            it[keys.errorKind] = error.kind.name
            if (error.detail != null) it[keys.errorDetail] = error.detail.take(300) else it.remove(keys.errorDetail)
            it[keys.errorAt] = clock.millis()
        }
    }

    /** Forgets a removed (or replaced) source. */
    suspend fun remove(sourceId: String) {
        val keys = Keys(sourceId)
        store.edit { prefs -> keys.all.forEach { prefs.remove(it) } }
    }

    private class Keys(id: String) {
        val lastSuccess = longPreferencesKey("$PREFIX$id.last_success_at")
        val lastFull = longPreferencesKey("$PREFIX$id.last_full_scan_at")
        val lastCount = intPreferencesKey("$PREFIX$id.last_game_count")
        val errorKind = stringPreferencesKey("$PREFIX$id.error_kind")
        val errorDetail = stringPreferencesKey("$PREFIX$id.error_detail")
        val errorAt = longPreferencesKey("$PREFIX$id.error_at")
        val all = listOf(lastSuccess, lastFull, lastCount, errorKind, errorDetail, errorAt)
    }

    private companion object {
        // Source ids are lower-case letters, digits and dashes: no dot, so "<id>.<field>" splits safely.
        const val PREFIX = "src."
    }
}
