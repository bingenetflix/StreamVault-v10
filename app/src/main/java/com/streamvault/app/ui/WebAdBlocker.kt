package com.streamvault.app.ui

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object WebAdBlocker {

    private val AD_DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com",
        "ads.youtube.com",
        "adnxs.com",
        "adsrvr.org",
        "adcolony.com",
        "admob.com",
        "amazon-adsystem.com",
        "applovin.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "smartadserver.com",
        "media.net",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "propellerclick.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.net",
        "adsterra.com",
        "highperformanceformat.com",
        "profitabledisplaynetwork.com",
        "hilltopads.net",
        "clickadu.com",
        "mgid.com",
        "revcontent.com",
        "zedo.com",
        "scorecardresearch.com",
        "quantserve.com",
        "hotjar.com",
        "moatads.com",
        "chartbeat.com",
        "unityads.unity3d.com",
        "vungle.com",
        "inmobi.com",
        "bidswitch.net",
        "casalemedia.com",
        "sharethrough.com",
        "triplelift.com",
        "yieldmo.com",
        "33across.com",
        "spotxchange.com",
        "teads.tv",
        "smartclip.net",
        "freewheel.tv"
    )

    private val AD_URL_KEYWORDS = listOf(
        "/pagead/",
        "/ads/",
        "/adserver",
        "/adview",
        "/adbanner",
        "/popunder",
        "/popupad",
        "doubleclick",
        "googlesyndication",
        "ad_status.js",
        "show_ads.js",
        "adunit",
        "vast.xml",
        "vmap.xml"
    )

    fun isAdRequest(uri: Uri?): Boolean {
        if (uri == null) return false
        val host = uri.host?.lowercase() ?: return false
        val fullUrl = uri.toString().lowercase()

        // Never block YouTube's core video/player infrastructure
        if (host.endsWith("googlevideo.com") || host.endsWith("ytimg.com")) {
            return false
        }

        // Check exact domain or subdomain match
        for (adDomain in AD_DOMAINS) {
            if (host == adDomain || host.endsWith(".$adDomain")) {
                return true
            }
        }

        // Check common ad script/vast path patterns on non-YouTube domains
        if (!host.endsWith("youtube.com")) {
            for (keyword in AD_URL_KEYWORDS) {
                if (fullUrl.contains(keyword)) {
                    return true
                }
            }
        }

        return false
    }

    fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    // CSS + JS cosmetic ad-hider & popup blocker injected into web pages
    const val COSMETIC_ADBLOCK_JS = """
        (function() {
            window.open = function() { return null; };
            var styleId = 'sv-adblock-style';
            if (!document.getElementById(styleId)) {
                var style = document.createElement('style');
                style.id = styleId;
                style.innerHTML = [
                    'ins.adsbygoogle',
                    '[id^="google_ads_"]',
                    '[id*="ad-banner"]',
                    '[class*="ad-banner"]',
                    '[class*="popup-ad"]',
                    '[class*="overlay-ad"]',
                    '.ytp-ad-module',
                    '.ytp-ad-overlay-container',
                    '.ytp-ad-player-overlay',
                    'ytm-promoted-sparkles-web-renderer',
                    'ytm-companion-ad-renderer',
                    '#player-ads',
                    'iframe[src*="doubleclick"]',
                    'iframe[src*="googlesyndication"]',
                    'iframe[src*="adsterra"]',
                    'iframe[src*="popads"]'
                ].join(',') + '{ display: none !important; visibility: hidden !important; height: 0 !important; width: 0 !important; pointer-events: none !important; }';
                (document.head || document.documentElement).appendChild(style);
            }
            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern');
            if (skipBtn) { skipBtn.click(); }
        })();
    """
}
