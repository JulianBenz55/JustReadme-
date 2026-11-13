package com.aryan.reader.webimport

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Web imports that need no server of our own: a page or file by URL or an RSS/Atom feed. Each importer
 * returns a file that the library then imports as a book. Webnovel imports live in [NovelImporter].
 */
internal object WebImporters {
    private const val MAX_FEED_ITEMS = 100
    private const val CHAPTER_CONCURRENCY = 4

    fun normalizeUrl(input: String): String {
        var value = input.trim()
        require(value.isNotEmpty()) { "Please enter a web address" }
        if (!value.contains("://")) value = "https://$value"
        val parsed = try {
            URL(value)
        } catch (e: Exception) {
            throw IllegalArgumentException("This is not a valid web address")
        }
        require(parsed.protocol == "http" || parsed.protocol == "https") { "Only http(s) addresses are supported" }
        require(parsed.host.isNotBlank()) { "This is not a valid web address" }
        return value
    }

    internal fun outputDir(context: Context): File = File(context.cacheDir, "web_import").apply { mkdirs() }

    internal fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        return cleaned.take(80).ifBlank { "web-import" }
    }

    private fun nameFromUrl(url: String, extension: String): String {
        val last = try {
            URLDecoder.decode(URL(url).path.substringAfterLast('/'), "UTF-8")
        } catch (e: Exception) {
            ""
        }
        val base = last.substringBeforeLast('.', last)
        return safeFileName(base.ifBlank { URL(url).host }) + "." + extension
    }

    private fun parseHtml(result: FetchResult): Document =
        Jsoup.parse(ByteArrayInputStream(result.bytes), null, result.finalUrl)

    // ---------------------------------------------------------------- web page / file

    /** "From web browser": a direct PDF/EPUB link is downloaded, any other page becomes a one-chapter EPUB. */
    suspend fun importWebPage(context: Context, url: String): File = withContext(Dispatchers.IO) {
        val result = HttpFetcher.fetch(url)
        val type = result.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val path = URL(result.finalUrl).path.lowercase()
        val dir = outputDir(context)
        when {
            type == "application/pdf" || path.endsWith(".pdf") -> {
                File(dir, nameFromUrl(result.finalUrl, "pdf")).also { it.writeBytes(result.bytes) }
            }
            type == "application/epub+zip" || path.endsWith(".epub") -> {
                File(dir, nameFromUrl(result.finalUrl, "epub")).also { it.writeBytes(result.bytes) }
            }
            else -> buildPageEpub(dir, parseHtml(result), result.finalUrl)
        }
    }

    /** [bookTitle] replaces the page title as the book's name (used for numbered clips like "Overgeared 3"). */
    private fun buildPageEpub(dir: File, doc: Document, pageUrl: String, bookTitle: String? = null): File {
        val page = ContentExtractor.extract(doc)
        if (page.textLength < 200) {
            throw IOException("No readable text found (the page may load its content with JavaScript)")
        }
        val title = bookTitle?.trim()?.takeIf { it.isNotEmpty() } ?: page.title
        val target = File(dir, safeFileName(title) + ".epub")
        EpubWriter.write(
            target = target,
            title = title,
            author = URL(pageUrl).host,
            language = doc.selectFirst("html")?.attr("lang")?.takeIf { it.isNotBlank() } ?: "en",
            identifier = "urn:uuid:" + UUID.nameUUIDFromBytes(pageUrl.toByteArray()),
            chapters = listOf(EpubChapter(page.title, page.bodyXhtml)),
        )
        return target
    }

    /** "Clip page": turns the HTML currently shown in the in-app browser (login and scripts included) into an EPUB. */
    suspend fun clipHtml(
        context: Context,
        html: String,
        pageUrl: String,
        bookTitle: String? = null,
    ): File = withContext(Dispatchers.Default) {
        buildPageEpub(outputDir(context), Jsoup.parse(html, pageUrl), pageUrl, bookTitle)
    }

    /** Downloads a file the in-app browser was asked to save, using the browser's cookies so logins keep working. */
    suspend fun downloadFile(
        context: Context,
        url: String,
        fileName: String,
        cookies: String?,
        userAgent: String?,
    ): File = withContext(Dispatchers.IO) {
        val target = File(outputDir(context), safeFileName(fileName.substringBeforeLast('.')) + "." +
            fileName.substringAfterLast('.', "bin").take(8))
        val connection = URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            if (!userAgent.isNullOrBlank()) connection.setRequestProperty("User-Agent", userAgent)
            if (!cookies.isNullOrBlank()) connection.setRequestProperty("Cookie", cookies)
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
        target
    }

    // ---------------------------------------------------------------- feeds

    private fun child(item: Element, vararg names: String): Element? {
        for (name in names) {
            val match = item.children().firstOrNull { it.tagName().equals(name, ignoreCase = true) }
            if (match != null) return match
        }
        return null
    }

    private fun contentOf(element: Element?): String {
        if (element == null) return ""
        return if (element.children().isEmpty()) element.text() else element.html()
    }

    /** "From feed URL": an RSS/Atom feed (or a page that links to one) becomes an EPUB with one chapter per entry. */
    suspend fun importFeed(context: Context, url: String): File = withContext(Dispatchers.IO) {
        var page = HttpFetcher.fetch(url)
        var doc = Jsoup.parse(String(page.bytes, Charsets.UTF_8), page.finalUrl, Parser.xmlParser())
        if (doc.selectFirst("rss, feed") == null) {
            val html = parseHtml(page)
            val alternate = html.select("link[rel=alternate]").firstOrNull { link ->
                val type = link.attr("type").lowercase()
                type.contains("rss") || type.contains("atom")
            }?.absUrl("href").orEmpty()
            if (alternate.isBlank()) throw IOException("No RSS or Atom feed found at this address")
            page = HttpFetcher.fetch(alternate)
            doc = Jsoup.parse(String(page.bytes, Charsets.UTF_8), page.finalUrl, Parser.xmlParser())
            if (doc.selectFirst("rss, feed") == null) throw IOException("The linked address is not a valid feed")
        }
        val host = URL(page.finalUrl).host
        val feedTitle = doc.selectFirst("channel > title, feed > title")?.text()?.trim().orEmpty().ifBlank { host }
        val items = doc.select("item, entry").take(MAX_FEED_ITEMS)
        if (items.isEmpty()) throw IOException("The feed has no entries")

        val total = items.size
        val semaphore = Semaphore(CHAPTER_CONCURRENCY)
        val chapters = coroutineScope {
            items.mapIndexed { index, item ->
                async {
                    semaphore.withPermit {
                        buildFeedChapter(item, index)
                    }
                }
            }.awaitAll()
        }
        val target = File(outputDir(context), safeFileName(feedTitle) + ".epub")
        EpubWriter.write(
            target = target,
            title = feedTitle,
            author = host,
            language = doc.selectFirst("channel > language")?.text()?.trim()?.takeIf { it.length in 2..5 } ?: "en",
            identifier = "urn:uuid:" + UUID.nameUUIDFromBytes((page.finalUrl + total).toByteArray()),
            chapters = chapters,
        )
        target
    }

    private suspend fun buildFeedChapter(item: Element, index: Int): EpubChapter {
        val title = child(item, "title")?.text()?.trim().orEmpty().ifBlank { "Entry ${index + 1}" }
        val link = item.selectFirst("link[href]")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: child(item, "link")?.text()?.trim().orEmpty()
        val date = child(item, "pubDate", "published", "updated", "dc:date")?.text()?.trim().orEmpty()
        val raw = contentOf(child(item, "content:encoded", "content", "description", "summary"))
        var body = ContentExtractor.cleanToXhtml(raw)
        val bodyLength = Jsoup.parse(body).text().length
        // Summary-only feeds: try to load the full article, keep the summary when that fails.
        if (bodyLength < 400 && link.startsWith("http") && index < 40) {
            try {
                val page = HttpFetcher.fetch(link, maxBytes = 8L * 1024 * 1024)
                val extracted = ContentExtractor.extract(parseHtml(page))
                if (extracted.textLength > bodyLength) body = extracted.bodyXhtml
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // keep the feed's own text
            }
        }
        val meta = listOf(date, link).filter { it.isNotBlank() }.joinToString("  ·  ")
        val header = if (meta.isEmpty()) "" else "<p><em>${escapeXml(meta)}</em></p>\n"
        return EpubChapter(title, header + body)
    }
}
