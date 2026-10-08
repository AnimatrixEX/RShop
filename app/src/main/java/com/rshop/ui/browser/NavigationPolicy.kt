package com.rshop.ui.browser

/**
 * What the in-app browser does with a navigation or a new window. Ad pages usually arrive as
 * a pop-up (or a redirect of the current page) towards another site than the link that was
 * clicked; those are never shown without the user agreeing.
 */
object NavigationPolicy {

    enum class Decision {
        /** Load it. */
        Allow,
        /** Never: not a web page, or an ad host. */
        Block,
        /** Another site the user did not click: ask before showing it. */
        Ask,
    }

    /** A page change in the current tab. [isRedirect]: an HTTP redirect answered by the server. */
    fun navigation(target: String, current: String?, clickedLink: String?, isRedirect: Boolean): Decision = when {
        !isWebUrl(target) || AdBlocker.isBlocked(hostOf(target)) -> Decision.Block
        // Server redirects lead to the file or its mirror host: that is how downloads work.
        isRedirect -> Decision.Allow
        current == null || !isWebUrl(current) || sameSite(target, current) -> Decision.Allow
        sameUrl(target, clickedLink) -> Decision.Allow
        else -> Decision.Ask
    }

    /** A new window opened by [opener]: only the clicked link, or a page of the same site. */
    fun popup(target: String, opener: String?, clickedLink: String?): Decision = when {
        !isWebUrl(target) || AdBlocker.isBlocked(hostOf(target)) -> Decision.Block
        sameUrl(target, clickedLink) -> Decision.Allow
        opener != null && sameSite(target, opener) -> Decision.Allow
        else -> Decision.Ask
    }

    fun hostOf(url: String?): String? =
        url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore('?')?.substringBefore('#')
            ?.substringAfter('@')?.substringBefore(':')?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Same registrable domain: dl.example.com and www.example.com are one site. */
    fun sameSite(a: String, b: String): Boolean {
        val siteA = siteOf(hostOf(a)) ?: return false
        return siteA == siteOf(hostOf(b))
    }

    private fun siteOf(host: String?): String? {
        val labels = host?.split('.')?.filter { it.isNotEmpty() } ?: return null
        if (labels.size <= 2 || labels.all { part -> part.all(Char::isDigit) }) return labels.joinToString(".")
        // example.co.uk, example.com.br: the last two labels are a public suffix.
        val keep = if (labels.last().length == 2 && labels[labels.size - 2].length <= 3) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    private fun isWebUrl(url: String) = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun sameUrl(a: String, b: String?) = b != null && a.substringBefore('#').trimEnd('/') == b.substringBefore('#').trimEnd('/')
}
