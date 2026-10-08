package com.rshop.scraper.website

import com.rshop.scraper.analysis.SiteAnalyzer
import com.rshop.scraper.testing.FixtureSite
import com.rshop.scraper.testing.fixture
import com.rshop.scraper.testing.html
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression found on device: the analysed first game had a direct link, so games behind a
 * countdown page on the same site got no download at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MixedDownloadsTest {

    private val site = FixtureSite().apply {
        routes["/game/pixel-quest"] = {
            html(fixture("game-via-page.html").replace("/download/neon-drift", "/download/pixel-quest"))
        }
        routes["/download/pixel-quest"] = { html(fixture("download-page.html")) }
    }

    @After
    fun tearDown() = site.close()

    @Test
    fun `games behind a download page work when the analysed game had a direct link`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games")
        assertFalse(analysis.sampleDetails!!.downloads.single().viaPage)
        assertEquals(0, analysis.config.downloadPageDelaySeconds)

        val source = WebsiteSource(analysis.config, site.fetcher())
        val details = source.getGameDetails("/game/pixel-quest")
        val download = details.downloads.single()
        assertTrue(download.viaPage)

        // The countdown is read from the actual page, even though the config says 0.
        val before = currentTime
        assertEquals(site.baseUrl + "files/neon-drift.7z", source.resolveDownload(download.url).url)
        assertTrue(currentTime - before >= 6_000)
    }
}
