package com.rshop.ui.details

import com.rshop.domain.model.DownloadOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptionDisplayTest {

    private fun option(label: String? = null, fileName: String? = null, size: Long? = null) =
        DownloadOption(url = "https://x/y", label = label, fileName = fileName, sizeBytes = size)

    @Test
    fun `label and format are split`() {
        val d = OptionDisplayFactory.of(option(label = "Disc 2 · CHD", fileName = "game-d2.chd", size = 10))
        assertEquals("Disc 2", d.name)
        assertEquals("CHD", d.format)
        assertEquals(10L, d.sizeBytes)
    }

    @Test
    fun `an unreadable file name is cleaned up when the label says only the format`() {
        val d = OptionDisplayFactory.of(option(label = "ZIP", fileName = "Super_Mario_World_%28USA%29.zip"))
        assertEquals("Super Mario World (USA)", d.name)
        assertEquals("ZIP", d.format)
    }

    @Test
    fun `compound extensions are recognised`() {
        assertEquals("TAR.GZ", OptionDisplayFactory.of(option(fileName = "game.tar.gz")).format)
    }

    @Test
    fun `nothing known gives no name`() {
        val d = OptionDisplayFactory.of(option())
        assertNull(d.name)
        assertNull(d.format)
    }
}
