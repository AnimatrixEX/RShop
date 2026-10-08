package com.rshop.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {

    @Test
    fun `ad networks and their subdomains are blocked`() {
        assertTrue(AdBlocker.isBlocked("doubleclick.net"))
        assertTrue(AdBlocker.isBlocked("securepubads.g.doubleclick.net"))
        assertTrue(AdBlocker.isBlocked("c.popads.net"))
        assertTrue(AdBlocker.isBlocked("WWW.Google-Analytics.com."))
    }

    @Test
    fun `ordinary sites are not`() {
        assertFalse(AdBlocker.isBlocked("example.org"))
        assertFalse(AdBlocker.isBlocked("notdoubleclick.net"))
        assertFalse(AdBlocker.isBlocked("net"))
        assertFalse(AdBlocker.isBlocked(null))
        assertFalse(AdBlocker.isBlocked("localhost"))
    }
}
