package com.rshop.installation

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/** Runs the real 7-Zip engine on the device: the JVM unit tests cannot load the native library. */
@RunWith(AndroidJUnit4::class)
class RarExtractionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val limits = ExtractionLimits(maxTotalBytes = 10L * 1024 * 1024)

    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

    private fun le(value: Long, size: Int) = ByteArray(size) { ((value shr (8 * it)) and 0xFF).toByte() }

    /** A RAR 4 archive whose files are stored without compression (method 0x30). */
    private fun rar4(vararg files: Pair<String, ByteArray>): File {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        // Archive header: type 0x73, no flags, 13 bytes.
        val main = byteArrayOf(0x73, 0, 0, 13, 0, 0, 0, 0, 0, 0, 0)
        out.write(le(crc(main) and 0xFFFF, 2)); out.write(main)
        for ((name, data) in files) {
            val nameBytes = name.toByteArray()
            val header = ByteArrayOutputStream()
            header.write(0x74)
            header.write(le(0x8000, 2)) // the packed size follows the common fields
            header.write(le(32L + nameBytes.size, 2))
            header.write(le(data.size.toLong(), 4)) // packed
            header.write(le(data.size.toLong(), 4)) // unpacked
            header.write(2) // host OS: Windows
            header.write(le(crc(data), 4))
            header.write(le(0x5A21_0000, 4)) // time
            header.write(20) // version needed
            header.write(0x30) // stored
            header.write(le(nameBytes.size.toLong(), 2))
            header.write(le(0x20, 4)) // attributes
            header.write(nameBytes)
            val bytes = header.toByteArray()
            out.write(le(crc(bytes) and 0xFFFF, 2)); out.write(bytes)
            out.write(data)
        }
        return tmp.newFile("test.rar").apply { writeBytes(out.toByteArray()) }
    }

    private fun extract(file: File): Pair<File, ExtractionResult> = runBlocking {
        val out = tmp.newFolder()
        val result = ArchiveExtractor.extract(file, ArchiveExtractor.detect(file), file.name, DirectorySink(out), limits)
        out to result
    }

    @Test
    fun a_rar_is_extracted_with_its_folders() {
        val rom = ByteArray(5_000) { (it % 251).toByte() }
        val file = rar4("Game\\game.gba" to rom, "Game\\readme.txt" to "hello".toByteArray())

        assertEquals(ArchiveFormat.Rar, ArchiveExtractor.detect(file))
        val (out, result) = extract(file)

        assertTrue(File(out, "Game/game.gba").readBytes().contentEquals(rom))
        assertEquals("hello", File(out, "Game/readme.txt").readText())
        assertEquals(setOf("Game"), result.topLevelNames)
        assertEquals(2, result.files)
    }

    @Test
    fun entries_that_try_to_leave_the_folder_are_refused_and_nothing_escapes() {
        val file = rar4("..\\..\\evil.txt" to "x".toByteArray(), "good.gba" to "ok".toByteArray())
        val out = tmp.newFolder()
        val error = runCatching {
            runBlocking { ArchiveExtractor.extract(file, ArchiveFormat.Rar, "t.rar", DirectorySink(out), limits) }
        }.exceptionOrNull()

        // Same rule as every other format: the whole archive is refused.
        assertTrue("got $error", error is InstallException.UnsafeEntry)
        assertFalse(File(out.parentFile, "evil.txt").exists())
        assertFalse(File(out, "evil.txt").exists())
    }

    @Test
    fun the_size_limit_stops_a_rar_too() {
        val file = rar4("big.bin" to ByteArray(4_000))
        val error = runCatching {
            runBlocking {
                ArchiveExtractor.extract(file, ArchiveFormat.Rar, "big.rar", DirectorySink(tmp.newFolder()), ExtractionLimits(maxTotalBytes = 1_000))
            }
        }.exceptionOrNull()
        assertTrue("got $error", error is InstallException.TooLarge)
    }

    /** Copies the split archive [name] (made with WinRAR: Game/game.nsp, 150 kB of noise, in 60 kB volumes) out of the test assets. */
    private fun volumes(name: String): File {
        val dir = tmp.newFolder()
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        assets.list("volumes").orEmpty().filter { it.startsWith("$name.") }.forEach { file ->
            assets.open("volumes/$file").use { input -> File(dir, file).outputStream().use { input.copyTo(it) } }
        }
        return dir
    }

    @Test
    fun a_split_rar5_is_extracted_from_its_first_volume() = splitArchive("Vol5")

    @Test
    fun a_split_rar4_is_extracted_from_its_first_volume() = splitArchive("Vol4")

    private fun splitArchive(name: String) {
        val dir = volumes(name)
        assertEquals(3, dir.listFiles()!!.size)
        val (out, result) = extract(File(dir, "$name.part1.rar"))
        val game = File(out, "Game/game.nsp")
        assertEquals(150_000L, game.length())
        assertEquals("588724fa6fa5d4366731d189f548555e", md5(game))
        assertEquals("readme", File(out, "Game/readme.txt").readText().trim())
        assertEquals(setOf("Game"), result.topLevelNames)
    }

    @Test
    fun a_split_rar_with_a_volume_missing_says_which() {
        val dir = volumes("Vol5")
        File(dir, "Vol5.part2.rar").delete()
        val error = runCatching { extract(File(dir, "Vol5.part1.rar")) }.exceptionOrNull()
        assertTrue("got $error", error is InstallException.MissingVolume)
        assertEquals("Vol5.part2.rar", (error as InstallException.MissingVolume).volume)
    }

    private fun md5(file: File): String =
        java.security.MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun a_damaged_rar_is_reported_as_corrupt_not_copied() {
        val file = rar4("a.gba" to ByteArray(2_000)).also { it.writeBytes(it.readBytes().copyOf(60)) }
        val error = runCatching { extract(file) }.exceptionOrNull()
        assertTrue("got $error", error is InstallException.Corrupt || error is InstallException.Empty)
    }
}
