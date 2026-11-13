package com.aryan.reader.webimport

import android.webkit.CookieManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class FetchResult(
    val bytes: ByteArray,
    val contentType: String?,
    val finalUrl: String,
)

/** A non-2xx answer. Kept as its own type so callers can tell a CDN hiccup from a real refusal. */
internal class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** Minimal blocking-IO HTTP GET used by the web importers. Cancellable between chunks. */
internal object HttpFetcher {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_REDIRECTS = 10

    private fun sameOrigin(a: String, b: String): Boolean = try {
        val x = URL(a)
        val y = URL(b)
        x.protocol == y.protocol && x.host == y.host && x.port == y.port
    } catch (e: Exception) {
        false
    }

    /**
     * @param allowUrl called for the first URL and for every redirect hop; throw to refuse it.
     * @param headers request headers; they replace the defaults of the same name.
     * @param browserSession share cookies with the in-app browser's WebView (Readest's
     *   `fetch_web_browser_resource`): the stored cookies for each hop are sent, and `Set-Cookie`
     *   answers are written back. A challenge or login passed once in the browser therefore
     *   carries over to the import.
     */
    suspend fun fetch(
        url: String,
        maxBytes: Long = 80L * 1024 * 1024,
        timeoutMs: Int = 20_000,
        accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        headers: Map<String, String> = emptyMap(),
        browserSession: Boolean = false,
        allowUrl: (String) -> Unit = {},
    ): FetchResult {
        var current = url
        val requestHeaders = LinkedHashMap(headers)
        for (hop in 0..MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            allowUrl(current)
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,de;q=0.8")
                for ((name, value) in requestHeaders) connection.setRequestProperty(name, value)
                if (browserSession) {
                    val cookies = try {
                        CookieManager.getInstance().getCookie(current)
                    } catch (e: Exception) {
                        null // no WebView on this device: continue without a session
                    }
                    if (!cookies.isNullOrEmpty()) connection.setRequestProperty("Cookie", cookies)
                }
                val code = connection.responseCode
                if (browserSession) storeCookies(current, connection)
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect without target")
                    val next = URL(URL(current), location).toString()
                    if (current.startsWith("https://", ignoreCase = true) && !next.startsWith("https://", ignoreCase = true)) {
                        throw IOException("Insecure redirect")
                    }
                    // A cross-origin hop must not learn which page led to it.
                    if (!sameOrigin(current, next)) requestHeaders.keys.removeAll { it.equals("referer", ignoreCase = true) }
                    current = next
                    continue
                }
                if (code !in 200..299) throw HttpStatusException(code)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                connection.inputStream.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IOException("File is too large")
                        output.write(buffer, 0, read)
                    }
                }
                return FetchResult(output.toByteArray(), connection.contentType, current)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun storeCookies(url: String, connection: HttpURLConnection) {
        try {
            val manager = CookieManager.getInstance()
            for ((name, values) in connection.headerFields) {
                if (name == null || !name.equals("Set-Cookie", ignoreCase = true)) continue
                // Chromium's cookie store rejects a Domain that does not cover the URL's host.
                for (value in values) manager.setCookie(url, value)
            }
            manager.flush()
        } catch (e: Exception) {
            // Cookies are a convenience for the session; never fail the fetch over them.
        }
    }
}
