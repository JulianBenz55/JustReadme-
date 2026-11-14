package com.aryan.reader.webimport

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Renders a page like a real browser (JavaScript, challenges, cookies) and returns the final DOM. */
internal typealias PageRenderer = suspend (url: String) -> FetchedPage

/**
 * Off-screen WebView renderer, the Android counterpart of Readest's `renderNovelPage` (`clip_url`).
 *
 * Sites such as webnovel.com sit behind Cloudflare / JS challenges and answer a plain
 * `HttpURLConnection` with HTTP 403 no matter which headers it sends (TLS fingerprint, missing
 * JavaScript). A real WebView passes those checks, so [NovelImporter] falls back to it whenever the
 * plain fetch is blocked or the fetched HTML is only an empty JavaScript shell.
 *
 * The WebView shares [CookieManager] with the in-app browser and [HttpFetcher], so a clearance
 * cookie earned here also helps the plain requests afterwards.
 */
internal object WebViewRenderer {
    private const val POLL_MS = 600L
    private const val STABLE_POLLS = 3
    private const val DEFAULT_TIMEOUT_MS = 40_000L

    // One WebView at a time: parallel challenge solving is slower and gets flagged more often.
    private val mutex = Mutex()

    /** User-Agent of the last successful render; clearance cookies are bound to it. */
    @Volatile
    var lastUserAgent: String? = null
        private set

    private val CHALLENGE_TITLE = Regex(
        "<title[^>]*>\\s*(just a moment|attention required|checking your browser|verify you are human|" +
            "access denied|ddos-guard|security check|are you a robot|one more step)",
        RegexOption.IGNORE_CASE,
    )
    private val CHALLENGE_MARKERS = listOf("cf-browser-verification", "challenge-running", "id=\"challenge-form\"")

    /** True while the DOM is still an anti-bot interstitial rather than the real page. */
    internal fun looksLikeChallenge(html: String): Boolean {
        if (CHALLENGE_TITLE.containsMatchIn(html.take(4096))) return true
        // Real pages are large; interstitials are tiny and carry one of Cloudflare's markers.
        return html.length < 60_000 && CHALLENGE_MARKERS.any { html.contains(it, ignoreCase = true) }
    }

    suspend fun render(context: Context, url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): FetchedPage {
        NovelUrlGuard.assertAllowed(url)
        val appContext = context.applicationContext
        return mutex.withLock {
            withContext(Dispatchers.Main) { renderOnMain(appContext, url, timeoutMs) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderOnMain(context: Context, url: String, timeoutMs: Long): FetchedPage {
        val view = try {
            WebView(context)
        } catch (e: Exception) {
            throw NovelImportException(
                "No WebView is available on this device.",
                NovelImportException.Kind.FETCH_FAILED,
            )
        }
        var pageLoaded = false
        var navigationError: String? = null
        var blockedNavigation = false
        try {
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.loadsImagesAutomatically = false // text is all we need; saves data and time
            // A detached WebView has no size; give it one so layout-dependent scripts run normally.
            view.layout(0, 0, 1080, 1920)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.resumeTimers()
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString() ?: return true
                    // Same SSRF guard as the plain fetcher, applied to every redirect hop.
                    return try {
                        NovelUrlGuard.assertAllowed(target)
                        false
                    } catch (e: NovelImportException) {
                        blockedNavigation = true
                        true
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    pageLoaded = false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded = true
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) navigationError = error?.description?.toString() ?: "Load error"
                }
            }
            view.loadUrl(url)

            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var lastLength = -1
            var stablePolls = 0
            var best: String? = null
            while (true) {
                delay(POLL_MS)
                currentCoroutineContext().ensureActive()
                if (blockedNavigation) {
                    throw NovelImportException(
                        "Cannot import from a private or unsupported URL",
                        NovelImportException.Kind.BLOCKED_URL,
                    )
                }
                navigationError?.let { throw NovelImportException(it, NovelImportException.Kind.FETCH_FAILED) }

                val html = readHtml(view)
                if (html != null && !looksLikeChallenge(html)) {
                    best = html
                    if (html.length == lastLength) stablePolls++ else {
                        stablePolls = 0
                        lastLength = html.length
                    }
                    // Loaded and the DOM stopped growing: client-side rendering is finished.
                    if (pageLoaded && stablePolls >= STABLE_POLLS) break
                } else {
                    stablePolls = 0
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    if (best != null) break // slow SPA: take what is there, the parser decides
                    throw NovelImportException(
                        "The site's browser check was not passed (HTTP 403).",
                        NovelImportException.Kind.BOT_BLOCK,
                        403,
                    )
                }
            }
            lastUserAgent = view.settings.userAgentString
            CookieManager.getInstance().flush()
            return FetchedPage(best.orEmpty(), view.url?.takeIf { it.startsWith("http") } ?: url)
        } finally {
            view.stopLoading()
            view.destroy()
        }
    }

    private suspend fun readHtml(view: WebView): String? = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
            val html = try {
                JSONTokener(raw).nextValue() as? String
            } catch (e: Exception) {
                null
            }
            if (cont.isActive) cont.resume(html)
        }
    }
}
