package com.rshop.data.preferences

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class AppLanguage(val tag: String?) {
    System(null),
    French("fr"),
    English("en"),
}

/**
 * Per-app language through the platform LocaleManager (API 33+). The system persists the choice
 * and recreates activities itself, so there is nothing to store on our side.
 */
@Singleton
class AppLanguageController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val localeManager = context.getSystemService(LocaleManager::class.java)

    fun current(): AppLanguage {
        val locales = localeManager.applicationLocales
        if (locales.isEmpty) return AppLanguage.System
        val language = locales[0].language
        return AppLanguage.entries.firstOrNull { it.tag == language } ?: AppLanguage.System
    }

    fun set(language: AppLanguage) {
        localeManager.applicationLocales = language.tag
            ?.let { LocaleList.forLanguageTags(it) }
            ?: LocaleList.getEmptyLocaleList()
    }
}
