package com.rshop.scraper

/** Logging hook so this JVM module stays free of Android dependencies (the app plugs Timber in). */
interface ScraperLog {
    fun debug(message: String)
    fun warn(message: String, error: Throwable? = null)

    object None : ScraperLog {
        override fun debug(message: String) = Unit
        override fun warn(message: String, error: Throwable?) = Unit
    }
}
