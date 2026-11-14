package com.aryan.reader.webimport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

internal class NovelImportException(
    message: String,
    val kind: Kind,
    val httpStatus: Int? = null,
) : IOException(message) {
    enum class Kind {
        /** The origin was briefly unreachable (timeout, 502/504, Cloudflare 52x): worth retrying. */
        TRANSIENT,
        BOT_BLOCK,
        FETCH_FAILED,
        PARSE_FAILED,
        BLOCKED_URL,
    }
}

internal class FetchedPage(val html: String, val finalUrl: String)

/** Injectable page fetcher (the default decodes the body with [HtmlDecoder]); handy for tests. */
internal typealias PageFetcher = suspend (url: String) -> FetchedPage

internal class NovelBook(
    val file: File,
    val title: String,
    val author: String,
    val chapterCount: Int,
    /** Chapters that could not be downloaded and were replaced by a placeholder page. */
    val failures: Int,
)

/**
 * Web-novel import, structured like Readest's `services/novel/novelImport.ts`:
 *
 *  1. [fetchNovelToc]: fetch the TOC page, parse the chapter list, backfill weak metadata from the
 *     first chapter. The UI shows this as a preview so nobody starts hundreds of requests on a
 *     misfiring heuristic.
 *  2. [downloadNovel]: a small worker pool fetches the chapters, failed chapters become placeholder
 *     pages instead of aborting the whole download, and everything is assembled into one EPUB.
 */
internal object NovelImporter {
    /** Hard cap: a runaway TOC heuristic must not schedule tens of thousands of requests. */
    const val MAX_NOVEL_CHAPTERS = 2000

    private const val PAGE_FETCH_TIMEOUT_MS = 15_000
    private const val MAX_PAGE_BYTES = 8L * 1024 * 1024
    private const val MAX_COVER_BYTES = 5L * 1024 * 1024
    private const val CHAPTER_CONCURRENCY = 4

    /** Below this many characters the "chapter" is a JS-challenge stub or an extraction miss. */
    private const val CHAPTER_QUALITY_FLOOR = 100

    /** Extra attempts for a briefly unreachable origin; linear backoff (1 s, then 2 s). */
    private const val TRANSIENT_RETRIES = 2
    private const val TRANSIENT_BACKOFF_MS = 1000L

    // ------------------------------------------------------------------ fetching

    private fun statusError(code: Int): NovelImportException = when {
        code == 502 || code == 504 || code in 520..527 -> NovelImportException(
            "The site is temporarily unavailable (HTTP $code). Try again in a moment.",
            NovelImportException.Kind.TRANSIENT,
            code,
        )
        code == 401 || code == 403 || code == 429 || code == 503 -> NovelImportException(
            "The site blocked the request (HTTP $code). Try again later.",
            NovelImportException.Kind.BOT_BLOCK,
            code,
        )
        else -> NovelImportException("Could not fetch the page (HTTP $code).", NovelImportException.Kind.FETCH_FAILED, code)
    }

    /**
     * Fetches like Readest: full Chrome navigation headers plus the in-app browser's cookie
     * session. A bare GET is what sites answer with HTTP 403; this is what they accept.
     * [sessionUserAgent] is the WebView UA after the user passed a challenge in the browser.
     */
    private fun browserFetcher(sessionUserAgent: String?): PageFetcher = { url ->
        val result = try {
            HttpFetcher.fetch(
                url,
                maxBytes = MAX_PAGE_BYTES,
                timeoutMs = PAGE_FETCH_TIMEOUT_MS,
                headers = BrowserHeaders.pageNavigate(sessionUserAgent),
                browserSession = true,
                allowUrl = NovelUrlGuard::assertAllowed,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: NovelImportException) {
            throw e
        } catch (e: HttpStatusException) {
            throw statusError(e.code)
        } catch (e: SocketTimeoutException) {
            throw NovelImportException(
                "The site took too long to respond. Try again in a moment.",
                NovelImportException.Kind.TRANSIENT,
            )
        } catch (e: IOException) {
            throw NovelImportException(e.message ?: "Could not fetch the page.", NovelImportException.Kind.FETCH_FAILED)
        }
        FetchedPage(HtmlDecoder.decode(result.bytes, result.contentType), result.finalUrl)
    }

    /**
     * Wraps a fetcher with a backoff retry so a briefly unreachable origin doesn't fail the whole
     * import. A site behind Cloudflare can 52x or hang on most requests for minutes at a time while
     * staying perfectly readable in between.
     */
    private fun withTransientRetry(fetch: PageFetcher): PageFetcher = { url -> fetchWithRetry(fetch, url) }

    private suspend fun fetchWithRetry(fetch: PageFetcher, url: String): FetchedPage {
        NovelUrlGuard.assertAllowed(url)
        var attempt = 0
        while (true) {
            try {
                return fetch(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NovelImportException) {
                if (e.kind != NovelImportException.Kind.TRANSIENT || attempt >= TRANSIENT_RETRIES) throw e
            }
            attempt++
            delay(TRANSIENT_BACKOFF_MS * attempt)
        }
    }

    // ------------------------------------------------------------------ step 1: table of contents

    /** Anti-bot answers (401/403/429/503) that a real browser render may get past. */
    private fun isBlocked(e: Exception): Boolean =
        e is NovelImportException && e.kind == NovelImportException.Kind.BOT_BLOCK

    /** `webnovel.com/book/<slug>_<id>` lists its chapters on a separate `/catalog` view. */
    private fun catalogFallbackUrl(url: String): String? = try {
        val parsed = URL(url)
        val path = parsed.path.trimEnd('/')
        if (parsed.host.endsWith("webnovel.com") && Regex("^/book/[^/]+$").matches(path)) {
            "${parsed.protocol}://${parsed.authority}$path/catalog"
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Fetches (or takes) a TOC page and parses it into a capped, ordered chapter list.
     *
     * Like Readest, a plain request comes first and a real browser render ([renderer]) takes over
     * when the site blocks it (HTTP 403 from Cloudflare & co.) or only serves an empty JavaScript
     * shell with no chapter list in it.
     */
    suspend fun fetchNovelToc(
        url: String,
        /** A page already rendered by the in-app browser (after a login or challenge): skips the fetch. */
        preloaded: FetchedPage? = null,
        sessionUserAgent: String? = null,
        fetcher: PageFetcher = browserFetcher(sessionUserAgent),
        renderer: PageRenderer? = null,
    ): NovelToc =
        withContext(Dispatchers.IO) {
            val fetch = withTransientRetry(fetcher)
            var rendered = false

            suspend fun render(target: String): FetchedPage {
                NovelUrlGuard.assertAllowed(target)
                rendered = true
                return renderer!!(target)
            }

            var page = preloaded ?: try {
                fetch(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (renderer != null && isBlocked(e)) render(url) else throw e
            }
            NovelUrlGuard.assertAllowed(page.finalUrl)
            var toc = NovelTocParser.parseChapterList(page.html, page.finalUrl)
            // Plain HTML without a chapter list: the list is probably drawn by JavaScript.
            if (toc == null && renderer != null && !rendered && preloaded == null) {
                page = render(page.finalUrl)
                NovelUrlGuard.assertAllowed(page.finalUrl)
                toc = NovelTocParser.parseChapterList(page.html, page.finalUrl)
            }
            if (toc == null && renderer != null) {
                val catalogUrl = catalogFallbackUrl(page.finalUrl)
                if (catalogUrl != null) {
                    page = render(catalogUrl)
                    NovelUrlGuard.assertAllowed(page.finalUrl)
                    toc = NovelTocParser.parseChapterList(page.html, page.finalUrl)
                }
            }
            val parsed = toc ?: throw NovelImportException(
                "No chapter list found on this page. Open the novel's table-of-contents page and try again.",
                NovelImportException.Kind.PARSE_FAILED,
            )
            // Metadata backfill needs the same render fallback as the TOC itself.
            val metadataFetch: PageFetcher = if (rendered && renderer != null) {
                val plain = fetch
                val withRender: PageFetcher = { target ->
                    try {
                        plain(target)
                    } catch (e: NovelImportException) {
                        if (isBlocked(e)) renderer(target) else throw e
                    }
                }
                withRender
            } else {
                fetch
            }
            backfillMetadata(
                parsed.copy(chapters = parsed.chapters.take(MAX_NOVEL_CHAPTERS), rendered = rendered),
                metadataFetch,
            )
        }

    /**
     * A chapter-index page is often just a list of links, with the work's real title and author
     * only on the chapter pages. When the index could offer no more than a page-level guess, ask
     * the first chapter instead. Best-effort: the guess stands if that fetch or parse comes up empty.
     */
    private suspend fun backfillMetadata(toc: NovelToc, fetch: PageFetcher): NovelToc {
        val first = toc.chapters.firstOrNull() ?: return toc
        if (!toc.weakTitle && !toc.weakAuthor) return toc
        val found = try {
            NovelTocParser.parseWorkMetadata(fetch(first.url).html, first.url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return toc
        }
        return toc.copy(
            title = if (toc.weakTitle && !found.title.isNullOrEmpty()) found.title else toc.title,
            author = if (toc.weakAuthor && !found.author.isNullOrEmpty()) found.author else toc.author,
        )
    }

    // ------------------------------------------------------------------ chapter extraction

    // Selectors novel sites use for the chapter body. Tried BEFORE the generic reader-mode
    // heuristic: a bare `<div id="content">` full of `<br>`-separated lines scores poorly there,
    // and the heuristic can end up on <body> (navigation included) instead of the text.
    private val CHAPTER_CONTENT_SELECTORS = listOf(
        "#chaptercontent",
        "#chapter-content",
        ".chapter-content",
        "#booktxt",
        "#content",
        ".read-content",
        "article",
        ".content",
    )

    private fun normalizeTitle(s: String): String = s.replace(Regex("\\s+"), " ").trim().lowercase()

    /**
     * Extracts one chapter's prose as XHTML, or `null` when the page holds no real text. A leading
     * heading that repeats the TOC title is dropped; [EpubWriter] prints the canonical heading.
     */
    fun extractChapterHtml(pageHtml: String, chapterTitle: String, pageUrl: String): String? {
        val doc = Jsoup.parse(pageHtml, pageUrl)
        val page = ContentExtractor.extractBySelectors(doc, CHAPTER_CONTENT_SELECTORS, CHAPTER_QUALITY_FLOOR)
            ?: ContentExtractor.extract(doc).takeIf { it.textLength >= CHAPTER_QUALITY_FLOOR }
            ?: return null
        val fragment = Jsoup.parseBodyFragment(page.bodyXhtml)
        val heading = fragment.body().selectFirst("h1, h2, h3")
        if (heading != null && normalizeTitle(heading.text()) == normalizeTitle(chapterTitle)) heading.remove()
        fragment.outputSettings(ContentExtractor.xhtmlSettings())
        return fragment.body().html()
    }

    /**
     * @param renderer browser render used when the plain fetch is blocked (HTTP 403 …) or returns a
     *   JavaScript shell without chapter text.
     * @param renderFirst skip the plain fetch: an earlier chapter already proved the site blocks it.
     *   Set to `true` by this function the first time a block is seen.
     */
    private suspend fun loadChapter(
        link: NovelChapterLink,
        fetch: PageFetcher,
        renderer: PageRenderer?,
        renderFirst: AtomicBoolean,
    ): String? {
        return try {
            NovelUrlGuard.assertAllowed(link.url)
            var rendered = false
            var page = if (renderer != null && renderFirst.get()) {
                rendered = true
                renderer(link.url)
            } else {
                try {
                    fetch(link.url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    when {
                        renderer != null && isBlocked(e) -> {
                            renderFirst.set(true)
                            rendered = true
                            renderer(link.url)
                        }
                        // A transient failure was already retried with backoff: don't double up.
                        // Anything else gets one more immediate try.
                        e is NovelImportException && e.kind == NovelImportException.Kind.TRANSIENT -> throw e
                        else -> fetch(link.url)
                    }
                }
            }
            var html = extractChapterHtml(page.html, link.title, page.finalUrl)
            // Server-rendered shell without prose: the text is drawn by JavaScript.
            if (html == null && renderer != null && !rendered) {
                NovelUrlGuard.assertAllowed(page.finalUrl)
                page = renderer(page.finalUrl)
                html = extractChapterHtml(page.html, link.title, page.finalUrl)
            }
            html
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun placeholderBody(link: NovelChapterLink, text: String): String =
        "<p>${escapeXml(text)}</p>\n<p><a href=\"${escapeXml(link.url)}\">${escapeXml(link.url)}</a></p>"

    // ------------------------------------------------------------------ cover + language

    private fun sniffImageMime(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
            else -> null
        }
    }

    private suspend fun fetchCover(coverUrl: String, referer: String, sessionUserAgent: String?): EpubCover? = try {
        val result = HttpFetcher.fetch(
            coverUrl,
            maxBytes = MAX_COVER_BYTES,
            timeoutMs = PAGE_FETCH_TIMEOUT_MS,
            accept = "image/avif,image/webp,image/png,image/jpeg,image/*,*/*;q=0.8",
            headers = BrowserHeaders.image(referer, sessionUserAgent),
            browserSession = true,
            allowUrl = NovelUrlGuard::assertAllowed,
        )
        sniffImageMime(result.bytes)?.let { EpubCover(result.bytes, it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null // the cover is optional
    }

    /** Script-based guess from real prose; Latin-script novels fall back to the page's `lang`. */
    private fun detectLanguage(sample: String, htmlLang: String?): String {
        var kana = 0
        var hangul = 0
        var han = 0
        var letters = 0
        for (ch in sample) {
            if (!ch.isLetter()) continue
            letters++
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.HAN -> han++
                else -> Unit
            }
        }
        val fallback = htmlLang?.substringBefore('-')?.lowercase()?.takeIf { it.length in 2..3 } ?: "en"
        if (letters == 0) return fallback
        return when {
            kana * 10 >= letters -> "ja"
            hangul * 5 >= letters -> "ko"
            han * 5 >= letters -> "zh"
            else -> fallback
        }
    }

    private fun stableIdentifier(key: String): String = "urn:uuid:" + UUID.nameUUIDFromBytes(key.toByteArray())

    // ------------------------------------------------------------------ step 2: download

    /**
     * Downloads every chapter of [toc] and assembles a single EPUB in [outputDir].
     *
     * Individual chapter failures become placeholder pages (with the source URL) rather than
     * aborting a long download; [NovelBook.failures] reports the count.
     *
     * @param identityKey stable input for the EPUB identifier. Defaults to [sourceUrl]; a chapter
     *   subset should pass its chapter URLs as well so each selection stays a distinct book.
     * @param placeholderText text shown on a chapter that could not be downloaded (localized by the caller).
     */
    suspend fun downloadNovel(
        toc: NovelToc,
        sourceUrl: String,
        outputDir: File,
        placeholderText: String,
        identityKey: String = sourceUrl,
        sessionUserAgent: String? = null,
        fetcher: PageFetcher = browserFetcher(sessionUserAgent),
        renderer: PageRenderer? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): NovelBook = withContext(Dispatchers.IO) {
        val fetch = withTransientRetry(fetcher)
        val links = toc.chapters.take(MAX_NOVEL_CHAPTERS)
        val total = links.size
        require(total > 0) { "No chapters to download" }

        val results = arrayOfNulls<EpubChapter>(total)
        val loaded = BooleanArray(total)
        val cursor = AtomicInteger(0)
        val done = AtomicInteger(0)
        val failures = AtomicInteger(0)
        // The TOC already needed a browser render, so every chapter will too: don't waste 403s.
        val renderFirst = AtomicBoolean(toc.rendered)
        onProgress(0, total)

        coroutineScope {
            repeat(minOf(CHAPTER_CONCURRENCY, total)) {
                launch {
                    while (true) {
                        ensureActive()
                        val index = cursor.getAndIncrement()
                        if (index >= total) return@launch
                        val link = links[index]
                        val body = loadChapter(link, fetch, renderer, renderFirst)
                        if (body != null) {
                            loaded[index] = true
                            results[index] = EpubChapter(link.title, body)
                        } else {
                            failures.incrementAndGet()
                            results[index] = EpubChapter(link.title, placeholderBody(link, placeholderText))
                        }
                        onProgress(done.incrementAndGet(), total)
                    }
                }
            }
        }
        val chapters = results.map { it!! }

        val siteName = try {
            URL(sourceUrl).host.removePrefix("www.")
        } catch (e: Exception) {
            ""
        }
        val cover = toc.coverUrl?.let { fetchCover(it, sourceUrl, sessionUserAgent) }
            ?: CoverGenerator.generate(toc.title, toc.author, siteName)

        // Sample real prose (not placeholder text) for language detection.
        val sample = StringBuilder(toc.title).append(' ')
        for (i in chapters.indices) {
            if (!loaded[i]) continue
            sample.append(Jsoup.parse(chapters[i].bodyXhtml).text()).append(' ')
            if (sample.length >= 2048) break
        }
        val language = detectLanguage(sample.take(2048).toString(), toc.htmlLang)

        ensureActive()
        val target = File(outputDir, WebImporters.safeFileName(toc.title) + ".epub")
        EpubWriter.write(
            target = target,
            title = toc.title,
            author = toc.author.ifBlank { "Web novel" },
            language = language,
            identifier = stableIdentifier(identityKey),
            chapters = chapters,
            cover = cover,
        )
        NovelBook(target, toc.title, toc.author, total, failures.get())
    }
}
