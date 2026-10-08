package com.rshop.ui.util

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.rshop.domain.model.Game
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun formatSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** Download counters, compact like stores show them: "87", "12,3 k", "1,5 M". */
@Composable
fun formatCount(count: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    return when {
        count < 1_000 -> format.format(count)
        count < 1_000_000 -> androidx.compose.ui.res.stringResource(com.rshop.R.string.count_thousands, format.format(count / 1_000.0))
        else -> androidx.compose.ui.res.stringResource(com.rshop.R.string.count_millions, format.format(count / 1_000_000.0))
    }
}

@Composable
fun formatDate(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(instant)
}

/** "SNES · Course · 3 Mo" — skips whatever the source did not provide. */
@Composable
fun rememberGameMetaLine(game: Game): String {
    val size = game.sizeBytes?.let { formatSize(it) }
    return listOfNotNull(game.platform, gameGenreText(game, max = 2), size).joinToString(" · ")
}
