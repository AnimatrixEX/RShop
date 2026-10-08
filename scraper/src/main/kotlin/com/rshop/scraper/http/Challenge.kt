package com.rshop.scraper.http

import okhttp3.Response
import org.jsoup.nodes.Document

/**
 * Recognises CAPTCHAs and anti-bot challenges so they can be reported clearly.
 * Detection only: RShop never tries to solve or get around them.
 */
object Challenge {
    private const val INTERSTITIAL =
        "#challenge-form, #challenge-stage, #cf-challenge-running, .cf-browser-verification, #ddg-captcha, #px-captcha"
    private val INTERSTITIAL_TITLES = Regex("(?i)^\\s*(just a moment|attention required|ddos-guard|checking your browser)")
    private const val CAPTCHA_WIDGETS =
        ".g-recaptcha, .h-captcha, .cf-turnstile, [data-sitekey], iframe[src*=recaptcha], iframe[src*=hcaptcha], " +
            "iframe[src*=challenges.cloudflare.com], script[src*=recaptcha/api], script[src*=hcaptcha.com], script[src*=turnstile]"

    /** A page that is nothing but a challenge (the real content is withheld). */
    fun isInterstitial(document: Document): Boolean =
        document.selectFirst(INTERSTITIAL) != null || INTERSTITIAL_TITLES.containsMatchIn(document.title())

    /** A CAPTCHA widget somewhere on the page (it may only guard a form, e.g. comments). */
    fun hasCaptcha(document: Document): Boolean = document.selectFirst(CAPTCHA_WIDGETS) != null

    fun isChallengeResponse(response: Response): Boolean =
        response.header("cf-mitigated").equals("challenge", ignoreCase = true)
}
