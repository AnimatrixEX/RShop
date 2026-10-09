package com.rshop.data.backup

import com.rshop.BuildConfig
import com.rshop.data.preferences.AppLanguage
import com.rshop.data.preferences.AppLanguageController
import com.rshop.data.repository.GameListRepository
import com.rshop.data.source.SourceManager
import com.rshop.data.source.SourceRepository
import com.rshop.domain.model.CatalogPrefs
import com.rshop.domain.model.FocusStyle
import com.rshop.domain.model.CoverStyle
import com.rshop.domain.model.TextSize
import com.rshop.domain.model.ThemeAccent
import com.rshop.domain.model.ThemeBase
import com.rshop.domain.model.ThemeSettings
import com.rshop.domain.repository.GameRepository
import com.rshop.domain.repository.SettingsRepository
import com.rshop.scraper.ScraperConfigException
import com.rshop.scraper.config.ScraperConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Writes the user's own data to one JSON file and puts it back, on this device or another. */
@Singleton
class BackupManager @Inject constructor(
    private val sourceManager: SourceManager,
    private val sourceRepository: SourceRepository,
    private val games: GameRepository,
    private val lists: GameListRepository,
    private val settings: SettingsRepository,
    private val language: AppLanguageController,
    private val pending: PendingRestore,
    private val clock: Clock,
) {
    suspend fun export(): String {
        val current = settings.settings.first()
        val backup = Backup(
            appVersion = BuildConfig.VERSION_NAME,
            createdAt = clock.millis(),
            sources = sourceRepository.all(),
            favorites = games.observeFavorites().first().map { BackupGame(it.id, it.title, it.platform) },
            lists = lists.snapshot().map { list -> BackupList(list.name, list.games.map { BackupGame(it.id, it.title, it.platform) }) },
            settings = BackupSettings(
                wifiOnly = current.wifiOnly,
                deleteArchivesAfterInstall = current.deleteArchivesAfterInstall,
                pauseOnLowBattery = current.pauseOnLowBattery,
                minFreeSpaceMb = current.minFreeSpaceMb,
                autoCheckUpdates = current.autoCheckUpdates,
                readPagesAhead = current.readPagesAhead,
                themeBase = current.theme.base.name,
                themeAccent = current.theme.accent.name,
                themeFocus = current.theme.focus.name,
                themeTextSize = current.theme.textSize.name,
                themeCoverStyle = current.theme.coverStyle.name,
                animatedBackground = current.theme.animatedBackground,
                dynamicBackdrop = current.theme.dynamicBackdrop,
                hideInstalled = current.catalog.hideInstalled,
                hideExtras = current.catalog.hideExtras,
                language = language.current().tag,
            ),
        )
        return BackupCodec.encode(backup)
    }

    /** Reads and checks a backup file; nothing is changed yet. */
    fun parse(text: String): Backup = BackupCodec.decode(text)

    fun summarize(backup: Backup) = BackupSummary(
        createdAt = backup.createdAt,
        appVersion = backup.appVersion,
        sources = backup.sources.map(ScraperConfig::name),
        favorites = backup.favorites.size,
        lists = backup.lists.size,
    )

    suspend fun restore(backup: Backup): RestoreReport {
        applySettings(backup.settings)
        // Sources first: they bring the games that favorites and lists point at.
        backup.sources.forEach { sourceManager.add(it) }

        val wanted = (backup.favorites.map { it.id } + backup.lists.flatMap { list -> list.games.map { it.id } }).distinct()
        val present = games.existingIds(wanted)

        backup.favorites.filter { it.id in present }.forEach { games.setFavorite(it.id, true) }
        var restoredLists = 0
        val waiting = LinkedHashMap<String, List<String>>()
        for (list in backup.lists) {
            val id = lists.idOfOrCreate(list.name) ?: continue
            restoredLists++
            list.games.filter { it.id in present }.forEach { lists.setMember(id, it.id, true) }
            val later = list.games.map { it.id }.filter { it !in present }
            if (later.isNotEmpty()) waiting[list.name] = later
        }
        val favoritesLater = backup.favorites.map { it.id }.filter { it !in present }
        pending.add(favoritesLater, waiting)

        // Applied last: a language change restarts the activity.
        applyLanguage(backup.settings.language)
        return RestoreReport(
            sources = backup.sources.size,
            favorites = backup.favorites.size - favoritesLater.size,
            lists = restoredLists,
            waiting = favoritesLater.size + waiting.values.sumOf { it.size },
        )
    }

    private suspend fun applySettings(saved: BackupSettings) {
        settings.setWifiOnly(saved.wifiOnly)
        settings.setDeleteArchivesAfterInstall(saved.deleteArchivesAfterInstall)
        settings.setPauseOnLowBattery(saved.pauseOnLowBattery)
        settings.setMinFreeSpaceMb(saved.minFreeSpaceMb)
        settings.setAutoCheckUpdates(saved.autoCheckUpdates)
        settings.setReadPagesAhead(saved.readPagesAhead)
        val theme = ThemeSettings(
            base = enumOrDefault(saved.themeBase, ThemeBase.Night),
            accent = enumOrDefault(saved.themeAccent, ThemeAccent.Blue),
            focus = enumOrDefault(saved.themeFocus, FocusStyle.White),
            textSize = enumOrDefault(saved.themeTextSize, TextSize.Normal),
            coverStyle = enumOrDefault(saved.themeCoverStyle, CoverStyle.Flat),
            animatedBackground = saved.animatedBackground,
            dynamicBackdrop = saved.dynamicBackdrop,
        )
        settings.setTheme(theme)
        settings.setCatalogPrefs(
            CatalogPrefs(saved.hideInstalled, saved.hideExtras, filtersExpanded = settings.settings.first().catalog.filtersExpanded),
        )
    }

    private fun applyLanguage(tag: String?) {
        val wanted = AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.System
        if (wanted != language.current()) language.set(wanted)
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
