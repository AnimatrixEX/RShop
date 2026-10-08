package com.rshop.installation

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.rshop.data.storage.DirectoryLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.FileNotFoundException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File operations inside the user's games folder through the Storage Access Framework
 * (DocumentsContract directly: much faster than DocumentFile for many small files).
 */
@Singleton
class SafGameStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver = context.contentResolver

    fun rootDocument(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    fun children(tree: Uri, parent: Uri): List<Pair<String, Uri>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(1) to DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
                }
            }
        } ?: throw InstallException.DirectoryLost()
    }

    fun findChild(tree: Uri, parent: Uri, name: String): Uri? =
        children(tree, parent).firstOrNull { it.first == name }?.second

    fun findOrCreateDirectory(tree: Uri, parent: Uri, name: String): Uri =
        findChild(tree, parent, name) ?: create(parent, Document.MIME_TYPE_DIR, name)

    /** Creates a file, replacing any same-name document (the provider would otherwise rename it). */
    fun createFile(tree: Uri, parent: Uri, name: String): Uri {
        findChild(tree, parent, name)?.let { delete(it) }
        return create(parent, "application/octet-stream", name)
    }

    fun openOutput(uri: Uri): OutputStream =
        BufferedOutputStream(resolver.openOutputStream(uri, "w") ?: throw InstallException.Storage("Cannot write $uri"), 256 * 1024)

    /** Deletes a file or a whole folder. Missing documents count as deleted. */
    fun delete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (e: FileNotFoundException) {
        true
    } catch (e: IllegalArgumentException) {
        Timber.w(e, "Cannot delete %s", uri)
        false
    } catch (e: SecurityException) {
        Timber.w(e, "Cannot delete %s", uri)
        false
    }

    fun exists(uri: Uri): Boolean = try {
        resolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    } catch (e: Exception) {
        false
    }

    fun rename(uri: Uri, name: String): Uri =
        DocumentsContract.renameDocument(resolver, uri, name) ?: throw InstallException.Storage("Cannot rename $uri")

    /** Moves inside the same volume; null when the provider cannot move (caller falls back). */
    fun move(document: Uri, fromParent: Uri, toParent: Uri): Uri? = try {
        DocumentsContract.moveDocument(resolver, document, fromParent, toParent)
    } catch (e: UnsupportedOperationException) {
        null
    } catch (e: IllegalStateException) {
        null
    }

    /** Name and whether it is a folder, null if the document does not exist. */
    fun info(uri: Uri): Pair<String, Boolean>? = try {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to (cursor.getString(1) == Document.MIME_TYPE_DIR) else null
        }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /** Free and total bytes of the volume holding the games folder, when they can be determined. */
    fun volumeSpace(location: DirectoryLocation?): DeviceSpace? {
        if (location == null) return null
        val directory = if (location.isPrimary) {
            Environment.getExternalStorageDirectory()
        } else {
            context.getSystemService(StorageManager::class.java).storageVolumes
                .firstOrNull { it.uuid.equals(location.volume, ignoreCase = true) }?.directory
        } ?: return null
        return DeviceSpace(freeBytes = directory.usableSpace, totalBytes = directory.totalSpace)
    }

    /** Free bytes on the volume holding the games folder, when it can be determined. */
    fun availableBytes(location: DirectoryLocation?): Long? {
        if (location == null) return null
        if (location.isPrimary) return Environment.getExternalStorageDirectory().usableSpace
        val manager = context.getSystemService(StorageManager::class.java)
        return manager.storageVolumes.firstOrNull { it.uuid.equals(location.volume, ignoreCase = true) }?.directory?.usableSpace
    }

    private fun create(parent: Uri, mime: String, name: String): Uri = try {
        DocumentsContract.createDocument(resolver, parent, mime, name)
    } catch (e: FileNotFoundException) {
        throw InstallException.DirectoryLost()
    } ?: throw InstallException.Storage("Cannot create $name")
}

/** Writes extracted entries into a SAF folder, creating sub-folders on demand. */
class SafSink(
    private val storage: SafGameStorage,
    private val tree: Uri,
    private val root: Uri,
) : ExtractionSink {
    private val directories = HashMap<List<String>, Uri>().apply { put(emptyList(), root) }

    override fun directory(path: List<String>) {
        resolveDirectory(path)
    }

    override fun file(path: List<String>): OutputStream {
        val parent = resolveDirectory(path.dropLast(1))
        return storage.openOutput(storage.createFile(tree, parent, path.last()))
    }

    private fun resolveDirectory(path: List<String>): Uri = directories.getOrPut(path) {
        storage.findOrCreateDirectory(tree, resolveDirectory(path.dropLast(1)), path.last())
    }
}

/** Free and total space of a storage volume. */
data class DeviceSpace(val freeBytes: Long, val totalBytes: Long)
