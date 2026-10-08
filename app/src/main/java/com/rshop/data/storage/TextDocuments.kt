package com.rshop.data.storage

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Small text files the user picks through the system file picker (config import/export). */
@Singleton
class TextDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun read(uri: Uri, maxBytes: Int = 512 * 1024): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        stream.use { input ->
            val bytes = input.readNBytes(maxBytes + 1)
            if (bytes.size > maxBytes) throw IOException("File larger than $maxBytes bytes")
            bytes.toString(Charsets.UTF_8)
        }
    }

    suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
