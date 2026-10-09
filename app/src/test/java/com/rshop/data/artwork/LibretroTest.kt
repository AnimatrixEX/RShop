package com.rshop.data.artwork

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rshop.testing.fixedClock
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class LibretroTest {

    private val requests = CopyOnWriteArrayList<String>()
    private val listing = """
        <html><body><table>
        <tr><td><a href="/Nintendo%20-%20Game%20Boy%20Advance/">Parent Directory</a></td></tr>
        <tr><td><a href="?C=N;O=D">Name</a></td></tr>
        <tr><td><a href="Advance%20Wars%20(Europe)%20(En,Fr,De,Es).png">x</a></td></tr>
        <tr><td><a href="Advance%20Wars%20(USA)%20(Rev%201).png">x</a></td></tr>
        <tr><td><a href="Advance%20Wars%20(USA)%20(Beta).png">x</a></td></tr>
        <tr><td><a href="Legend%20of%20Zelda,%20The%20-%20The%20Minish%20Cap%20(USA).png">x</a></td></tr>
        <tr><td><a href="Pokemon%20_%20Red.png">x</a></td></tr>
        </table></body></html>
    """.trimIndent()

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.url.encodedPath
                return when (request.url.encodedPath) {
                    "/Nintendo%20-%20Game%20Boy%20Advance/Named_Boxarts/" ->
                        MockResponse.Builder().setHeader("Content-Type", "text/html").body(listing).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        start()
    }

    @After
    fun tearDown() = server.close()

    private fun thumbnails(): LibretroThumbnails {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "libretro").deleteRecursively()
        return LibretroThumbnails(context, OkHttpClient(), fixedClock()).also { it.baseUrl = server.url("/") }
    }

    @Test
    fun `consoles are mapped from the names sites use`() {
        assertEquals("Nintendo - Super Nintendo Entertainment System", LibretroSystems.directoryFor("SNES"))
        assertEquals("Nintendo - Super Nintendo Entertainment System", LibretroSystems.directoryFor("Super Nintendo"))
        assertEquals("Nintendo - Game Boy Advance", LibretroSystems.directoryFor("Game Boy Advance"))
        assertEquals("Nintendo - Game Boy Color", LibretroSystems.directoryFor("GBC"))
        assertEquals("Nintendo - Game Boy", LibretroSystems.directoryFor("Game Boy"))
        assertEquals("Sega - Mega Drive - Genesis", LibretroSystems.directoryFor("Mega Drive"))
        assertEquals("Sega - Mega-CD - Sega CD", LibretroSystems.directoryFor("Sega CD"))
        assertEquals("Sony - PlayStation 2", LibretroSystems.directoryFor("PS2"))
        assertEquals("Sony - PlayStation", LibretroSystems.directoryFor("PlayStation"))
        assertEquals("Nintendo - Nintendo DS", LibretroSystems.directoryFor("Nintendo DS"))
        assertEquals("Nintendo - Nintendo 3DS", LibretroSystems.directoryFor("Nintendo 3DS"))
        assertEquals("NEC - PC Engine - TurboGrafx 16", LibretroSystems.directoryFor("TurboGrafx-16"))
        assertEquals("Nintendo - Nintendo Entertainment System", LibretroSystems.directoryFor("NES"))
        assertNull(LibretroSystems.directoryFor("Homebrew Hub"))
        assertNull(LibretroSystems.directoryFor(null))
    }

    @Test
    fun `the directory listing is read and decoded`() {
        val names = LibretroIndex.parseListing(listing)
        assertEquals(5, names.size)
        assertEquals("Legend of Zelda, The - The Minish Cap (USA)", names[3])
        assertEquals("Pokemon _ Red", names[4])
    }

    @Test
    fun `titles match whatever the punctuation, article or region tag`() {
        val index = LibretroIndex(LibretroIndex.parseListing(listing))
        assertEquals("Legend of Zelda, The - The Minish Cap (USA)", index.match("The Legend of Zelda: The Minish Cap"))
        assertEquals("Pokemon _ Red", index.match("Pokemon: Red"))
        assertNull(index.match("Advance Wars 2"))
        assertNull(index.match("Wars"))
    }

    @Test
    fun `the region named by the title wins, else the usual order, and betas come last`() {
        val index = LibretroIndex(LibretroIndex.parseListing(listing))
        assertEquals("Advance Wars (Europe) (En,Fr,De,Es)", index.match("Advance Wars (Europe)"))
        assertEquals("Advance Wars (USA) (Rev 1)", index.match("Advance Wars"))
    }

    @Test
    fun `a cover address is built from the console and the best file, with one request per console`() = runTest {
        val libretro = thumbnails()

        val first = libretro.find("GBA", "Legend of Zelda, The - The Minish Cap")
        assertEquals(
            server.url("/Nintendo%20-%20Game%20Boy%20Advance/Named_Boxarts/Legend%20of%20Zelda,%20The%20-%20The%20Minish%20Cap%20(USA).png").toString(),
            first,
        )
        libretro.find("Game Boy Advance", "Advance Wars")
        libretro.find("Game Boy Advance", "Unknown Game")
        // The list is fetched once for the console; the other lookups are local.
        assertEquals(1, requests.size)
        // A console Libretro does not know costs nothing.
        assertNull(libretro.find("Homebrew Hub", "Neon Drift"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `a console the server has no folder for gives no cover and is not asked twice`() = runTest {
        val libretro = thumbnails()
        assertNull(libretro.find("Atari 2600", "Pitfall"))
        assertNull(libretro.find("Atari 2600", "Pitfall II"))
        assertEquals(1, requests.size)
    }
}
