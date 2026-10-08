package com.rshop.scraper.http

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Keeps cookies for the session, as a browser does: many download pages set a session cookie
 * that the "Download now" link then expects. Only in memory (lost when the app stops), never
 * shared across hosts beyond what each cookie's domain allows, and expired cookies are dropped.
 */
class MemoryCookieJar(private val now: () -> Long = System::currentTimeMillis) : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > now()) this.cookies += cookie
        }
        // Bounded: a misbehaving site cannot grow it without limit.
        while (this.cookies.size > MAX_COOKIES) this.cookies.removeAt(0)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val time = now()
        cookies.removeAll { it.expiresAt <= time }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() = cookies.clear()

    private companion object {
        const val MAX_COOKIES = 300
    }
}
