package com.rshop.installation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * RAR and RAR5 extraction with the 7-Zip engine (7-Zip-JBinding, a native library). Entries stream
 * through the same [ArchiveExtractor.LimitedWriter] as every other format, so path validation, entry
 * and size limits apply unchanged. An archive split into volumes ("Game.part1.rar", "Game.part2.rar")
 * is read from its first volume, the engine opening the next ones beside it in the same folder; a
 * missing volume is reported as such. Password-protected archives are refused.
 */
internal object RarExtraction {

    /** Loads the native library once; false where it cannot run (an unsupported CPU, a unit test on the JVM). */
    private val ready: Boolean by lazy {
        try {
            System.loadLibrary("7-Zip-JBinding")
            SevenZip.initLoadedLibraries()
            true
        } catch (e: Throwable) {
            Timber.w(e, "The 7-Zip engine cannot be loaded")
            false
        }
    }

    suspend fun extract(file: File, writer: ArchiveExtractor.LimitedWriter) {
        if (!ready) throw InstallException.UnsupportedFormat("RAR")
        val context = currentCoroutineContext()
        var failure: Throwable? = null
        val volumes = Volumes(file.parentFile ?: File("."))
        try {
            // Format detected from the content: RAR 2 to 4 and RAR5.
            val first = volumes.getStream(file.name) ?: throw InstallException.MissingVolume(file.name)
            val archive = SevenZip.openInArchive(null, first, volumes)
            archive.use {
                val callback = Callback(it, writer) { context.isActive }
                try {
                    it.extract(null, false, callback)
                } catch (e: SevenZipException) {
                    failure = callback.failure ?: e
                }
                failure = failure ?: callback.failure
            }
        } catch (e: SevenZipException) {
            throw volumes.missing?.let { InstallException.MissingVolume(it) } ?: InstallException.Corrupt(e)
        } finally {
            volumes.close()
        }
        // A volume the engine asked for and did not find explains the failure better than its own error.
        volumes.missing?.let { if (failure != null && failure !is InstallException) failure = InstallException.MissingVolume(it) }
        context.ensureActive()
        when (val error = failure) {
            null -> Unit
            is InstallException -> throw error
            else -> throw InstallException.Corrupt(error)
        }
    }

    /**
     * Opens the volumes of a split archive as the engine asks for them, by name, in [folder]. It
     * also tells the engine the name of the volume being read, from which it derives the next one.
     */
    private class Volumes(private val folder: File) : IArchiveOpenVolumeCallback, IArchiveOpenCallback {
        private val opened = LinkedHashMap<String, RandomAccessFile>()
        private var current: String? = null

        /** The first volume the engine asked for and that is not there. */
        var missing: String? = null
            private set

        override fun getProperty(propID: PropID): Any? = if (propID == PropID.NAME) current else null

        override fun getStream(filename: String): IInStream? {
            // Only names inside the archive's own folder.
            val name = File(filename).name
            val file = File(folder, name)
            if (!file.isFile) {
                if (missing == null) missing = name
                return null
            }
            val raf = opened.getOrPut(name) { RandomAccessFile(file, "r") }
            raf.seek(0)
            current = name
            return RandomAccessFileInStream(raf)
        }

        override fun setTotal(files: Long?, bytes: Long?) = Unit

        override fun setCompleted(files: Long?, bytes: Long?) = Unit

        fun close() = opened.values.forEach { runCatching { it.close() } }
    }

    private class Callback(
        private val archive: IInArchive,
        private val writer: ArchiveExtractor.LimitedWriter,
        private val active: () -> Boolean,
    ) : IArchiveExtractCallback {
        /** What stopped the extraction, from our own checks inside the engine's callbacks. */
        var failure: Throwable? = null
            private set
        private var current: OutputStream? = null

        override fun getStream(index: Int, askMode: ExtractAskMode): ISequentialOutStream? {
            current = null
            if (askMode != ExtractAskMode.EXTRACT) return null
            if (!active()) abort(IOException("cancelled"))
            val encrypted = archive.getProperty(index, PropID.ENCRYPTED) as? Boolean == true
            if (encrypted) abort(InstallException.UnsupportedFormat("RAR (mot de passe)"))
            // Windows archives use backslashes: SafeEntryPath splits on '/'.
            val name = (archive.getProperty(index, PropID.PATH) as? String).orEmpty().replace('\\', '/')
            if (archive.getProperty(index, PropID.IS_FOLDER) as? Boolean == true) {
                guarded { writer.directory(name) }
                return null
            }
            val output = guarded { writer.open(name) }
                // An unsafe name: the entry is read and dropped.
                ?: return ISequentialOutStream { it.size }
            current = output
            return ISequentialOutStream { data ->
                guarded { output.write(data, 0, data.size) }
                data.size
            }
        }

        override fun prepareOperation(askMode: ExtractAskMode) = Unit

        override fun setOperationResult(result: ExtractOperationResult) {
            val output = current
            current = null
            guarded { output?.close() }
            if (result != ExtractOperationResult.OK) {
                abort(
                    when (result) {
                        ExtractOperationResult.WRONG_PASSWORD -> InstallException.UnsupportedFormat("RAR (mot de passe)")
                        else -> IOException("RAR entry failed: $result")
                    },
                )
            }
        }

        override fun setTotal(total: Long) = Unit

        override fun setCompleted(complete: Long) = Unit

        /** Runs our own code; what it throws is kept and the engine is told to stop. */
        private inline fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            abort(e)
        }

        private fun abort(error: Throwable): Nothing {
            failure = failure ?: error
            throw SevenZipException(error.message ?: "aborted")
        }
    }
}
