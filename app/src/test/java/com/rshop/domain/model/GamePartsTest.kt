package com.rshop.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePartsTest {

    private fun option(name: String, viaPage: Boolean = false) =
        DownloadOption(url = "https://files.example.com/dl/$name", fileName = name, viaPage = viaPage)

    @Test
    fun `discs of one game are parts`() {
        assertTrue(GameParts.looksLikeParts(listOf(option("Final Fantasy VII (USA) (Disc 1).chd"), option("Final Fantasy VII (USA) (Disc 2).chd"), option("Final Fantasy VII (USA) (Disc 3).chd"))))
        assertTrue(GameParts.looksLikeParts(listOf(option("Game_CD1.iso"), option("Game_CD2.iso"))))
    }

    @Test
    fun `a cue sheet and its image are parts`() {
        assertTrue(GameParts.looksLikeParts(listOf(option("Game.cue"), option("Game.bin"))))
        assertTrue(GameParts.looksLikeParts(listOf(option("Game.gdi"), option("track01.bin"), option("track03.raw"))))
    }

    @Test
    fun `formats of the same game are choices`() {
        assertFalse(GameParts.looksLikeParts(listOf(option("Game.zip"), option("Game.tar.gz"))))
        assertFalse(GameParts.looksLikeParts(listOf(option("Game (USA).chd"), option("Game (Europe).chd"))))
        assertFalse(GameParts.looksLikeParts(listOf(option("Game.7z"), option("Game.zip"), option("Game.rvz"))))
    }

    @Test
    fun `one file, or files behind a page, are never parts`() {
        assertFalse(GameParts.looksLikeParts(listOf(option("Game.zip"))))
        assertFalse(GameParts.looksLikeParts(listOf(option("Game (Disc 1).chd", viaPage = true), option("Game (Disc 2).chd"))))
    }

    @Test
    fun `percent-encoded names are read`() {
        assertTrue(GameParts.looksLikeParts(listOf(option("Game%20(Disc%201).chd"), option("Game%20(Disc%202).chd"))))
    }
}
