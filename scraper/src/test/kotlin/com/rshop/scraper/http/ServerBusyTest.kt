package com.rshop.scraper.http

import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerBusyTest {

    private fun busy(html: String) = ServerBusy.isBusy(Jsoup.parse(html))

    @Test
    fun `busy notices are recognised`() {
        assertTrue(busy("<body><p>Server is busy, please try again later.</p></body>"))
        assertTrue(busy("<body><h1>Too many users</h1></body>"))
        assertTrue(busy("<body><p>Le serveur est occupé, réessayez plus tard.</p></body>"))
    }

    @Test
    fun `an ordinary page is not busy`() {
        assertFalse(busy("<body><a href='/x.zip'>Download</a></body>"))
        assertFalse(busy("<body>${"Lorem ipsum ".repeat(400)} server is busy</body>"))
    }
}
