package com.rshop.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rshop.R

@Composable
fun FavoriteButton(isFavorite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    ConsoleIconButton(
        icon = if (isFavorite) Icons.Filled.Check else Icons.Filled.Add,
        contentDescription = stringResource(if (isFavorite) R.string.action_unfavorite else R.string.action_favorite),
        onClick = onToggle,
        active = isFavorite,
        modifier = modifier,
    )
}
