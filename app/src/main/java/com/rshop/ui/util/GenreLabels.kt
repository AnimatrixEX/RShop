package com.rshop.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rshop.domain.genre.genreLabelRes
import com.rshop.domain.model.Game

/** Display name of a genre tag: known genres are translated, a site's own category is shown as is. */
@Composable
fun genreLabel(tag: String): String = genreLabelRes(tag)?.let { stringResource(it) } ?: tag

/** The game's genres for display ("Course · Arcade"), falling back to the site's raw genre text. */
@Composable
fun gameGenreText(game: Game, max: Int = Int.MAX_VALUE): String? {
    val labels = game.tags.take(max).map { genreLabel(it) }
    return if (labels.isNotEmpty()) labels.joinToString(" · ") else game.genre
}
