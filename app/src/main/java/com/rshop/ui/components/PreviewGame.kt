package com.rshop.ui.components

import com.rshop.domain.model.Game

/** Minimal [Game] for cover rendering of items that only know id, title, platform and cover. */
fun coverGame(id: String, title: String, platform: String?, coverUrl: String?) = Game(
    id = id,
    title = title,
    description = null,
    coverUrl = coverUrl,
    screenshots = emptyList(),
    downloadUrl = null,
    version = null,
    sizeBytes = null,
    platform = platform,
    genre = null,
    sourceUrl = null,
)
