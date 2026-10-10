package com.rshop.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import android.provider.DocumentsContract
import com.rshop.data.storage.DirectoryLocation
import com.rshop.data.storage.GamesFolder
import com.rshop.installation.DeviceSpace
import com.rshop.ui.theme.RShopTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws the library storage card with several games folders to build/screen-previews, to look at it without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h900dp-xhdpi")
class StorageCardPreviewTest {

    @Suppress("DEPRECATION")
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun folder(volume: String, path: String, default: Boolean = false) = GamesFolder(
        uri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "$volume:$path"),
        location = DirectoryLocation(volume, volume == "primary", path),
        available = true,
        isDefault = default,
    )

    private fun usage(vararg parts: Pair<String, Long>) = parts.map { PlatformUsage(it.first, it.second, 3) }

    private fun shoot(name: String, usage: StorageUsage, collapsed: Boolean = false) {
        composeRule.setContent {
            RShopTheme {
                Box(Modifier.width(860.dp).padding(16.dp)) { StorageCard(usage, collapsed = collapsed, onToggle = {}) }
            }
        }
        composeRule.waitForIdle()
        val out = File("build/screen-previews").apply { mkdirs() }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val gb = 1L shl 30

    @Test
    fun oneFolder() = shoot(
        "storage-one",
        StorageUsage(
            gamesBytes = 30 * gb,
            folders = listOf(
                FolderUsage(folder("primary", "Roms", default = true), 30 * gb, usage("PS2" to 18 * gb, "GBA" to 8 * gb, "SNES" to 4 * gb), DeviceSpace(100 * gb, 256 * gb), 30 * gb),
            ),
            platformOrder = listOf("PS2", "GBA", "SNES"),
        ),
    )

    @Test
    fun severalFolders() = shoot("storage-several", several)

    @Test
    fun severalFoldersCompact() = shoot("storage-several-compact", several, collapsed = true)

    private val several =
        StorageUsage(
            gamesBytes = 70 * gb,
            folders = listOf(
                FolderUsage(folder("primary", "Roms", default = true), 12 * gb, usage("GBA" to 8 * gb, "SNES" to 4 * gb), DeviceSpace(100 * gb, 256 * gb), 12 * gb),
                FolderUsage(folder("1234-ABCD", "Games"), 58 * gb, usage("PS2" to 40 * gb, "Switch" to 18 * gb), DeviceSpace(300 * gb, 512 * gb), 58 * gb),
                FolderUsage(folder("9999-FFFF", "Empty"), 0, emptyList(), DeviceSpace(50 * gb, 64 * gb), 0),
                FolderUsage(null, 2 * gb, usage("NES" to 2 * gb), null, 0),
            ),
            platformOrder = listOf("PS2", "Switch", "GBA", "SNES"),
        )
}
