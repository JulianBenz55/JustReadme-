package com.aryan.reader.webimport

/**
 * Browser-shaped request headers (1:1 from Readest's `httpHeaders.ts`). Anti-bot layers (Cloudflare,
 * Medium, NYT and many novel sites) answer 403/429 to requests that lack the full Chrome header set
 * as much as to a non-browser User-Agent, which is exactly why a bare `HttpURLConnection` GET fails
 * on pages Readest opens without trouble. This does not beat real JS challenges; for those the
 * in-app browser session is used (see [HttpFetcher] cookies and the "open in browser" flow).
 */
internal object BrowserHeaders {
    const val BROWSER_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val SEC_CH_UA = "\"Chromium\";v=\"124\", \"Not-A.Brand\";v=\"99\", \"Google Chrome\";v=\"124\""

    /**
     * @param sessionUserAgent the in-app WebView's User-Agent. Clearance cookies (cf_clearance) are
     *   bound to the UA that earned them, so after the user passed a challenge in the browser the
     *   same UA must be sent; the client hints are then switched to the matching mobile values.
     */
    private fun base(sessionUserAgent: String?): MutableMap<String, String> {
        val mobile = sessionUserAgent != null
        return linkedMapOf(
            "user-agent" to (sessionUserAgent ?: BROWSER_UA),
            "accept-language" to "en-US,en;q=0.9",
            "sec-ch-ua" to SEC_CH_UA,
            "sec-ch-ua-mobile" to if (mobile) "?1" else "?0",
            "sec-ch-ua-platform" to if (mobile) "\"Android\"" else "\"macOS\"",
        )
    }

    /** Headers a real Chrome sends when navigating to a top-level URL. */
    fun pageNavigate(sessionUserAgent: String? = null): Map<String, String> = base(sessionUserAgent).apply {
        put("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        put("sec-fetch-dest", "document")
        put("sec-fetch-mode", "navigate")
        put("sec-fetch-site", "none")
        put("sec-fetch-user", "?1")
        put("upgrade-insecure-requests", "1")
    }

    /** Headers a real Chrome sends when loading an image from a page. */
    fun image(referer: String?, sessionUserAgent: String? = null): Map<String, String> = base(sessionUserAgent).apply {
        put("accept", "image/avif,image/webp,image/png,image/svg+xml,image/*,*/*;q=0.8")
        put("sec-fetch-dest", "image")
        put("sec-fetch-mode", "no-cors")
        put("sec-fetch-site", "cross-site")
        // Some CDNs gate image responses on a page-origin Referer (NYT, WSJ, many news CDNs).
        if (referer != null) put("referer", referer)
    }
}
