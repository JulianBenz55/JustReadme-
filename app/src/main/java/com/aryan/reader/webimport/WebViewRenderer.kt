package com.aryan.reader.webimport

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Renders a page like a real browser (JavaScript, challenges, cookies) and returns the final DOM.
 *
 * [readySelector] is an optional CSS selector list naming the element that holds the real content
 * (the chapter body). When it is given, the renderer can return as soon as that element has text
 * instead of waiting for the whole page to go quiet.
 */
internal typealias PageRenderer = suspend (url: String, readySelector: String?) -> FetchedPage

/**
 * Off-screen WebView renderer, the Android counterpart of Readest's `renderNovelPage` (`clip_url`).
 *
 * Sites such as webnovel.com sit behind Cloudflare / JS challenges and answer a plain
 * `HttpURLConnection` with HTTP 403 no matter which headers it sends (TLS fingerprint, missing
 * JavaScript). A real WebView passes those checks, so [NovelImporter] falls back to it whenever the
 * plain fetch is blocked or the fetched HTML is only an empty JavaScript shell.
 *
 * Speed: the first render of a site runs alone (it may have to solve a challenge); after that up to
 * [MAX_PARALLEL] renders run side by side. Images, fonts, media and trackers are not loaded, and the
 * wait polls with a tiny JS probe (a few numbers) instead of copying the whole DOM every time.
 *
 * The WebView shares [CookieManager] with the in-app browser and [HttpFetcher], so a clearance
 * cookie earned here also helps the plain requests afterwards.
 */
internal object WebViewRenderer {
    private const val POLL_MS = 150L
    private const val DEFAULT_TIMEOUT_MS = 40_000L
    private const val MAX_PARALLEL = 3

    /** Quiet polls needed once the page finished loading and no content selector is known. */
    private const val STABLE_POLLS = 4

    /** Quiet polls needed when the content element already holds text and the page finished loading. */
    private const val READY_STABLE_POLLS = 2

    /** Same, but the page never reported "finished" (a hanging tracker): don't wait for it forever. */
    private const val READY_STABLE_POLLS_NO_LOAD_EVENT = 7

    /** Minimum text inside [readySelector] to count as "the content is there". */
    private const val READY_MIN_TEXT = 100

    private val firstRenderLock = Mutex()
    private val slots = Semaphore(MAX_PARALLEL)
    private val clearedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** User-Agent of the last successful render; clearance cookies are bound to it. */
    @Volatile
    var lastUserAgent: String? = null
        private set

    private val CHALLENGE_TITLE = Regex(
        "^\\s*(just a moment|attention required|checking your browser|verify you are human|" +
            "access denied|ddos-guard|security check|are you a robot|one more step)",
        RegexOption.IGNORE_CASE,
    )
    private val HTML_CHALLENGE_TITLE = Regex(
        "<title[^>]*>\\s*(just a moment|attention required|checking your browser|verify you are human|" +
            "access denied|ddos-guard|security check|are you a robot|one more step)",
        RegexOption.IGNORE_CASE,
    )
    private val CHALLENGE_MARKERS = listOf("cf-browser-verification", "challenge-running", "id=\"challenge-form\"")

    /** True while the DOM is still an anti-bot interstitial rather than the real page. */
    internal fun looksLikeChallenge(html: String): Boolean {
        if (HTML_CHALLENGE_TITLE.containsMatchIn(html.take(4096))) return true
        // Real pages are large; interstitials are tiny and carry one of Cloudflare's markers.
        return html.length < 60_000 && CHALLENGE_MARKERS.any { html.contains(it, ignoreCase = true) }
    }

    // Sub-resources a text import never needs. Main-frame navigations are never blocked.
    private val BLOCKED_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp",
        "woff", "woff2", "ttf", "otf", "eot",
        "mp4", "webm", "mp3", "m4a", "ogg", "wav",
    )
    private val BLOCKED_HOST_PARTS = listOf(
        "googlesyndication.com", "doubleclick.net", "googletagmanager.com", "google-analytics.com",
        "googleadservices.com", "adservice.google", "facebook.net", "hotjar.com", "clarity.ms",
        "scorecardresearch.com", "taboola.com", "outbrain.com",
    )

    private fun isNoise(request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        val host = uri.host?.lowercase().orEmpty()
        if (BLOCKED_HOST_PARTS.any { host.endsWith(it) || host.contains(it) }) return true
        val extension = uri.path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension in BLOCKED_EXTENSIONS
    }

    private fun emptyResponse() = WebResourceResponse(
        "text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)),
    )

    private fun hostOf(url: String): String = try {
        URL(url).host.lowercase()
    } catch (e: Exception) {
        ""
    }

    suspend fun render(
        context: Context,
        url: String,
        readySelector: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): FetchedPage {
        NovelUrlGuard.assertAllowed(url)
        val appContext = context.applicationContext
        val host = hostOf(url)

        suspend fun once(): FetchedPage = slots.withPermit {
            withContext(Dispatchers.Main) { renderOnMain(appContext, url, readySelector, timeoutMs) }
        }

        if (host !in clearedHosts) {
            // The first render of a site may have to solve a JS challenge: do that one alone, since
            // parallel challenge solving is slower and gets flagged more often.
            firstRenderLock.withLock {
                if (host !in clearedHosts) {
                    val page = once()
                    clearedHosts.add(host)
                    CookieManager.getInstance().flush()
                    return page
                }
            }
        }
        return once()
    }

    private class Probe(val state: String, val textLength: Int, val ready: Boolean, val title: String, val challenge: Boolean)

    private fun probeScript(readySelector: String?): String {
        val selector = if (readySelector == null) "null" else JSONObject.quote(readySelector)
        return "(function(){var b=document.body;if(!b)return[document.readyState,0,false,document.title||'',false];" +
            "var ready=false;var sel=$selector;" +
            "if(sel){try{var els=document.querySelectorAll(sel);" +
            "for(var i=0;i<els.length;i++){if(els[i].textContent.length>=$READY_MIN_TEXT){ready=true;break;}}}catch(e){}}" +
            "var ch=!!document.querySelector('#challenge-form,.cf-browser-verification,#challenge-running');" +
            "return[document.readyState,b.textContent.length,ready,(document.title||'').slice(0,80),ch];})()"
    }

    private suspend fun probe(view: WebView, script: String): Probe? = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(script) { raw ->
            val parsed = try {
                val array = JSONArray(raw)
                Probe(
                    state = array.optString(0),
                    textLength = array.optInt(1),
                    ready = array.optBoolean(2),
                    title = array.optString(3),
                    challenge = array.optBoolean(4),
                )
            } catch (e: Exception) {
                null
            }
            if (cont.isActive) cont.resume(parsed)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderOnMain(
        context: Context,
        url: String,
        readySelector: String?,
        timeoutMs: Long,
    ): FetchedPage {
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

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (request == null || request.isForMainFrame) return null
                    return if (isNoise(request)) emptyResponse() else null
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

            val script = probeScript(readySelector)
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var lastLength = -1
            var stablePolls = 0
            var sawContent = false
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

                val p = probe(view, script)
                if (p != null && p.textLength > 0 && !p.challenge && !CHALLENGE_TITLE.containsMatchIn(p.title)) {
                    sawContent = true
                    if (p.textLength == lastLength) stablePolls++ else {
                        stablePolls = 0
                        lastLength = p.textLength
                    }
                    val done = when {
                        // The content element has its text and nothing changes any more.
                        p.ready && pageLoaded && stablePolls >= READY_STABLE_POLLS -> true
                        p.ready && stablePolls >= READY_STABLE_POLLS_NO_LOAD_EVENT -> true
                        // No selector hit (unknown site): wait for load + a quiet DOM, like before.
                        pageLoaded && stablePolls >= STABLE_POLLS -> true
                        else -> false
                    }
                    if (done) break
                } else {
                    stablePolls = 0
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    if (sawContent) break // slow SPA: take what is there, the parser decides
                    throw NovelImportException(
                        "The site's browser check was not passed (HTTP 403).",
                        NovelImportException.Kind.BOT_BLOCK,
                        403,
                    )
                }
            }
            // The DOM is copied out once, at the end, not on every poll.
            val html = readHtml(view).orEmpty()
            if (html.isEmpty() || looksLikeChallenge(html)) {
                throw NovelImportException(
                    "The site's browser check was not passed (HTTP 403).",
                    NovelImportException.Kind.BOT_BLOCK,
                    403,
                )
            }
            lastUserAgent = view.settings.userAgentString
            return FetchedPage(html, view.url?.takeIf { it.startsWith("http") } ?: url)
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
