package com.rshop.scraper.http

import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeTest {
    @Test
    fun `empty and normal pages are not challenges`() {
        assertFalse(Challenge.isInterstitial(Jsoup.parse("")))
        assertFalse(Challenge.isInterstitial(Jsoup.parse("<title>Games</title><p>hi</p>")))
    }

    @Test
    fun `challenge pages and captcha widgets are recognised`() {
        assertTrue(Challenge.isInterstitial(Jsoup.parse("<title>Just a moment...</title>")))
        assertTrue(Challenge.isInterstitial(Jsoup.parse("<form id=challenge-form></form>")))
        assertTrue(Challenge.hasCaptcha(Jsoup.parse("<div class=g-recaptcha data-sitekey=x></div>")))
        assertFalse(Challenge.hasCaptcha(Jsoup.parse("<a href=/file.zip>Download</a>")))
    }
}
