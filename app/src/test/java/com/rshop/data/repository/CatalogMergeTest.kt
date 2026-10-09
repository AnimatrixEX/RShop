package com.rshop.data.repository

import com.rshop.data.database.entity.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogMergeTest {

    private fun game(title: String, cover: String? = null) = GameEntity(
        id = "src:1",
        sourceId = "src",
        title = title,
        description = null,
        coverUrl = cover,
        downloadUrl = null,
        version = null,
        sizeBytes = null,
        platform = "Switch",
        genre = null,
        sourceUrl = null,
        sha256 = null,
        addedAt = 1,
        updatedAt = 1,
        popularity = 0,
        lastSyncedAt = 1,
    )

    @Test
    fun `a game the source renamed is looked up outside again`() {
        val old = game("Zelda [0100ABCDEF012000][v0]", cover = "https://wrong/cover.png").copy(
            artworkCheckedAt = 5,
            descriptionSource = "wikipedia:en",
            description = "Another game's article",
            descriptionCheckedAt = 5,
            screenshotsCheckedAt = 5,
        )

        val merged = CatalogMerge.listing(old, game("Zelda"), now = 10)

        assertEquals("Zelda", merged.title)
        assertNull(merged.coverUrl)
        assertNull(merged.artworkCheckedAt)
        assertNull(merged.description)
        assertNull(merged.descriptionSource)
        assertNull(merged.descriptionCheckedAt)
        assertNull(merged.screenshotsCheckedAt)
    }

    @Test
    fun `a game with the same title keeps what was found for it`() {
        val old = game("Zelda", cover = "https://sgdb/cover.png").copy(artworkCheckedAt = 5, descriptionSource = "wikipedia:en", description = "Article")

        val merged = CatalogMerge.listing(old, game("Zelda"), now = 10)

        assertEquals("https://sgdb/cover.png", merged.coverUrl)
        assertEquals(5L, merged.artworkCheckedAt)
        assertEquals("Article", merged.description)
    }
}
