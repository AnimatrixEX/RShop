package com.rshop.data.artwork

import com.rshop.data.database.entity.GameEntity
import com.rshop.data.repository.CatalogMerge
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

class SteamGridDbTest {

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var portraitGrids = """[{"id":1,"score":2,"url":"https://cdn2.steamgriddb.com/grid/low.png"},{"id":2,"score":9,"url":"https://cdn2.steamgriddb.com/grid/best.png"}]"""

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.headers["Authorization"] != "Bearer good-key") return json("""{"success":false}""", 401)
                val path = request.url.encodedPath
                return when {
                    path.startsWith("/api/v2/search/autocomplete/") -> json(
                        """{"success":true,"data":[{"id":10,"name":"Zelda II","verified":true},{"id":11,"name":"The Legend of Zelda","verified":true}]}""",
                    )
                    path == "/api/v2/grids/game/11" && request.url.queryParameter("dimensions") != null ->
                        json("""{"success":true,"page":0,"total":2,"limit":10,"data":$portraitGrids}""")
                    path == "/api/v2/grids/game/11" ->
                        json("""{"success":true,"data":[{"id":3,"score":1,"url":"https://cdn2.steamgriddb.com/grid/wide.png"}]}""")
                    else -> json("""{"success":false}""", 404)
                }
            }
        }
        server.start()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun client(key: String = "good-key") = SteamGridDbClient(OkHttpClient(), key, server.url("/api/v2/"))

    @After
    fun tearDown() = server.close()

    @Test
    fun `search then best voted portrait grid`() = runTest {
        val term = ArtworkTitle.searchTerm("Legend of Zelda, The (USA) [!].nes")
        assertEquals("The Legend of Zelda", term)
        val game = ArtworkTitle.pick(client().search(term), term)!!
        assertEquals(11L, game.id)
        assertEquals("https://cdn2.steamgriddb.com/grid/best.png", client().cover(game.id))
        val search = requests.first()
        assertEquals("/api/v2/search/autocomplete/The%20Legend%20of%20Zelda", search.url.encodedPath)
        assertEquals("600x900,342x482,660x930", requests.last().url.queryParameter("dimensions"))
    }

    @Test
    fun `falls back to any grid, none for an unknown game`() = runTest {
        portraitGrids = "[]"
        assertEquals("https://cdn2.steamgriddb.com/grid/wide.png", client().cover(11))
        assertNull(client().cover(99))
    }

    @Test(expected = ArtworkException.InvalidKey::class)
    fun `a rejected key is reported`() = runTest {
        client("bad").search("Zelda")
    }

    @Test
    fun `a merely similar name is not taken`() {
        val results = listOf(SgdbGame(1, "Mirror", verified = true), SgdbGame(2, "Mirror's Edge", verified = true))
        assertNull(ArtworkTitle.pick(results, "Mirror Maze"))
        assertEquals(3L, ArtworkTitle.pick(listOf(SgdbGame(3, "Pokemon Red Version", true)), "Pokemon Red Versio")?.id)
        assertEquals(4L, ArtworkTitle.pick(listOf(SgdbGame(4, "Sonic The Hedgehog", false)), "Sonic the Hedgehog")?.id)
    }

    @Test
    fun `titles are cleaned for the search`() {
        assertEquals("Final Fantasy VII", ArtworkTitle.searchTerm("Final Fantasy VII (Europe) (Disc 1)"))
        assertEquals("Super Mario World", ArtworkTitle.searchTerm("Super_Mario_World (USA).zip"))
        assertEquals("Dr. Mario", ArtworkTitle.searchTerm("Dr. Mario"))
    }

    @Test
    fun `syncs keep the SteamGridDB answer`() {
        val old = entity(cover = "https://cdn2.steamgriddb.com/grid/best.png", checkedAt = 5)
        val listing = CatalogMerge.listing(old, entity(cover = null, checkedAt = null), now = 10)
        assertEquals(old.coverUrl, listing.coverUrl)
        assertEquals(5L, listing.artworkCheckedAt)
        val details = CatalogMerge.details(old, entity(cover = null, checkedAt = null), now = 10)
        assertEquals(old.coverUrl, details.coverUrl)
        assertTrue(details.artworkCheckedAt == 5L)
    }

    private fun entity(cover: String?, checkedAt: Long?) = GameEntity(
        id = "src:/game/1", sourceId = "src", title = "Game", description = null, coverUrl = cover,
        downloadUrl = null, version = null, sizeBytes = null, platform = null, genre = null, sourceUrl = null,
        sha256 = null, addedAt = 1, updatedAt = 1, popularity = 0, lastSyncedAt = 1, artworkCheckedAt = checkedAt,
    )
}
