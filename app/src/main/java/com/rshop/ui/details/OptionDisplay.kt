package com.rshop.ui.details

import com.rshop.domain.model.DownloadOption
import com.rshop.installation.FileFormats
import java.net.URLDecoder

/** What the file chooser shows for one file of a game page. */
data class OptionDisplay(
    /** Readable name, or null when nothing but the format tells it apart. */
    val name: String?,
    /** "ZIP", "CHD", "TAR.GZ"… */
    val format: String?,
    val sizeBytes: Long?,
)

object OptionDisplayFactory {
    private val PERCENT = Regex("%[0-9a-fA-F]{2}")

    fun of(option: DownloadOption): OptionDisplay {
        val fromFile = option.fileName?.let(FileFormats::of)
        // Labels built by the scraper end with the format: "Disc 2 · CHD".
        val parts = option.label?.split(" · ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val labelFormat = parts.lastOrNull()?.takeIf { it.length in 2..8 && it == it.uppercase() && it.all { c -> c.isLetterOrDigit() || c == '.' } }
        val format = fromFile ?: labelFormat
        var name = (if (labelFormat != null && labelFormat == format) parts.dropLast(1) else parts).joinToString(" · ")
        if (name.isBlank() || name.equals(format, ignoreCase = true)) name = option.fileName?.let(::readable).orEmpty()
        return OptionDisplay(name.takeIf { it.isNotBlank() }, format, option.sizeBytes)
    }

    /** "Super_Mario_World_%28USA%29.zip" → "Super Mario World (USA)". */
    internal fun readable(fileName: String): String {
        var name = fileName
        if (PERCENT.containsMatchIn(name)) name = runCatching { URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrDefault(name)
        val extension = name.substringAfterLast('.', "")
        if (extension.length in 1..5 && extension.all(Char::isLetterOrDigit) && !extension.all(Char::isDigit)) name = name.dropLast(extension.length + 1)
        name = name.removeSuffix(".tar")
        name = name.replace('_', ' ')
        if (' ' !in name) name = name.replace('-', ' ')
        return name.replace(Regex("\\s+"), " ").trim(' ', '-', '.')
    }
}
