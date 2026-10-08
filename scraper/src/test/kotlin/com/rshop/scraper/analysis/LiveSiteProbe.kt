package com.rshop.scraper.analysis

import com.rshop.scraper.ScraperLog
import com.rshop.scraper.http.HtmlFetcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual check against a real, authorised site. Skipped unless RSHOP_LIVE_URL is set:
 * `RSHOP_LIVE_URL=https://example.org/ ./gradlew :scraper:test --tests '*LiveSiteProbe*' -i`
 */
class LiveSiteProbe {

    @Test
    fun analyzeLiveSite() {
        val url = System.getenv("RSHOP_LIVE_URL")
        assumeTrue(!url.isNullOrBlank())
        val log = object : ScraperLog {
            override fun debug(message: String) = println("DEBUG $message")
            override fun warn(message: String, error: Throwable?) = println("WARN $message ${error?.message.orEmpty()}")
        }
        val fetcher = HtmlFetcher(client = OkHttpClient(), userAgent = "RShop/0.1 (homebrew store)", productToken = "RShop")
        val analysis = runBlocking { SiteAnalyzer(fetcher, log).analyze(url!!) }
        println("CONSOLES ${analysis.consoles.size}: ${analysis.consoles.take(30)}")
        println("PAGINATION ${analysis.pagination}")
        analysis.sampleGames.take(5).forEach { println("GAME ${it.title} | ${it.platform} | ${it.detailsUrl}") }
        println("DETAILS ${analysis.sampleDetails?.game?.title} downloads=${analysis.sampleDetails?.downloads} error=${analysis.detailsError}")
        analysis.sampleDetails?.downloads?.firstOrNull()?.let { download ->
            val info = runBlocking { com.rshop.scraper.website.WebsiteSource(analysis.config, fetcher, log).resolveDownload(download.url) }
            println("RESOLVED $info")
        }
        println("CONFIG ${analysis.config.toJson()}")
    }
}
