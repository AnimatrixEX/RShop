package com.rshop.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rshop.R
import com.rshop.data.storage.GamesFolder

/** A games folder as the player recognises it: "Internal storage/Roms" or "1234-ABCD/Roms". */
@Composable
fun folderLabel(folder: GamesFolder): String {
    val location = folder.location ?: return folder.uri.toString()
    val root = if (location.isPrimary) stringResource(R.string.storage_internal) else location.volume
    return "$root/${location.relativePath}".trimEnd('/')
}
