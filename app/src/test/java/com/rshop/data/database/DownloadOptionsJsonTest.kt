package com.rshop.data.database

import com.rshop.domain.model.DownloadOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOptionsJsonTest {

    @Test
    fun `update and extra flags survive storage`() {
        val options = listOf(
            DownloadOption("u1", fileName = "Game.nsp", sizeBytes = 10),
            DownloadOption("u2", label = "cover.jpg", fileName = "cover.jpg", isExtra = true),
            DownloadOption("u3", label = "UPDATE", fileName = "Game v1.1.nsp", isUpdate = true),
        )
        val read = DownloadOptionsJson.decode(DownloadOptionsJson.encode(options))
        assertEquals(options, read)
        assertTrue(read[2].isUpdate && !read[2].isExtra)
        assertFalse(read[0].isUpdate || read[0].isExtra)
    }

    @Test
    fun `options stored before the flags existed still load`() {
        val old = """[{"url":"u1","label":"ZIP","viaPage":false}]"""
        assertEquals(listOf(DownloadOption("u1", label = "ZIP")), DownloadOptionsJson.decode(old))
    }
}
