package com.rshop.ui.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rshop.R
import com.rshop.ui.components.FocusableSurface
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.folderLabel
import com.rshop.ui.util.formatSize

private val SegmentColors = listOf(
    Color(0xFF4F8CFF), Color(0xFFFF8A3D), Color(0xFF3DDC97), Color(0xFFB46CFF),
    Color(0xFFFF5C8A), Color(0xFFFFD23F), Color(0xFF3CC8E0),
)
private val OthersColor = Color(0xFF8A8F9C)
private const val MAX_SEGMENTS = 6

/** One slice of a bar: a console, or the consoles too small to list. */
private data class Slice(val label: String, val bytes: Long, val color: Color)

/** A console keeps its color in every folder; the ones past the sixth biggest are grouped. */
private fun slicesOf(folder: FolderUsage, platformOrder: List<String>): List<Slice> {
    val listed = folder.byPlatform.filter { platformOrder.indexOf(it.platform) in 0 until MAX_SEGMENTS }
    val rest = folder.byPlatform.filterNot { it in listed }.sumOf { it.bytes }
    return listed.map { Slice(it.platform, it.bytes, SegmentColors[platformOrder.indexOf(it.platform) % SegmentColors.size]) } +
        listOfNotNull(rest.takeIf { it > 0 }?.let { Slice("", it, OthersColor) })
}

/**
 * Where the games' space goes: for each games folder, a bar split per console and the volume around
 * it. With a single folder it is just that; with several, each folder gets its own block.
 * The header folds it ([collapsed]) to one line per folder: its bar and its free space.
 */
@Composable
fun StorageCard(usage: StorageUsage, collapsed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(RShopColors.Surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .animateContentSize(),
    ) {
        // The whole header folds and unfolds the card, by touch or with the controller.
        FocusableSurface(
            onClick = onToggle,
            shape = RoundedCornerShape(12.dp),
            focusedScale = 1.01f,
            glow = false,
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.library_storage_title), style = MaterialTheme.typography.titleMedium, color = RShopColors.TextPrimary)
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.library_storage_games, formatSize(usage.gamesBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RShopColors.TextSecondary,
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(if (collapsed) R.string.library_storage_expand else R.string.library_storage_collapse),
                    tint = RShopColors.TextSecondary,
                )
            }
        }
        val several = usage.folders.size > 1
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
            usage.folders.forEach { folder ->
                if (collapsed) {
                    Spacer(Modifier.height(6.dp))
                    CompactFolder(folder, usage.platformOrder, showName = several)
                } else {
                    Spacer(Modifier.height(if (several) 16.dp else 8.dp))
                    FolderBlock(folder, usage.platformOrder, showHeader = several)
                }
            }
        }
    }
}

/** A folder on one line: its name when there are several, its console bar, the room left. */
@Composable
private fun CompactFolder(folder: FolderUsage, platformOrder: List<String>, showName: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showName) {
            Text(
                folder.folder?.let { folderLabel(it) } ?: stringResource(R.string.library_storage_unknown_folder),
                modifier = Modifier.width(150.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (folder.folder == null) RShopColors.Warning else RShopColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) {
            if (folder.gamesBytes > 0) {
                Bar(slicesOf(folder, platformOrder).map { it.bytes.toFloat() to it.color }, track = RShopColors.SurfaceHighest, height = 8.dp)
            }
        }
        folder.device?.takeIf { it.totalBytes > 0 }?.let { device ->
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.library_storage_free_short, formatSize(device.freeBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = RShopColors.TextTertiary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderBlock(folder: FolderUsage, platformOrder: List<String>, showHeader: Boolean) {
    val others = stringResource(R.string.library_storage_others)
    if (showHeader) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                folder.folder?.let { folderLabel(it) } ?: stringResource(R.string.library_storage_unknown_folder),
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleSmall,
                color = if (folder.folder == null) RShopColors.Warning else RShopColors.TextPrimary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                formatSize(folder.gamesBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = RShopColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    val slices = slicesOf(folder, platformOrder)
    if (folder.gamesBytes > 0) {
        Bar(slices.map { it.bytes.toFloat() to it.color }, track = RShopColors.SurfaceHighest, height = 16.dp)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(slice.color, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    val percent = (slice.bytes * 100f / folder.gamesBytes).toInt().coerceAtLeast(1)
                    Text(
                        "${slice.label.ifEmpty { others }} · ${formatSize(slice.bytes)} ($percent %)",
                        style = MaterialTheme.typography.bodySmall,
                        color = RShopColors.TextSecondary,
                    )
                }
            }
        }
    }
    folder.device?.takeIf { it.totalBytes > 0 }?.let { device ->
        Spacer(Modifier.height(if (folder.gamesBytes > 0) 16.dp else 0.dp))
        val otherUsed = (device.totalBytes - device.freeBytes - folder.volumeGamesBytes).coerceAtLeast(0)
        Bar(
            listOf(
                folder.volumeGamesBytes.toFloat() to RShopColors.Accent,
                otherUsed.toFloat() to RShopColors.Outline,
            ),
            track = RShopColors.SurfaceHighest,
            height = 8.dp,
            total = device.totalBytes.toFloat(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.library_storage_free, formatSize(device.freeBytes), formatSize(device.totalBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = RShopColors.TextTertiary,
        )
    }
}

/** Horizontal bar of coloured parts; [total] defaults to their sum, the rest is the track. */
@Composable
private fun Bar(parts: List<Pair<Float, Color>>, track: Color, height: androidx.compose.ui.unit.Dp, total: Float = parts.sumOf { it.first.toDouble() }.toFloat()) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track),
    ) {
        val visible = parts.filter { it.first > 0f && total > 0f }
        visible.forEach { (value, color) ->
            // A sliver stays visible even for a tiny console.
            Box(Modifier.weight((value / total).coerceAtLeast(0.01f)).fillMaxHeight().background(color))
        }
        val rest = 1f - visible.sumOf { (it.first / total).coerceAtLeast(0.01f).toDouble() }.toFloat()
        if (rest > 0.001f) Spacer(Modifier.weight(rest))
    }
}
