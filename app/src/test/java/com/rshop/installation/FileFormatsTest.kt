package com.rshop.installation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileFormatsTest {

    @Test
    fun `single file gives its extension`() {
        assertEquals("GBA", FileFormats.summary(listOf("Pokemon.gba")))
        assertEquals("TAR.GZ", FileFormats.of("backup.tar.gz"))
    }

    @Test
    fun `disc images list their parts, extras are ignored`() {
        assertEquals("BIN + CUE", FileFormats.summary(listOf("Game (Track 1).bin", "Game (Track 2).bin", "Game.cue", "readme.txt", "cover.jpg")))
    }

    @Test
    fun `most frequent format comes first and at most three are kept`() {
        val names = List(5) { "a$it.chd" } + listOf("x.iso", "y.cue", "z.bin", "w.gdi")
        assertEquals(listOf("CHD", "BIN", "CUE"), FileFormats.of(names))
    }

    @Test
    fun `names without a real extension tell nothing`() {
        assertNull(FileFormats.summary(listOf("README", "notes.txt", "Game v1.2")))
        assertNull(FileFormats.of("file.123"))
    }
}
