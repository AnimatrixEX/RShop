package com.rshop.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.rshop.domain.model.Game

/** Asks for the page of a game to be read ahead; does nothing where it is not provided. */
val LocalDetailsPrefetch = staticCompositionLocalOf<(Game) -> Unit> { {} }

/** How long a card must keep the focus before its page is read. */
const val PREFETCH_DELAY_MS = 500L
