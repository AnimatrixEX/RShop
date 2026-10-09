package com.rshop.data.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.rshop.data.preferences.DataStoreSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GamesDirectoryManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = DataStoreSettingsRepository(context)

    /** Grants kept in memory instead of the system's. */
    private class FakeAccess(context: Context) : GamesDirectoryAccess(context) {
        val granted = mutableSetOf<Uri>()
        override fun takePermission(treeUri: Uri) { granted += treeUri }
        override fun releasePermission(treeUri: Uri) { granted -= treeUri }
        override fun hasPermission(treeUri: Uri) = treeUri in granted
    }

    private val access = FakeAccess(context)
    private val manager = GamesDirectoryManager(settings, access)

    private fun tree(id: String): Uri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", id)

    private val internal = tree("primary:Roms")
    private val card = tree("1234-ABCD:Games")

    @Before
    fun clean() = runTest { settings.setGamesDirectories(emptyList(), null) }

    @Test
    fun `the first folder becomes the default and more can be added`() = runTest {
        assertEquals(GamesDirectoryState.NotSelected, manager.state.first())

        manager.add(internal)
        manager.add(card)
        manager.add(card) // adding twice does nothing

        val folders = manager.folders.first()
        assertEquals(listOf(internal, card), folders.map { it.uri })
        assertEquals(listOf(true, false), folders.map { it.isDefault })
        assertEquals(internal, (manager.state.first() as GamesDirectoryState.Available).uri)
    }

    @Test
    fun `the default can change and removing it hands over to another folder`() = runTest {
        manager.add(internal)
        manager.add(card)

        manager.setDefault(card)
        assertEquals(card, (manager.state.first() as GamesDirectoryState.Available).uri)

        manager.remove(card)
        assertEquals(listOf(internal), manager.folders.first().map { it.uri })
        assertEquals(internal, (manager.state.first() as GamesDirectoryState.Available).uri)
        assertTrue(card !in access.granted)

        manager.remove(internal)
        assertEquals(GamesDirectoryState.NotSelected, manager.state.first())
    }

    @Test
    fun `selecting a folder adds it and makes it the default`() = runTest {
        manager.add(internal)
        manager.select(card)
        assertEquals(listOf(internal, card), manager.folders.first().map { it.uri })
        assertEquals(card, (manager.state.first() as GamesDirectoryState.Available).uri)
    }

    @Test
    fun `a chosen folder wins, then the folder the game is in, then the default`() = runTest {
        manager.add(internal)
        manager.add(card)
        val inCard = DocumentsContract.buildDocumentUriUsingTree(card, "1234-ABCD:Games/GBA/Game.gba").toString()

        assertEquals(card, (manager.stateFor(card.toString()) as GamesDirectoryState.Available).uri)
        assertEquals(card, (manager.stateFor(null, inCard) as GamesDirectoryState.Available).uri)
        assertEquals(internal, (manager.stateFor(null, null) as GamesDirectoryState.Available).uri)
        assertEquals(card, manager.folderOf(inCard)?.uri)
        assertNull(manager.folderOf(DocumentsContract.buildDocumentUriUsingTree(tree("9999-FFFF:X"), "9999-FFFF:X/a").toString()))
    }

    @Test
    fun `a folder whose grant is gone is reported, not silently replaced`() = runTest {
        manager.add(internal)
        manager.add(card)
        access.granted -= card

        // Asked for explicitly: the player must know it cannot be used.
        assertEquals(GamesDirectoryState.AccessLost, manager.stateFor(card.toString()))
        // Not asked for: another usable folder is used.
        assertEquals(internal, (manager.stateFor(null) as GamesDirectoryState.Available).uri)
        assertEquals(listOf(internal), manager.availableFolders().map { it.uri })

        manager.setDefault(card)
        assertEquals(internal, (manager.state.first() as GamesDirectoryState.Available).uri)
        access.granted -= internal
        assertEquals(GamesDirectoryState.AccessLost, manager.state.first())
    }
}
