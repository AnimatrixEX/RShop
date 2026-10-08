package com.rshop.ui.browser

/**
 * Hosts of ad networks, pop-under / malvertising networks and trackers. Requests to them (and
 * their subdomains) are never loaded by the in-app browser, and pages on them never opened.
 */
object AdBlocker {
    private val BLOCKED = setOf(
        // Ad networks and ad servers.
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "googletagservices.com", "pagead2.googlesyndication.com", "adnxs.com", "adsrvr.org", "amazon-adsystem.com",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "rubiconproject.com", "pubmatic.com",
        "openx.net", "casalemedia.com", "smartadserver.com", "moatads.com", "media.net", "yieldmo.com",
        "adform.net", "bidswitch.net", "sharethrough.com", "33across.com", "teads.tv", "revcontent.com",
        "mgid.com", "zergnet.com", "adcash.com", "admaven.com", "ad-maven.com", "adsterra.com", "adsterratech.com",
        // Pop-unders, redirects and malvertising.
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "onclickads.net", "onclkds.com",
        "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net", "hilltopads.net", "clickadu.com",
        "clickaine.com", "a-ads.com", "richpush.co", "pushame.com", "pushnami.com", "monetag.com",
        "highperformanceformat.com", "profitablecpmrate.com", "displayvertising.com", "galaksion.com",
        "bebi.com", "zeroredirect1.com", "trafficstars.com", "tsyndicate.com", "adspyglass.com",
        "realsrv.com", "syndication.realsrv.com", "dolohen.com", "pemsrv.com", "mnaspm.com", "acint.net",
        "coinhive.com", "coin-hive.com", "cryptoloot.pro", "jsecoin.com",
        // Trackers.
        "google-analytics.com", "googletagmanager.com", "scorecardresearch.com", "quantserve.com",
        "hotjar.com", "mixpanel.com", "facebook.net", "connect.facebook.net", "mc.yandex.ru", "clarity.ms", "newrelic.com", "nr-data.net",
    )

    /** True for a blocked host or any of its subdomains. */
    fun isBlocked(host: String?): Boolean {
        var current = host?.lowercase()?.trimEnd('.') ?: return false
        while (true) {
            if (current in BLOCKED) return true
            val dot = current.indexOf('.')
            if (dot < 0 || current.indexOf('.', dot + 1) < 0) return false
            current = current.substring(dot + 1)
        }
    }
}
