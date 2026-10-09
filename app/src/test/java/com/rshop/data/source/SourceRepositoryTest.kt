package com.rshop.data.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rshop.data.artwork.SecretCipher
import com.rshop.scraper.config.DriveConfig
import com.rshop.scraper.config.ListRules
import com.rshop.scraper.drive.DriveSource
import org.junit.Assert.assertTrue
import com.rshop.scraper.config.ScraperConfig
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SourceRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.filesDir, "source")

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun kotlinx.coroutines.test.TestScope.repository() =
        SourceRepository(context, OkHttpClient(), backgroundScope, DriveSettings(context, SecretCipher(), backgroundScope))

    private fun config(id: String, name: String) = ScraperConfig(
        id = id,
        name = name,
        baseUrl = "https://$id.example/",
        listUrl = "https://$id.example/games?page={page}",
        list = ListRules(item = listOf("div.game")),
    )

    @Test
    fun `sources are kept side by side, sorted by name, and removed one by one`() = runTest {
        val repository = repository()

        repository.save(config("zeta", "Zeta Games"))
        repository.save(config("alpha", "Alpha Homebrew"))
        assertEquals(listOf("alpha", "zeta"), repository.all().map { it.id })

        repository.remove("alpha")
        assertEquals(listOf("zeta"), repository.all().map { it.id })
        assertNull(repository.get("alpha"))

        // Persisted: a new instance (next app start) reads the same list.
        assertEquals(listOf("zeta"), repository().all().map { it.id })
    }

    @Test
    fun `saving the same site again replaces it`() = runTest {
        val repository = repository()

        repository.save(config("alpha", "Alpha"))
        repository.save(config("alpha", "Alpha renamed"))

        assertEquals(listOf("Alpha renamed"), repository.all().map { it.name })
    }

    @Test
    fun `the single source of earlier versions is migrated`() = runTest {
        root.mkdirs()
        val legacy = File(root, "config.json")
        legacy.writeText(config("old-site", "Old site").toJson())

        val repository = repository()

        assertEquals(listOf("old-site"), repository.all().map { it.id })
        assertFalse(legacy.exists())
    }

    @Test
    fun `Drive sources are stored next to websites and read back as Drive sources`() = runTest {
        val repository = repository()
        val drive = DriveConfig(DriveConfig.idFor("1AbCdEfGhIjKlMnOp"), "Mes jeux", "1AbCdEfGhIjKlMnOp", platform = "Wii")
        repository.save(drive)
        repository.save(config("alpha", "Alpha"))

        val again = repository()
        assertEquals(drive, again.get(drive.id))
        assertTrue(again.createSource(again.get(drive.id)!!) is DriveSource)
        assertEquals("Mes jeux", again.all().map { it.name }.last())
    }
}
