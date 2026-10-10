package com.rshop.scraper.drive

import com.rshop.scraper.drive.DriveNaming.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DriveNamingTest {

    private fun parse(name: String) = DriveNaming.parse(name, isFile = true)

    @Test
    fun `Switch ids tell the game, its update and its DLC apart and group them`() {
        val base = parse("Super Mario Odyssey [0100000000010000][v0].nsp")
        val update = parse("Super Mario Odyssey [UPDATE][0100000000010800][v65536].nsp")
        val dlc = parse("Super Mario Odyssey [DLC][0100000000011001].nsp")

        assertEquals(Role.Base, base.role)
        assertEquals(Role.Update, update.role)
        assertEquals(Role.Dlc, dlc.role)
        assertEquals(setOf("Super Mario Odyssey"), setOf(base.title, update.title, dlc.title))
        assertEquals(1, setOf(base.groupKey, update.groupKey, dlc.groupKey).size)
        assertEquals("65536", update.version)
    }

    @Test
    fun `scene releases, split archives and DLC counts read as the game`() {
        val part1 = parse("BATMN-ARKHMCITY-(USA)-NSwTcH-NSP-Update102-Ziperto.part1.rar")
        val part2 = parse("BATMN-ARKHMCITY-(USA)-NSwTcH-NSP-Update102-Ziperto.part2.rar")
        val base = parse("BATMN-ARKHMCITY-(USA)-NSwTcH-NSP-Ziperto.part1.rar")
        assertEquals("BATMN-ARKHMCITY", part1.title)
        assertEquals(Role.Update, part1.role)
        assertEquals(Role.Base, base.role)
        assertEquals(1, setOf(part1.groupKey, part2.groupKey, base.groupKey).size)

        assertEquals("FFXX2-HDR", parse("FFXX2-HDR-(USA)-NSwTcH-NSP-(2DLCPack)-Ziperto.part1.rar").title)
        assertEquals(Role.Dlc, parse("DAVTDIVR-(USA)-NSwTcH-NSP-[4DLCPack]-Ziperto.rar").role)
        for ((name, title) in listOf(
            "Capcom Arcade 2nd Stadium [34DLC][US] NSP.rar" to "Capcom Arcade 2nd Stadium",
            "Capcom Arcade Stadium (NSP)(33 DLCs).rar" to "Capcom Arcade Stadium",
            "Hogwarts Legacy (NSP)(4 Updated DLCs).rar" to "Hogwarts Legacy",
            "Grip_18_DLC_NSP.rar" to "Grip",
            "Dragon Quest 7 Reimagined [DLC Jam Packed Swag Bag] [0100A9D01C447003][v0].nsp.nsp" to "Dragon Quest 7 Reimagined",
            "Against the Storm v1.17[010062F01F2CC800][131072][UPD].nsp" to "Against the Storm",
            "GRIP__0100459009A2A000__v0_NSP.rar" to "GRIP",
        )) {
            assertEquals(name, title, parse(name).title)
        }
        assertEquals("1 2 Switch", parse("1 2 Switch [01000320000CC000][v0].nsp").title)
    }

    @Test
    fun `an id alone decides, whatever the name says`() {
        // No tag in the name at all.
        assertEquals(Role.Update, parse("Zelda [0100ABCDEF012800][v131072].nsp").role)
        assertEquals(Role.Base, parse("Zelda [0100ABCDEF012000][v0].nsp").role)
    }

    @Test
    fun `updates and DLC named in words`() {
        val update = parse("Game Name Update 1.2.0.zip")
        assertEquals(Role.Update, update.role)
        assertEquals("Game Name", update.title)
        assertEquals("1.2.0", update.version)
        assertEquals(parse("Game Name.zip").groupKey, update.groupKey)

        assertEquals(Role.Update, parse("Game Name (Update v1.0.2).nsp").role)
        assertEquals(Role.Update, parse("Game Name + Update v1.0.2.nsp").role)
        val dlc = parse("Game Name DLC Pack.zip")
        assertEquals(Role.Dlc, dlc.role)
        assertEquals("Game Name", dlc.title)
        assertEquals(Role.Dlc, parse("Game Name [DLC].nsp").role)
        // A game whose title merely holds the word is still a game.
        assertEquals(Role.Base, parse("Pokemon Updated Edition.zip").role)
    }

    @Test
    fun `tags are not part of the title, the rest is`() {
        assertEquals("Sonic The Hedgehog", parse("Sonic The Hedgehog (USA) (En,Fr) (Rev 1).md").title)
        assertEquals("Pokemon Red", parse("Pokemon Red (USA, Europe) [!].gb").title)
        assertEquals("Final Fantasy VII", parse("Final Fantasy VII (Disc 1).chd").title)
        assertEquals("Metroid Fusion", parse("Metroid_Fusion_(Europe).gba").title)
        assertEquals("Super Mario Odyssey", parse("Super Mario Odyssey [0100000000010000][v0].nsp").title)
        // What the title itself holds in parentheses stays, and so does a demo mark (the demo filter reads it).
        assertEquals("Zelda (Link's Awakening)", parse("Zelda (Link's Awakening) [0100000000010000].nsp").title)
        assertEquals("Mega Man (Demo)", parse("Mega Man (Demo) (USA).nes").title)
        // Folders are cleaned too, without an extension to drop.
        assertEquals("Gran Turismo 4", DriveNaming.parse("Gran Turismo 4 [BLUS30001]", isFile = false).title)
        // Nothing left: keep the name rather than an empty title.
        assertEquals("[Untitled]", parse("[Untitled].zip").title)
    }

    @Test
    fun `regions and revisions of one game are one game with several files`() {
        assertEquals(parse("Sonic (USA).md").groupKey, parse("Sonic (Europe).md").groupKey)
        assertNotEquals(parse("Sonic (USA).md").groupKey, parse("Sonic 2 (USA).md").groupKey)
    }

    @Test
    fun `add-on labels`() {
        assertEquals("DLC", DriveNaming.addOnLabel("Game [DLC][0100000000011001].nsp"))
        assertEquals("UPDATE v65536", DriveNaming.addOnLabel("Game [0100000000010800][v65536].nsp"))
        // A file out of an Update folder, named like the game itself.
        assertEquals("UPDATE", DriveNaming.addOnLabel("patch-final.zip"))
    }

    @Test
    fun `scene style names read as words`() {
        assertEquals("Super Mario Odyssey", parse("Super.Mario.Odyssey.nsp").title)
    }

    @Test
    fun `loose files become games with their updates and DLC attached`() = runTest {
        val (tree, root) = tree("Switch") { r ->
            file("Super Mario Odyssey [0100000000010000][v0].nsp", r, size = 100)
            file("Super Mario Odyssey [UPDATE][0100000000010800][v65536].nsp", r, size = 20)
            file("Super Mario Odyssey [DLC][0100000000011001].nsp", r, size = 5)
            file("Celeste [0100ABCDEF012000][v0].nsp", r, size = 50)
            file("Celeste (Update 1.1).nsp", r, size = 7)
            file("Orphan Game [UPDATE][0100AAAAAAAAA800].nsp", r, size = 3)
        }
        val walker = DriveCatalogWalker({ folders -> tree.childrenOf(folders.map { it.id }) }, maxDepth = 6)
        val games = mutableListOf<DriveGame>()
        walker.scanGames(tree.files.first { it.id == root }, "Switch") { games += it }

        assertEquals(listOf("Celeste", "Orphan Game", "Super Mario Odyssey"), games.map { it.title }.sorted())
        val odyssey = games.first { it.title == "Super Mario Odyssey" }
        assertEquals(1, odyssey.files.size)
        assertEquals(100L, odyssey.sizeBytes)
        assertEquals(2, odyssey.updates.size)
        // An update whose game is not in the Drive still shows up, as itself, instead of vanishing.
        assertEquals(1, games.first { it.title == "Orphan Game" }.files.size)
        assertEquals(1, games.first { it.title == "Celeste" }.updates.size)
    }

    @Test
    fun `a game folder keeps the updates and DLC it holds as add-ons`() = runTest {
        val (tree, root) = tree("Switch") { r ->
            folder("Celeste", r) { g ->
                file("Celeste [0100ABCDEF012000][v0].nsp", g)
                file("Celeste [UPDATE][0100ABCDEF012800][v1].nsp", g)
                file("Celeste [DLC][0100ABCDEF013001].nsp", g)
                file("cover.jpg", g)
            }
        }
        val walker = DriveCatalogWalker({ folders -> tree.childrenOf(folders.map { it.id }) }, maxDepth = 6)
        val games = mutableListOf<DriveGame>()
        walker.scanGames(tree.files.first { it.id == root }, "Switch") { games += it }

        val game = games.single()
        assertEquals("Celeste", game.title)
        assertEquals(1, game.files.size)
        assertEquals(2, game.updates.size)
        assertEquals(listOf("cover.jpg"), game.others.map { it.name })
    }
}
