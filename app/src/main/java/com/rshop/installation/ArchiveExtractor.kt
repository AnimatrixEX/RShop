package com.rshop.installation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class ArchiveFormat { Zip, SevenZip, Tar, TarGz, TarXz, Gzip, Rar, Raw }

/**
 * Detects archive formats from their magic bytes (extensions lie) and extracts them through an
 * [ExtractionSink] with path validation and size limits. Adding a format = one more branch here.
 */
object ArchiveExtractor {

    fun detect(file: File): ArchiveFormat {
        val header = ByteArray(512)
        val read = FileInputStream(file).use { it.readNBytes(header, 0, header.size) }
        fun starts(vararg bytes: Int) = read >= bytes.size && bytes.indices.all { header[it] == bytes[it].toByte() }
        return when {
            starts(0x50, 0x4B, 0x03, 0x04) || starts(0x50, 0x4B, 0x05, 0x06) -> ArchiveFormat.Zip
            starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> ArchiveFormat.SevenZip
            starts(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> ArchiveFormat.Rar
            starts(0x1F, 0x8B) -> if (innerIsTar(file, gzip = true)) ArchiveFormat.TarGz else ArchiveFormat.Gzip
            starts(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> if (innerIsTar(file, gzip = false)) ArchiveFormat.TarXz else ArchiveFormat.Raw
            read >= 262 && String(header, 257, 5, Charsets.US_ASCII) == "ustar" -> ArchiveFormat.Tar
            else -> ArchiveFormat.Raw
        }
    }

    /**
     * Extracts [file] into [sink]. [rawName] names the output when the file is not an archive (or
     * a single gzip stream). [onProgress] receives bytes written so far.
     */
    suspend fun extract(
        file: File,
        format: ArchiveFormat,
        rawName: String,
        sink: ExtractionSink,
        limits: ExtractionLimits,
        onProgress: (Long) -> Unit = {},
    ): ExtractionResult {
        val writer = LimitedWriter(sink, limits, onProgress)
        try {
            when (format) {
                ArchiveFormat.Zip -> extractZip(file, writer)
                ArchiveFormat.SevenZip -> extractSevenZip(file, writer)
                ArchiveFormat.Tar -> extractTar(BufferedInputStream(FileInputStream(file)), writer)
                ArchiveFormat.TarGz -> extractTar(GzipCompressorInputStream(BufferedInputStream(FileInputStream(file))), writer)
                ArchiveFormat.TarXz -> extractTar(XZCompressorInputStream(BufferedInputStream(FileInputStream(file))), writer)
                ArchiveFormat.Gzip -> GzipCompressorInputStream(BufferedInputStream(FileInputStream(file))).use {
                    writer.write(rawName.removeSuffix(".gz").removeSuffix(".GZ"), it)
                }
                ArchiveFormat.Raw -> FileInputStream(file).use { writer.write(rawName, it) }
                // No maintained pure-Java RAR5 extractor: say so instead of copying an unusable file.
                ArchiveFormat.Rar -> throw InstallException.UnsupportedFormat("RAR")
            }
        } catch (e: InstallException) {
            throw e
        } catch (e: IOException) {
            throw InstallException.Corrupt(e)
        } catch (e: IllegalArgumentException) {
            throw InstallException.Corrupt(e)
        }
        if (writer.files == 0) throw InstallException.Empty()
        return ExtractionResult(writer.bytes, writer.files, writer.topLevel, FileFormats.of(writer.fileNames))
    }

    private suspend fun extractZip(file: File, writer: LimitedWriter) {
        ZipFile.builder().setFile(file).get().use { zip ->
            for (entry in zip.entries) {
                currentCoroutineContext().ensureActive()
                when {
                    entry.isUnixSymlink -> throw InstallException.UnsafeEntry(entry.name)
                    entry.isDirectory -> writer.directory(entry.name)
                    else -> zip.getInputStream(entry).use { writer.write(entry.name, it) }
                }
            }
        }
    }

    private suspend fun extractSevenZip(file: File, writer: LimitedWriter) {
        SevenZFile.builder().setFile(file).get().use { archive ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = archive.nextEntry ?: break
                when {
                    entry.isAntiItem -> Unit
                    entry.isDirectory -> writer.directory(entry.name)
                    entry.hasStream() -> writer.write(entry.name, archive.getInputStream(entry))
                    else -> writer.write(entry.name, InputStream.nullInputStream())
                }
            }
        }
    }

    private suspend fun extractTar(stream: InputStream, writer: LimitedWriter) {
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                when {
                    entry.isSymbolicLink || entry.isLink -> throw InstallException.UnsafeEntry(entry.name)
                    entry.isDirectory -> writer.directory(entry.name)
                    entry.isFile -> writer.write(entry.name, tar)
                    // Devices, FIFOs…: never created.
                    else -> throw InstallException.UnsafeEntry(entry.name)
                }
            }
        }
    }

    private fun innerIsTar(file: File, gzip: Boolean): Boolean = try {
        val raw = BufferedInputStream(FileInputStream(file))
        val stream = if (gzip) GzipCompressorInputStream(raw) else XZCompressorInputStream(raw)
        stream.use {
            val header = it.readNBytes(262)
            header.size >= 262 && String(header, 257, 5, Charsets.US_ASCII) == "ustar"
        }
    } catch (_: IOException) {
        false
    }

    /** Counts entries and bytes across the whole archive and stops at the limits. */
    private class LimitedWriter(
        private val sink: ExtractionSink,
        private val limits: ExtractionLimits,
        private val onProgress: (Long) -> Unit,
    ) {
        var bytes = 0L
        var files = 0
        var entries = 0
        val topLevel = linkedSetOf<String>()
        val fileNames = ArrayList<String>()

        fun directory(name: String) {
            val path = SafeEntryPath.normalize(name) ?: return
            countEntry()
            topLevel += path.first()
            sink.directory(path)
        }

        fun write(name: String, input: InputStream) {
            val path = SafeEntryPath.normalize(name) ?: return
            countEntry()
            topLevel += path.first()
            sink.file(path).use { output -> copy(input, output) }
            files++
            if (fileNames.size < MAX_NAMES) fileNames += path.last()
        }

        private fun countEntry() {
            entries++
            if (entries > limits.maxEntries) throw InstallException.TooManyEntries(limits.maxEntries)
        }

        private fun copy(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(BUFFER_SIZE)
            var lastReport = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                bytes += read
                if (bytes > limits.maxTotalBytes) throw InstallException.TooLarge(limits.maxTotalBytes)
                output.write(buffer, 0, read)
                if (bytes - lastReport >= REPORT_EVERY) {
                    lastReport = bytes
                    onProgress(bytes)
                }
            }
            onProgress(bytes)
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
    private const val REPORT_EVERY = 1L shl 20
    private const val MAX_NAMES = 2_000
}
