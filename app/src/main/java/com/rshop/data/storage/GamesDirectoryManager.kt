package com.rshop.data.storage

import android.net.Uri
import android.provider.DocumentsContract
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

/** One of the games folders the user added. */
data class GamesFolder(
    val uri: Uri,
    val location: DirectoryLocation?,
    /** False when the grant is gone (revoked, SD card removed): the folder cannot be used until it is added again. */
    val available: Boolean,
    /** Where games go when the player does not choose. */
    val isDefault: Boolean,
) {
    val asState: GamesDirectoryState get() = if (available) GamesDirectoryState.Available(uri, location) else GamesDirectoryState.AccessLost
}

/**
 * Single entry point for choosing and checking the games folders. There can be several (internal
 * storage, an SD card…): installing asks which one a game goes to, [state] is the default one.
 */
@Singleton
class GamesDirectoryManager @Inject constructor(
    private val settings: SettingsRepository,
    private val access: GamesDirectoryAccess,
) {
    val folders: Flow<List<GamesFolder>> = settings.settings
        .map { s ->
            val default = s.gamesDirectoryUri
            s.gamesDirectoryUris.map { stored ->
                val uri = stored.toUri()
                val granted = access.hasPermission(uri)
                if (!granted) Timber.w("Persisted grant missing for games folder %s", uri)
                GamesFolder(uri, access.describe(uri), granted, isDefault = stored == default)
            }
        }
        .distinctUntilChanged()

    /** The default folder; if it is unusable, the first usable one. */
    val state: Flow<GamesDirectoryState> = folders.map(::stateOf)

    /** The folder chosen for a download if it still works, else the folder the game is already in, else the default. */
    suspend fun stateFor(preferred: String?, installedDocumentUris: String? = null): GamesDirectoryState {
        val list = folders.first()
        val chosen = preferred?.let { p -> list.firstOrNull { it.uri.toString() == p } }
            ?: installedDocumentUris?.let { owning(it, list) }
        return chosen?.asState ?: stateOf(list)
    }

    /** Usable folders, the default first. */
    suspend fun availableFolders(): List<GamesFolder> = folders.first().filter { it.available }.sortedByDescending { it.isDefault }

    /** The folder holding a stored document address (several are joined by newlines; the first is used). */
    suspend fun folderOf(documentUris: String): GamesFolder? = owning(documentUris, folders.first())

    /** Adds a folder (the first one added becomes the default). */
    suspend fun add(treeUri: Uri): Result<Unit> {
        try {
            access.takePermission(treeUri)
        } catch (e: SecurityException) {
            Timber.e(e, "Folder %s cannot be persisted", treeUri)
            return Result.failure(e)
        }
        val current = settings.settings.first()
        val stored = treeUri.toString()
        if (stored !in current.gamesDirectoryUris) {
            settings.setGamesDirectories(current.gamesDirectoryUris + stored, current.gamesDirectoryUri ?: stored)
        }
        return Result.success(Unit)
    }

    /** Adds a folder and makes it the default one. */
    suspend fun select(treeUri: Uri): Result<Unit> {
        add(treeUri).onFailure { return Result.failure(it) }
        setDefault(treeUri)
        return Result.success(Unit)
    }

    suspend fun setDefault(treeUri: Uri) {
        val current = settings.settings.first()
        val stored = treeUri.toString()
        if (stored in current.gamesDirectoryUris) settings.setGamesDirectories(current.gamesDirectoryUris, stored)
    }

    /**
     * Forgets a folder and gives its permission back. Games installed there stay in the library,
     * but RShop can no longer reach their files until the folder is added again.
     */
    suspend fun remove(treeUri: Uri) {
        val current = settings.settings.first()
        val stored = treeUri.toString()
        val rest = current.gamesDirectoryUris - stored
        settings.setGamesDirectories(rest, current.gamesDirectoryUri?.takeIf { it != stored })
        access.releasePermission(treeUri)
    }

    private fun stateOf(list: List<GamesFolder>): GamesDirectoryState {
        if (list.isEmpty()) return GamesDirectoryState.NotSelected
        val usable = list.firstOrNull { it.isDefault && it.available } ?: list.firstOrNull { it.available }
        return usable?.asState ?: GamesDirectoryState.AccessLost
    }

    private fun owning(documentUris: String, list: List<GamesFolder>): GamesFolder? {
        val first = documentUris.split('\n').firstOrNull { it.isNotBlank() } ?: return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(first.toUri()) }.getOrNull() ?: return null
        return list.firstOrNull { runCatching { DocumentsContract.getTreeDocumentId(it.uri) }.getOrNull() == treeId }
    }
}
