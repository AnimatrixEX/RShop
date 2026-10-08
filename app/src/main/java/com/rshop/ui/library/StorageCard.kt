package com.rshop.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rshop.R
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.formatSize

private val SegmentColors = listOf(
    Color(0xFF4F8CFF), Color(0xFFFF8A3D), Color(0xFF3DDC97), Color(0xFFB46CFF),
    Color(0xFFFF5C8A), Color(0xFFFFD23F), Color(0xFF3CC8E0),
)
private val OthersColor = Color(0xFF8A8F9C)
private const val MAX_SEGMENTS = 6

/** One slice of the graph: a console, or the consoles too small to list. */
private data class Slice(val label: String, val bytes: Long, val color: Color)

private fun slicesOf(usage: StorageUsage): List<Slice> {
    val top = usage.byPlatform.take(MAX_SEGMENTS)
    val rest = usage.byPlatform.drop(MAX_SEGMENTS).sumOf { it.bytes }
    return top.mapIndexed { index, item -> Slice(item.platform, item.bytes, SegmentColors[index % SegmentColors.size]) } +
        listOfNotNull(rest.takeIf { it > 0 }?.let { Slice("", it, OthersColor) })
}

/** Where the games folder's space goes: a bar split per console, and the volume around it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorageCard(usage: StorageUsage, modifier: Modifier = Modifier) {
    val slices = slicesOf(usage)
    val others = stringResource(R.string.library_storage_others)
    androidx.compose.foundation.layout.Column(
        modifier
            .fillMaxWidth()
            .background(RShopColors.Surface, RoundedCornerShape(16.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.library_storage_title), style = MaterialTheme.typography.titleMedium, color = RShopColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.library_storage_games, formatSize(usage.gamesBytes)),
                style = MaterialTheme.typography.bodyMedium,
                color = RShopColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(12.dp))
        Bar(slices.map { it.bytes.toFloat() to it.color }, track = RShopColors.SurfaceHighest, height = 16.dp)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(slice.color, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    val percent = (slice.bytes * 100f / usage.gamesBytes).toInt().coerceAtLeast(1)
                    Text(
                        "${slice.label.ifEmpty { others }} · ${formatSize(slice.bytes)} ($percent %)",
                        style = MaterialTheme.typography.bodySmall,
                        color = RShopColors.TextSecondary,
                    )
                }
            }
        }
        usage.device?.takeIf { it.totalBytes > 0 }?.let { device ->
            Spacer(Modifier.height(16.dp))
            val otherUsed = (device.totalBytes - device.freeBytes - usage.gamesBytes).coerceAtLeast(0)
            Bar(
                listOf(
                    usage.gamesBytes.toFloat() to RShopColors.Accent,
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
