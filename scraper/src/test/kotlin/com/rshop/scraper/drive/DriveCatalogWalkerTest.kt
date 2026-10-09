package com.rshop.scraper.drive

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveCatalogWalkerTest {

    private fun walker(tree: TreeBuilder, maxDepth: Int = 6) = DriveCatalogWalker({ folders -> tree.childrenOf(folders.map { it.id }) }, maxDepth)

    private fun TreeBuilder.byId(id: String) = files.first { it.id == id }

    private suspend fun DriveCatalogWalker.games(folder: DriveFile, platform: String?): List<DriveGame> {
        val all = mutableListOf<DriveGame>()
        scanGames(folder, platform) { all += it }
        return all
    }

    @Test
    fun `console folders are found below container folders`() = runTest {
        val (t, root) = tree {
            folder("Roms", it) { roms ->
                folder("Game Boy Advance", roms)
                folder("PlayStation 2", roms)
                folder("Super Nintendo ROMs (120)", roms)
            }
            folder("Docs", it)
        }
        val consoles = walker(t).discoverConsoles(t.byId(root))
        assertEquals(listOf("Game Boy Advance", "PlayStation 2", "Super Nintendo ROMs (120)"), consoles.map { it.name }.sorted())
    }

    @Test
    fun `a Drive of game folders has no console`() = runTest {
        val (t, root) = tree {
            folder("Wii Sports", it) { g -> file("wii-sports.rvz", g) }
            folder("Advance Wars", it) { g -> file("aw.gba", g) }
        }
        assertTrue(walker(t).discoverConsoles(t.byId(root)).isEmpty())
    }

    @Test
    fun `the shared folder itself may be the console`() = runTest {
        val (t, root) = tree("GBA") { file("Advance Wars.zip", it) }
        assertEquals(listOf("GBA"), walker(t).discoverConsoles(t.byId(root)).map { it.name })
    }

    @Test
    fun `files, game folders, discs and letter folders become games`() = runTest {
        val (t, root) = tree("PS1") {
            file("Crash Bandicoot.zip", it)
            file("Final Fantasy VII (Disc 1).chd", it)
            file("Final Fantasy VII (Disc 2).chd", it)
            file("readme.txt", it)
            doc("Notes", it)
            folder("Gran Turismo 2", it) { g ->
                file("GT2.cue", g)
                file("GT2 (Track 01).bin", g)
                file("cover.jpg", g)
            }
            folder("A-C", it) { l ->
                file("Ape Escape.chd", l)
                folder("Castlevania SOTN", l) { g -> file("sotn.chd", g) }
            }
            folder("M", it) { l -> file("Medievil.chd", l) }
        }
        val games = walker(t).games(t.byId(root), "PS1")
        assertEquals(
            listOf("Ape Escape", "Castlevania SOTN", "Crash Bandicoot", "Final Fantasy VII", "Gran Turismo 2", "Medievil"),
            games.map { it.title }.sorted(),
        )
        val ff7 = games.first { it.title == "Final Fantasy VII" }
        assertEquals(2, ff7.files.size)
        assertEquals(200L, ff7.sizeBytes)
        assertEquals(2, games.first { it.title == "Gran Turismo 2" }.files.size)
        assertTrue(games.all { it.platform == "PS1" })
    }

    @Test
    fun `a game folder offers every file, its Update sub-folder apart`() = runTest {
        val (t, root) = tree("Switch") {
            folder("Zelda", it) { g ->
                file("Zelda.nsp", g)
                file("manual.pdf", g)
                file("cover.jpg", g)
                file("setup.exe", g)
                folder("Update", g) { u ->
                    file("Zelda v1.1.nsp", u)
                    file("notes.txt", u)
                }
            }
            folder("Mario", it) { g -> file("mario.xyz", g) }
        }
        val games = walker(t).games(t.byId(root), "Switch")
        assertEquals(listOf("Mario", "Zelda"), games.map { it.title }.sorted())
        val zelda = games.first { it.title == "Zelda" }
        assertEquals(listOf("Zelda.nsp"), zelda.files.map { it.name })
        assertEquals(listOf("cover.jpg", "manual.pdf"), zelda.others.map { it.name })
        assertEquals(listOf("Zelda v1.1.nsp", "notes.txt"), zelda.updates.map { it.name })
        // A format nobody listed still makes a game.
        assertEquals(1, games.first { it.title == "Mario" }.files.size)
    }

    @Test
    fun `a folder of several games is not one game`() = runTest {
        val (t, root) = tree("NES") {
            folder("Homebrew", it) { h ->
                file("Alter Ego.nes", h)
                file("Blade Buster.nes", h)
            }
        }
        assertEquals(listOf("Alter Ego", "Blade Buster"), walker(t).games(t.byId(root), "NES").map { it.title })
    }

    @Test
    fun `depth is limited`() = runTest {
        val (t, root) = tree("NES") {
            folder("a1", it) { a -> folder("a2", a) { b -> folder("a3", b) { c -> file("Deep.nes", c) } } }
        }
        assertTrue(walker(t, maxDepth = 2).games(t.byId(root), "NES").isEmpty())
        assertEquals(1, walker(t, maxDepth = 3).games(t.byId(root), "NES").size)
    }

    @Test
    fun `titles and groups are read from file names`() {
        assertEquals("Advance Wars", GameFiles.titleOf("Advance Wars.zip"))
        assertEquals("Neon Drift", GameFiles.titleOf("Neon_Drift.tar.gz"))
        assertEquals("Super Mario Bros. 3", GameFiles.titleOf("Super Mario Bros. 3"))
        assertEquals(GameFiles.groupKey("Game (Disc 1).cue"), GameFiles.groupKey("Game (Disc 2).bin"))
        assertEquals(GameFiles.groupKey("Game.cue"), GameFiles.groupKey("Game (Track 02).bin"))
        assertTrue(GameFiles.isUpdateFolderName("Update"))
        assertTrue(GameFiles.isUpdateFolderName("Mises à jour"))
        assertTrue(!GameFiles.isUpdateFolderName("Updated Remix"))
        assertTrue(GameFiles.isGameFile("game.pkg") && !GameFiles.isGameFile("cover.png") && !GameFiles.isOffered("run.exe"))
        assertTrue(GameFiles.isGroupingName("A-C"))
        assertTrue(GameFiles.isGroupingName("0-9"))
        assertTrue(!GameFiles.isGroupingName("Gran Turismo 2"))
    }
}
