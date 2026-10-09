package com.rshop.data.database

import com.rshop.domain.model.DownloadOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stores [DownloadOption] lists in one TEXT column: they are only ever read with their game. */
internal object DownloadOptionsJson {
    @Serializable
    private data class Stored(
        val url: String,
        val label: String? = null,
        val fileName: String? = null,
        val sizeBytes: Long? = null,
        val sha256: String? = null,
        val viaPage: Boolean = false,
        val isUpdate: Boolean = false,
        val isExtra: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(options: List<DownloadOption>): String? = options.takeIf { it.isNotEmpty() }?.let { list ->
        json.encodeToString(list.map { Stored(it.url, it.label, it.fileName, it.sizeBytes, it.sha256, it.viaPage, it.isUpdate, it.isExtra) })
    }

    fun decode(text: String?): List<DownloadOption> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Stored>>(text) }.getOrDefault(emptyList())
            .map { DownloadOption(it.url, it.label, it.fileName, it.sizeBytes, it.sha256, it.viaPage, it.isUpdate, it.isExtra) }
    }
}
