package com.rshop.scraper.remote

import com.rshop.scraper.ScraperException
import com.rshop.scraper.config.FtpConfig
import java.io.ByteArrayInputStream
import java.time.Instant

/** An in-memory server: folders and files by absolute path. */
class FakeRemote {
    private val folders = linkedMapOf("/" to mutableListOf<RemoteEntry>())
    private val contents = HashMap<String, ByteArray>()
    val listed = mutableListOf<String>()
    val opened = mutableListOf<Pair<String, Long>>()
    var sessions = 0
    var closed = 0

    /** Folders that refuse to be listed. */
    val denied = mutableSetOf<String>()

    fun folder(path: String) {
        if (path in folders) return
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        folder(parent)
        folders[path] = mutableListOf()
        add(parent, RemoteEntry(path.substringAfterLast('/'), path, true))
    }

    fun file(path: String, data: ByteArray = ByteArray(100)) {
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        folder(parent)
        contents[path] = data
        add(parent, RemoteEntry(path.substringAfterLast('/'), path, false, data.size.toLong(), Instant.parse("2026-01-02T03:04:05Z")))
    }

    private fun add(parent: String, entry: RemoteEntry) {
        if (parent != entry.path) folders.getValue(parent).add(entry)
    }

    fun connector(config: FtpConfig) = object : RemoteConnector(config, { null }) {
        override suspend fun connect(): RemoteSession {
            sessions++
            return Session()
        }
    }

    inner class Session : RemoteSession {
        override fun list(path: String): List<RemoteEntry> {
            listed += path
            if (path in denied) throw ScraperException.AccessDenied("ftp://fake$path", 550, "denied")
            return folders[path]?.toList() ?: throw ScraperException.Http("ftp://fake$path", 404)
        }

        override fun stat(path: String): RemoteEntry? {
            if (path == "/") return RemoteEntry("/", "/", true)
            val parent = path.substringBeforeLast('/').ifEmpty { "/" }
            return folders[parent]?.firstOrNull { it.path == path }
        }

        override fun open(path: String, offset: Long): RemoteFile {
            opened += path to offset
            val data = contents[path] ?: throw ScraperException.Http("ftp://fake$path", 404)
            return RemoteFile(ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt()), data.size.toLong(), null, offset) { closed++ }
        }

        override fun close() {
            closed++
        }
    }
}
