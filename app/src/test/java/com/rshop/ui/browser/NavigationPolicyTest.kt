package com.rshop.ui.browser

import com.rshop.ui.browser.NavigationPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {

    private val page = "https://www.example.org/game/42"

    @Test
    fun `a new tab for the clicked link or the same site opens`() {
        assertEquals(Decision.Allow, NavigationPolicy.popup("https://mirror.files.net/get/42", page, "https://mirror.files.net/get/42"))
        assertEquals(Decision.Allow, NavigationPolicy.popup("https://dl.example.org/42", page, null))
    }

    @Test
    fun `a pop-up towards another site than the clicked link is held back`() {
        assertEquals(Decision.Ask, NavigationPolicy.popup("https://win-a-prize.xyz/", page, "https://www.example.org/download/42"))
        assertEquals(Decision.Block, NavigationPolicy.popup("https://c.popads.net/x", page, null))
        assertEquals(Decision.Block, NavigationPolicy.popup("intent://scan#Intent;end", page, null))
    }

    @Test
    fun `the page cannot be swapped for another site behind the user's back`() {
        assertEquals(Decision.Allow, NavigationPolicy.navigation("https://example.org/download/42", page, null, isRedirect = false))
        assertEquals(Decision.Ask, NavigationPolicy.navigation("https://ads.badsite.io/landing", page, "https://www.example.org/download/42", isRedirect = false))
        // Clicked link to a file host, and server redirects to it, go through.
        assertEquals(Decision.Allow, NavigationPolicy.navigation("https://files.host.net/a.zip", page, "https://files.host.net/a.zip", isRedirect = false))
        assertEquals(Decision.Allow, NavigationPolicy.navigation("https://files.host.net/a.zip", page, null, isRedirect = true))
        assertEquals(Decision.Block, NavigationPolicy.navigation("market://details?id=x", page, null, isRedirect = false))
    }

    @Test
    fun `sites are compared by registrable domain`() {
        assertTrue(NavigationPolicy.sameSite("https://a.example.co.uk/x", "https://www.example.co.uk/"))
        assertFalse(NavigationPolicy.sameSite("https://example.co.uk/", "https://other.co.uk/"))
        assertTrue(NavigationPolicy.sameSite("http://localhost:8099/a", "http://localhost:8099/b"))
    }
}
