package com.rshop.installation

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Extraction into a plain directory (app-private staging, tests). Re-checks containment. */
class DirectorySink(private val root: File) : ExtractionSink {
    private val rootPath = root.canonicalFile.toPath()

    override fun directory(path: List<String>) {
        resolve(path).mkdirs()
    }

    override fun file(path: List<String>): OutputStream {
        val target = resolve(path)
        target.parentFile?.mkdirs()
        return FileOutputStream(target)
    }

    private fun resolve(path: List<String>): File {
        val target = File(root, path.joinToString(File.separator)).canonicalFile
        // Defense in depth: SafeEntryPath already rejected traversal.
        if (!target.toPath().startsWith(rootPath)) throw InstallException.UnsafeEntry(path.joinToString("/"))
        return target
    }
}
