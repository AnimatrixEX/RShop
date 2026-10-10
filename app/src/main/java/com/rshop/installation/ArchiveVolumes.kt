package com.rshop.installation

import java.io.File

/**
 * Archives split into volumes: "Game.part1.rar", "Game.part2.rar"… The volumes of one archive are
 * downloaded as separate files and extracted together, from the first one, once the last is there.
 */
object ArchiveVolumes {

    /** One volume: [set] names its archive (the same for every volume), [index] its rank from 1. */
    data class Volume(val set: String, val index: Int)

    private val RAR_PART = Regex("(?i)^(.*)\\.part(\\d+)\\.rar$")

    fun of(fileName: String): Volume? =
        RAR_PART.find(fileName.trim())?.let { Volume(it.groupValues[1].lowercase(), it.groupValues[2].toInt()) }

    /** True when [fileName] is a volume and another volume of its archive is among [planned]. */
    fun waitsForMore(fileName: String, planned: List<String>): Boolean {
        val volume = of(fileName) ?: return false
        return planned.any { of(it)?.set == volume.set }
    }

    /** The volumes of [file]'s archive already in its folder, in order; just [file] when it is not one. */
    fun siblings(file: File): List<File> {
        val volume = of(file.name) ?: return listOf(file)
        return file.parentFile?.listFiles().orEmpty()
            .mapNotNull { other -> of(other.name)?.takeIf { it.set == volume.set }?.let { other to it.index } }
            .sortedBy { it.second }
            .map { it.first }
            .ifEmpty { listOf(file) }
    }

    /** Where the extraction of [file]'s archive starts: its first volume, or [file] itself. */
    fun first(file: File): File = siblings(file).first()
}
