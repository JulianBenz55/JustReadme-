package com.aryan.reader.webimport

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

/** Minimal blocking-IO HTTP GET used by the web importers. Cancellable between chunks. */
internal object HttpFetcher {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_REDIRECTS = 6

    suspend fun fetch(
        url: String,
        maxBytes: Long = 80L * 1024 * 1024,
        timeoutMs: Int = 20_000,
    ): FetchResult {
        var current = url
        for (attempt in 0..MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,de;q=0.8")
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect without target")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code")
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
}
