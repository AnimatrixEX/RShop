package com.rshop.domain.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleTagsTest {

    @Test
    fun `demos betas and prototypes are extras`() {
        assertTrue(TitleTags.isExtra("Game (Demo)"))
        assertTrue(TitleTags.isExtra("Game (USA) (Beta 2)"))
        assertTrue(TitleTags.isExtra("Game (Proto)"))
        assertTrue(TitleTags.isExtra("Game [Sample]"))
        assertFalse(TitleTags.isExtra("Demolition Man (USA)"))
        assertFalse(TitleTags.isExtra("Betamax Blues"))
    }
}
