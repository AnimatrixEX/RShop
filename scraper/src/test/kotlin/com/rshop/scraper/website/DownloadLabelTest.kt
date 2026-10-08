package com.rshop.scraper.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadLabelTest {

    @Test
    fun `verb and title are dropped, the format is added`() {
        assertEquals("ZIP, 12 MB", DownloadLabel.of("Download (ZIP, 12 MB)", "game.zip", "Super Game"))
        assertEquals("ZIP", DownloadLabel.of("Download Super Game", "super-game.zip", "Super Game"))
        assertEquals("Disc 2 · CHD", DownloadLabel.of("Disc 2", "game-d2.chd", "Game"))
    }

    @Test
    fun `an empty link falls back to the cleaned file name`() {
        assertEquals("(USA) (Rev 1) · ZIP", DownloadLabel.of("Download", "Super_Mario_World_%28USA%29_%28Rev_1%29.zip", "Super Mario World"))
    }

    @Test
    fun `compound extensions are formats`() {
        assertEquals("TAR.GZ", DownloadLabel.formatOf("game.tar.gz"))
        assertEquals("ISO", DownloadLabel.formatOf("Game.NKit.iso"))
        assertNull(DownloadLabel.formatOf("file.123"))
        assertNull(DownloadLabel.formatOf(null))
    }

    @Test
    fun `long text is shortened`() {
        val label = DownloadLabel.of("x".repeat(200), null, "Game")!!
        assertEquals(90, label.length)
    }

    @Test
    fun `nothing useful gives no label`() {
        assertNull(DownloadLabel.of("Download here", null, "Game"))
    }
}
