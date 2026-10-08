package com.rshop.installation

import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class ArchiveExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val limits = ExtractionLimits(maxTotalBytes = 10L * 1024 * 1024)

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val file = tmp.newFile(name)
        ZipArchiveOutputStream(file).use { out ->
            entries.forEach { (entryName, data) ->
                out.putArchiveEntry(ZipArchiveEntry(entryName))
                out.write(data)
                out.closeArchiveEntry()
            }
        }
        return file
    }

    private suspend fun extract(file: File, rawName: String = file.name, limits: ExtractionLimits = this.limits): Pair<File, ExtractionResult> {
        val out = tmp.newFolder()
        val result = ArchiveExtractor.extract(file, ArchiveExtractor.detect(file), rawName, DirectorySink(out), limits)
        return out to result
    }

    @Test
    fun `zip with folders is extracted`() = runTest {
        val file = zip("game.zip", "Game/game.bin" to "BIN".toByteArray(), "Game/game.cue" to "CUE".toByteArray())
        assertEquals(ArchiveFormat.Zip, ArchiveExtractor.detect(file))

        val (out, result) = extract(file)
        assertEquals("BIN", File(out, "Game/game.bin").readText())
        assertEquals(2, result.files)
        assertEquals(setOf("Game"), result.topLevelNames)
    }

    @Test
    fun `seven zip is extracted`() = runTest {
        val file = tmp.newFile("game.7z")
        SevenZOutputFile(file).use { out ->
            val entry = out.createArchiveEntry(tmp.newFile("x"), "rom.sfc")
            out.putArchiveEntry(entry)
            out.write("SNES".toByteArray())
            out.closeArchiveEntry()
        }
        assertEquals(ArchiveFormat.SevenZip, ArchiveExtractor.detect(file))
        val (out, _) = extract(file)
        assertEquals("SNES", File(out, "rom.sfc").readText())
    }

    @Test
    fun `tar gz is extracted`() = runTest {
        val file = tmp.newFile("game.tar.gz")
        TarArchiveOutputStream(GzipCompressorOutputStream(FileOutputStream(file))).use { out ->
            val data = "GBA".toByteArray()
            out.putArchiveEntry(TarArchiveEntry("dir/rom.gba").apply { size = data.size.toLong() })
            out.write(data)
            out.closeArchiveEntry()
        }
        assertEquals(ArchiveFormat.TarGz, ArchiveExtractor.detect(file))
        val (out, _) = extract(file)
        assertEquals("GBA", File(out, "dir/rom.gba").readText())
    }

    @Test
    fun `plain file is copied as is`() = runTest {
        val file = tmp.newFile("download.tmp").apply { writeText("NES ROM") }
        assertEquals(ArchiveFormat.Raw, ArchiveExtractor.detect(file))
        val (out, result) = extract(file, rawName = "Neon Drift.nes")
        assertEquals("NES ROM", File(out, "Neon Drift.nes").readText())
        assertEquals(setOf("Neon Drift.nes"), result.topLevelNames)
    }

    @Test
    fun `single gzip stream keeps the name without extension`() = runTest {
        val file = tmp.newFile("rom.gb.gz")
        GzipCompressorOutputStream(FileOutputStream(file)).use { it.write("GB".toByteArray()) }
        assertEquals(ArchiveFormat.Gzip, ArchiveExtractor.detect(file))
        val (out, _) = extract(file, rawName = "rom.gb.gz")
        assertEquals("GB", File(out, "rom.gb").readText())
    }

    @Test
    fun `path traversal entries are refused and nothing escapes`() = runTest {
        val file = zip("evil.zip", "ok.txt" to "ok".toByteArray(), "../../escaped.txt" to "x".toByteArray())
        val error = runCatching { extract(file) }.exceptionOrNull()
        assertTrue(error is InstallException.UnsafeEntry)
        assertFalse(File(tmp.root, "escaped.txt").exists())
        assertFalse(File(tmp.root.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `absolute and drive letter entries are refused`() = runTest {
        assertTrue(runCatching { extract(zip("a.zip", "/etc/passwd" to "x".toByteArray())) }.exceptionOrNull() is InstallException.UnsafeEntry)
        assertTrue(runCatching { extract(zip("b.zip", "C:\\Windows\\x" to "x".toByteArray())) }.exceptionOrNull() is InstallException.UnsafeEntry)
    }

    @Test
    fun `symlinks in tar are refused`() = runTest {
        val file = tmp.newFile("link.tar")
        TarArchiveOutputStream(FileOutputStream(file)).use { out ->
            out.putArchiveEntry(TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/etc/passwd" })
            out.closeArchiveEntry()
        }
        assertTrue(runCatching { extract(file) }.exceptionOrNull() is InstallException.UnsafeEntry)
    }

    @Test
    fun `decompression bomb hits the size limit`() = runTest {
        val file = zip("bomb.zip", "zeros.bin" to ByteArray(4 * 1024 * 1024))
        assertTrue(file.length() < 64 * 1024)
        val error = runCatching { extract(file, limits = ExtractionLimits(maxTotalBytes = 1024 * 1024)) }.exceptionOrNull()
        assertTrue(error is InstallException.TooLarge)
    }

    @Test
    fun `too many entries are refused`() = runTest {
        val entries = (1..20).map { "f$it.txt" to "x".toByteArray() }.toTypedArray()
        val error = runCatching { extract(zip("many.zip", *entries), limits = ExtractionLimits(1_000_000, maxEntries = 10)) }.exceptionOrNull()
        assertTrue(error is InstallException.TooManyEntries)
    }

    @Test
    fun `truncated archive is reported as corrupt`() = runTest {
        val good = zip("good.zip", "rom.bin" to ByteArray(200_000) { it.toByte() })
        val truncated = tmp.newFile("truncated.zip").apply { writeBytes(good.readBytes().copyOf(1000)) }
        val error = runCatching { extract(truncated) }.exceptionOrNull()
        assertTrue("got $error", error is InstallException)
    }

    @Test
    fun `entry names are sanitized for app-created folders`() {
        assertEquals("Neon Drift (USA)", SafeEntryPath.sanitizeName("Neon Drift: (USA)?").replace("  ", " "))
        assertEquals("_CON", SafeEntryPath.sanitizeName("CON"))
        assertEquals("Game", SafeEntryPath.sanitizeName("..."))
    }
}
