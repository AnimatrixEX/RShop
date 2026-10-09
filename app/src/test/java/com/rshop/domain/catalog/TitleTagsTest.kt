package com.rshop.domain.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleTagsTest {

    @Test
    fun `regions are read from the parentheses of a title`() {
        assertEquals(TitleTags.USA, TitleTags.regionFlags("Sonic the Hedgehog (USA)"))
        assertEquals(TitleTags.USA or TitleTags.EUROPE, TitleTags.regionFlags("Sonic (USA, Europe)"))
        assertEquals(TitleTags.JAPAN, TitleTags.regionFlags("Final Fantasy (Japan) (Rev 1)"))
        assertEquals(TitleTags.WORLD, TitleTags.regionFlags("Tetris (World)"))
        assertEquals(TitleTags.OTHER, TitleTags.regionFlags("Game (Korea)"))
        assertEquals(TitleTags.EUROPE, TitleTags.regionFlags("Game (France) (En,Fr,De)"))
    }

    @Test
    fun `short codes count only on their own`() {
        assertEquals(TitleTags.USA, TitleTags.regionFlags("Game (U)"))
        assertEquals(TitleTags.USA or TitleTags.EUROPE, TitleTags.regionFlags("Game (UE)"))
        assertEquals(TitleTags.JAPAN, TitleTags.regionFlags("Game [J]"))
    }

    @Test
    fun `a title without region has none`() {
        assertEquals(0, TitleTags.regionFlags("Neon Drift"))
        assertEquals(0, TitleTags.regionFlags("Rush USA"))
        assertEquals(0, TitleTags.regionFlags("Game (En,Fr,De)"))
    }

    @Test
    fun `demos betas and prototypes are extras`() {
        assertTrue(TitleTags.isExtra("Game (Demo)"))
        assertTrue(TitleTags.isExtra("Game (USA) (Beta 2)"))
        assertTrue(TitleTags.isExtra("Game (Proto)"))
        assertTrue(TitleTags.isExtra("Game [Sample]"))
        assertFalse(TitleTags.isExtra("Demolition Man (USA)"))
        assertFalse(TitleTags.isExtra("Betamax Blues"))
    }

    @Test
    fun `a region filter keeps its region, world and untagged games`() {
        fun visible(title: String, region: CatalogRegion): Boolean {
            val flags = TitleTags.regionFlags(title)
            return region.mask == 0 || flags == 0 || (flags and region.mask) != 0
        }
        assertTrue(visible("Sonic (USA, Europe)", CatalogRegion.Europe))
        assertTrue(visible("Tetris (World)", CatalogRegion.Japan))
        assertTrue(visible("Neon Drift", CatalogRegion.Japan))
        assertFalse(visible("Final Fantasy (Japan)", CatalogRegion.Usa))
        assertFalse(visible("Game (Korea)", CatalogRegion.Usa))
    }
}
