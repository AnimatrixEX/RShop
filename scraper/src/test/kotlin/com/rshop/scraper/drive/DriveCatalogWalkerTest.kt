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
    fun `update and DLC folders found without their game are named after the game folder`() = runTest {
        val (t, root) = tree("Switch") {
            // Only the update is shared, not the game.
            folder("Against the Storm", it) { g ->
                folder("Update v1.17 (v131072)", g) { u -> file("Against the Storm v1.17[010062F01F2CC800][131072][UPD].nsp", u) }
            }
            // The game and a DLC folder next to it: one game with its add-ons, not a game called "DLC".
            folder("Hades", it) { g ->
                file("Hades [01000D200AC0C000][v0].nsp", g)
                folder("DLC", g) { d -> file("Hades Soundtrack [01000D200AC0D001][v0].nsp", d) }
            }
            // A folder named by an id.
            folder("Celeste", it) { g ->
                folder("01002B30028F6000", g) { i -> file("Celeste.nsp", i) }
                file("cover.jpg", g)
            }
            folder("DLC Quest", it) { g -> file("DLC Quest.nsp", g) }
            // Folders of add-ons only, named any way: the title of their files.
            folder("Sorted", it) { s ->
                file("notes.txt", s)
                folder("DLC Supporters Pack", s) { d -> file("Worlds of Aria [DLC Supporters Pack] [010091201B797001][v0][US].nsp.rar", d) }
                folder("60 FPS Patch (MOD)", s) { d -> file("Persona5 Royal [60FPS MOD].rar", d) }
            }
            // "5 DLC" next to the game: its add-ons. A lone "34 DLC" folder: named by its file.
            folder("Harvest Moon The Winds of Anthos", it) { g ->
                file("Harvest Moon The Winds of Anthos [0100CA6011122000][v0].nsp", g)
                folder("5 DLC", g) { d -> file("Harvest Moon The Winds of Anthos [5DLC][US] NSP.rar", d) }
            }
            folder("Capcom", it) { c ->
                file("readme.txt", c)
                folder("34 DLC", c) { d -> file("Capcom Arcade 2nd Stadium [34DLC][US] NSP.rar", d) }
                folder("DLC Unlocker v2.7.1", c) { d -> file("Diablo III Eternal Collection [diablo iii heritage DLC].nsp", d) }
            }
            // Split archives of one release are one game.
            folder("Batman", it) { b ->
                file("BATMN-ARKHMCITY-(USA)-NSwTcH-NSP-Ziperto.part1.rar", b)
                file("BATMN-ARKHMCITY-(USA)-NSwTcH-NSP-Ziperto.part2.rar", b)
                file("BATMN-ARKHMCITY-(USA)-NSwTcH-NSP-Update102-Ziperto.part1.rar", b)
                file("BATMN-ARKHMCITY-(USA)-NSwTcH-NSP-Update102-Ziperto.part2.rar", b)
            }
        }
        val games = walker(t).games(t.byId(root), "Switch")
        assertEquals(
            listOf(
                "Against the Storm", "Batman", "Capcom Arcade 2nd Stadium", "Celeste", "DLC Quest", "Diablo III Eternal Collection", "Hades",
                "Harvest Moon The Winds of Anthos", "Persona5 Royal", "Worlds of Aria",
            ),
            games.map { it.title }.sorted(),
        )
        assertEquals(2, games.first { it.title == "Batman" }.files.size)
        assertEquals(2, games.first { it.title == "Batman" }.updates.size)
        assertEquals(1, games.first { it.title == "Harvest Moon The Winds of Anthos" }.updates.size)
        assertEquals(listOf("Hades Soundtrack [01000D200AC0D001][v0].nsp"), games.first { it.title == "Hades" }.updates.map { it.name })
        assertTrue(GameFiles.isUpdateFolderName("DLC (3)") && GameFiles.isUpdateFolderName("15DLC") && !GameFiles.isUpdateFolderName("Updated Remix"))
        assertTrue(GameFiles.isTitleless("01002B30028F6000") && GameFiles.isTitleless("v1.2") && !GameFiles.isTitleless("1942"))
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
