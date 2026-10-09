package com.rshop.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the persistable Storage Access Framework grant on the user-chosen games folder.
 * The app never assumes direct file-path access to shared storage.
 */
@Singleton
open class GamesDirectoryAccess @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val resolver = context.contentResolver

    /** Persists read/write access so it survives reboots. Throws [SecurityException] if the grant is not persistable. */
    open fun takePermission(treeUri: Uri) {
        resolver.takePersistableUriPermission(treeUri, RW_FLAGS)
        Timber.i("Persisted access to games folder %s", treeUri)
    }

    open fun releasePermission(treeUri: Uri) {
        try {
            resolver.releasePersistableUriPermission(treeUri, RW_FLAGS)
        } catch (e: SecurityException) {
            Timber.w(e, "No grant to release for %s", treeUri)
        }
    }

    /** False when the user revoked access or the volume (e.g. an SD card) is gone. */
    open fun hasPermission(treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission && it.isWritePermission }

    fun describe(treeUri: Uri): DirectoryLocation? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        // External storage provider ids look like "primary:Roms/SNES" or "1234-ABCD:Roms".
        val volume = documentId.substringBefore(':', missingDelimiterValue = "")
        val path = documentId.substringAfter(':', missingDelimiterValue = documentId)
        return DirectoryLocation(volume = volume, isPrimary = volume == "primary", relativePath = path)
    }

    private companion object {
        const val RW_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

data class DirectoryLocation(
    val volume: String,
    val isPrimary: Boolean,
    val relativePath: String,
)
