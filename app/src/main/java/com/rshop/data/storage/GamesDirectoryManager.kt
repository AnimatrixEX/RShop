package com.rshop.data.storage

import android.net.Uri
import androidx.core.net.toUri
import com.rshop.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GamesDirectoryState {
    data object NotSelected : GamesDirectoryState
    data class Available(val uri: Uri, val location: DirectoryLocation?) : GamesDirectoryState

    /** A folder was chosen but its grant is gone (revoked, SD card removed, app data restored…). */
    data object AccessLost : GamesDirectoryState
}

/** Single entry point for choosing and checking the games folder. */
@Singleton
class GamesDirectoryManager @Inject constructor(
    private val settings: SettingsRepository,
    private val access: GamesDirectoryAccess,
) {
    val state: Flow<GamesDirectoryState> = settings.settings
        .map { it.gamesDirectoryUri }
        .distinctUntilChanged()
        .map { stored ->
            val uri = stored?.toUri() ?: return@map GamesDirectoryState.NotSelected
            if (access.hasPermission(uri)) {
                GamesDirectoryState.Available(uri, access.describe(uri))
            } else {
                Timber.w("Persisted grant missing for games folder %s", uri)
                GamesDirectoryState.AccessLost
            }
        }

    suspend fun select(treeUri: Uri): Result<Unit> {
        try {
            access.takePermission(treeUri)
        } catch (e: SecurityException) {
            Timber.e(e, "Folder %s cannot be persisted", treeUri)
            return Result.failure(e)
        }
        val previous = settings.settings.first().gamesDirectoryUri?.toUri()
        if (previous != null && previous != treeUri) {
            access.releasePermission(previous)
        }
        settings.setGamesDirectoryUri(treeUri.toString())
        return Result.success(Unit)
    }
}
