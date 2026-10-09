package com.rshop.data.metadata

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class WikipediaClientTest {

    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var englishPages = """
        [{"title":"Advance Wars","index":1,"description":"2001 turn-based Nintendo video game","extract":"Advance Wars is a turn-based strategy video game developed by Intelligent Systems and published by Nintendo for the Game Boy Advance. It was released in 2001."},
         {"title":"Lego Star Wars: The Video Game","index":2,"description":"2005 video game","extract":"Lego Star Wars: The Video Game is a 2005 action-adventure game developed by Travellers Tales."},
         {"title":"Wars (series)","index":3,"description":"Video game series","extract":"The Wars series is a series of military-themed turn-based strategy video games."}]
    """.trimIndent()
    private var frenchPages = "[]"

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val pages = if (request.url.encodedPath.startsWith("/fr/")) frenchPages else englishPages
                return MockResponse.Builder().setHeader("Content-Type", "application/json")
                    .body("""{"batchcomplete":true,"query":{"pages":$pages}}""").build()
            }
        }
        start()
    }

    @After
    fun tearDown() = server.close()

    private fun client() = WikipediaClient(OkHttpClient()).also {
        it.endpoint = { language -> server.url("/$language/w/api.php") }
        it.userAgent = "RShop-Test"
    }

    @Test
    fun `the article of the game is found and credited`() = runTest {
        val found = client().describe("Advance Wars (USA) [!].gba", listOf("en"))!!

        assertEquals("wikipedia:en", found.source)
        assertEquals("Advance Wars", found.articleTitle)
        assertTrue(found.text.startsWith("Advance Wars is a turn-based strategy video game"))
        val request = requests.single()
        assertEquals("RShop-Test", request.headers["User-Agent"])
        assertTrue(request.url.queryParameter("gsrsearch")!!.startsWith("Advance Wars"))
    }

    @Test
    fun `an article about something else is refused`() = runTest {
        englishPages = """[{"title":"Wars (series)","index":1,"description":"Video game series","extract":"The Wars series is a series of military-themed turn-based strategy video games."}]"""
        assertNull(client().describe("Advance Wars", listOf("en")))
        // Not a game at all.
        englishPages = """[{"title":"Advance Wars","index":1,"description":"2001 film","extract":"Advance Wars is a film about the army made in 2001 by a studio."}]"""
        assertNull(client().describe("Advance Wars", listOf("en")))
    }

    @Test
    fun `a longer article title that contains the name is accepted, a short name is not`() = runTest {
        englishPages = """[{"title":"Pokémon Red and Blue","index":1,"description":"1996 role-playing video game","extract":"Pokémon Red Version and Pokémon Blue Version are 1996 role-playing video games developed by Game Freak and published by Nintendo."}]"""
        assertEquals("Pokémon Red and Blue", client().describe("Pokemon Red", listOf("en"))?.articleTitle)
        englishPages = """[{"title":"Super Mario Bros.","index":1,"description":"1985 video game","extract":"Super Mario Bros. is a 1985 platform game developed and published by Nintendo for the Nintendo Entertainment System."}]"""
        assertNull(client().describe("Mario", listOf("en")))
    }

    @Test
    fun `the language of the player is tried first, then English`() = runTest {
        frenchPages = """[{"title":"Advance Wars","index":1,"description":"jeu vidéo de 2001","extract":"Advance Wars est un jeu vidéo de tactique au tour par tour appartenant à la série de jeux vidéo Wars, développé par Intelligent Systems."}]"""
        val french = client().describe("Advance Wars", listOf("fr", "en"))!!
        assertEquals("wikipedia:fr", french.source)

        frenchPages = "[]"
        requests.clear()
        val english = client().describe("Advance Wars", listOf("fr", "en"))!!
        assertEquals("wikipedia:en", english.source)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a long article is cut at the end of a sentence`() = runTest {
        val sentence = "Advance Wars is a turn-based strategy video game with a long story to tell. "
        englishPages = """[{"title":"Advance Wars","index":1,"description":"2001 video game","extract":"${sentence.repeat(20)}"}]"""
        val text = client().describe("Advance Wars", listOf("en"))!!.text
        assertTrue(text.length <= 700)
        assertTrue(text.endsWith("."))
    }
}
