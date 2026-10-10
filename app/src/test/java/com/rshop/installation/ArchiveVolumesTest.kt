package com.rshop.installation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveVolumesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `split RAR volumes are recognised and grouped by archive`() {
        val one = ArchiveVolumes.of("BATMN-ARKHMCITY-(USA)-NSwTcH-NSP-Ziperto.part1.rar")!!
        val two = ArchiveVolumes.of("BATMN-ARKHMCITY-(USA)-NSwTcH-NSP-Ziperto.PART2.RAR")!!
        assertEquals(one.set, two.set)
        assertEquals(1, one.index)
        assertEquals(2, two.index)
        assertNull(ArchiveVolumes.of("Game.rar"))
        assertNull(ArchiveVolumes.of("Game.part1.zip"))
    }

    @Test
    fun `a volume waits while another of its archive is still to download`() {
        val planned = listOf("Game.part2.rar", "Other.part1.rar", "Game [UPD].nsp")
        assertTrue(ArchiveVolumes.waitsForMore("Game.part1.rar", planned))
        assertFalse(ArchiveVolumes.waitsForMore("Game.part2.rar", listOf("Other.part1.rar", "Game [UPD].nsp")))
        // A file that is not a volume never waits.
        assertFalse(ArchiveVolumes.waitsForMore("Game.rar", planned))
    }

    @Test
    fun `extraction starts from the first volume found beside the last one`() {
        val dir = tmp.newFolder()
        listOf("Game.part10.rar", "Game.part2.rar", "Game.part1.rar", "Other.part1.rar", "cover.jpg")
            .forEach { java.io.File(dir, it).writeText("x") }
        val last = java.io.File(dir, "Game.part10.rar")
        assertEquals(listOf("Game.part1.rar", "Game.part2.rar", "Game.part10.rar"), ArchiveVolumes.siblings(last).map { it.name })
        assertEquals("Game.part1.rar", ArchiveVolumes.first(last).name)
        val single = java.io.File(dir, "cover.jpg")
        assertEquals(listOf(single), ArchiveVolumes.siblings(single))
    }
}
