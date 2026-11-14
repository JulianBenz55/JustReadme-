package com.aryan.reader.webimport

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URL
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.max

internal class NovelChapterLink(val title: String, val url: String)

/**
 * A parsed table-of-contents page.
 *
 * [weakTitle] / [weakAuthor] mark values that were only a page-level guess (the `<title>` tag, a
 * site-wide `meta[name=author]`) instead of real per-work metadata. A chapter-index page is often
 * just a list of links, so a chapter page may know better (see [NovelImporter.fetchNovelToc]).
 */
internal data class NovelToc(
    val title: String,
    val author: String,
    val coverUrl: String?,
    val chapters: List<NovelChapterLink>,
    val weakTitle: Boolean,
    val weakAuthor: Boolean,
    /** `<html lang>` of the TOC page, used as a language fallback for Latin-script novels. */
    val htmlLang: String?,
    /** The site only answered to a real browser render; chapter downloads should render first. */
    val rendered: Boolean = false,
)

internal class WorkMetadata(val title: String?, val author: String?)

/**
 * Heuristic chapter-list extraction for web-novel table-of-contents pages
 * (Kotlin port of Readest's `services/novel/chapterList.ts`).
 *
 * Works on the RAW page HTML parsed by Jsoup; nothing is executed or loaded.
 */
internal object NovelTocParser {
    /** Fewer links than this is not a chapter list. */
    private const val MIN_CHAPTER_LINKS = 5

    /**
     * The chosen container must hold at least this fraction of all chapter-like links on the page.
     * "Latest chapters" sidebars (a handful of links) fail the bar; the full list passes; when the
     * full list is split across siblings the common parent wins instead.
     */
    private const val CONTAINER_COVERAGE = 0.8

    private val CHAPTER_TEXT_PATTERNS = listOf(
        // Traditional forms too (節, 話, 萬, 兩); some sites number chapters as 页/頁.
        Regex("第[\\s\\u3000]*[0-9０-９零〇一二三四五六七八九十百千万萬两兩]+[\\s\\u3000]*[章节節回卷集部篇话話页頁]"),
        Regex("\\bchapter\\s*[0-9]+", RegexOption.IGNORE_CASE),
        Regex("\\bch\\.?\\s*[0-9]+\\b", RegexOption.IGNORE_CASE),
        Regex("\\bepisode\\s*[0-9]+", RegexOption.IGNORE_CASE),
        Regex("^[0-9]+\\s*[、.．:：\\-]"),
    )

    fun isChapterText(text: String): Boolean = CHAPTER_TEXT_PATTERNS.any { it.containsMatchIn(text) }

    private val DIGITS = Regex("[0-9]+")

    private class LinkEntry(
        val el: Element,
        val text: String,
        val url: String,
        val path: String,
        val template: String,
    )

    private fun parse(html: String, pageUrl: String): Document = Jsoup.parse(html, pageUrl)

    // ------------------------------------------------------------------ link collection

    /** Collapse digit runs so `/book/1/482.html` and `/book/1/7.html` compare equal. */
    private fun hrefTemplate(url: URL): String =
        "${url.protocol}://${url.authority}" + url.path.replace(DIGITS, "#")

    private fun collectLinks(doc: Document, pageUrl: String): List<LinkEntry> {
        val self = pageUrl.substringBefore('#')
        val entries = ArrayList<LinkEntry>()
        for (el in doc.select("a[href]")) {
            val raw = el.attr("href").trim()
            if (raw.isEmpty() || raw.startsWith("#")) continue
            val abs = el.absUrl("href")
            if (abs.isEmpty()) continue
            val parsed = try {
                URL(abs)
            } catch (e: Exception) {
                continue
            }
            if (parsed.protocol != "http" && parsed.protocol != "https") continue
            val text = el.text().trim()
            if (text.isEmpty()) continue
            val url = abs.substringBefore('#')
            if (url == self) continue
            entries.add(LinkEntry(el, text, url, parsed.path, hrefTemplate(parsed)))
        }
        return entries
    }

    private fun majorityTemplate(entries: List<LinkEntry>): Pair<String, Int>? {
        val counts = LinkedHashMap<String, Int>()
        for (entry in entries) counts[entry.template] = (counts[entry.template] ?: 0) + 1
        var best: Pair<String, Int>? = null
        for ((template, count) in counts) {
            if (best == null || count > best.second) best = template to count
        }
        return best
    }

    /**
     * The deepest element containing at least [CONTAINER_COVERAGE] of the chapter-like links:
     * tight enough to exclude sidebars, wide enough to span a list split across columns.
     */
    private fun findChapterContainer(candidates: List<LinkEntry>): Element? {
        val counts = IdentityHashMap<Element, Int>()
        for (entry in candidates) {
            var node: Element? = entry.el.parent()
            while (node != null && node !is Document) {
                counts[node] = (counts[node] ?: 0) + 1
                node = node.parent()
            }
        }
        val threshold = max(MIN_CHAPTER_LINKS, ceil(CONTAINER_COVERAGE * candidates.size).toInt())
        var container: Element? = null
        var containerDepth = -1
        for ((el, count) in counts) {
            if (count < threshold) continue
            val depth = el.parents().size
            if (depth > containerDepth) {
                container = el
                containerDepth = depth
            }
        }
        return container
    }

    private fun isInside(el: Element, container: Element): Boolean {
        var node: Element? = el
        while (node != null) {
            if (node === container) return true
            node = node.parent()
        }
        return false
    }

    // ------------------------------------------------------------------ ordering

    private val CN_DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '兩' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val CN_UNITS = mapOf('十' to 10, '百' to 100, '千' to 1000)

    /** 第一百零一 → 101, 第一萬零二 → 10002. */
    private fun parseChineseNumber(numeral: String): Int {
        var total = 0
        var section = 0
        var digit = 0
        for (ch in numeral) {
            val d = CN_DIGITS[ch]
            val unit = CN_UNITS[ch]
            when {
                d != null -> digit = d
                unit != null -> {
                    section += (if (digit == 0) 1 else digit) * unit
                    digit = 0
                }
                ch == '万' || ch == '萬' -> {
                    total += (section + digit) * 10000
                    section = 0
                    digit = 0
                }
            }
        }
        return total + section + digit
    }

    private val CN_CHAPTER_NUMBER = Regex("第[\\s\\u3000]*([0-9]+|[零〇一二三四五六七八九十百千万萬两兩]+)")
    private val LATIN_CHAPTER_NUMBER = Regex("(?:\\bchapter|\\bch\\.?|\\bepisode)\\s*([0-9]+)", RegexOption.IGNORE_CASE)
    private val LEADING_NUMBER = Regex("^([0-9]+)\\s*[、.．:：\\-]")
    private val LAST_PATH_DIGITS = Regex("([0-9]+)(?=[^0-9]*$)")

    /**
     * Chapter number in the title (the last 第N, arabic or Chinese, so 第一卷 第六章 reads 6), else
     * the last digit run in the URL path. Used only to detect newest-first ordering.
     */
    private fun chapterNumber(entry: LinkEntry): Int? {
        // Full-width digits (第１２頁) read as ASCII.
        val text = buildString {
            for (c in entry.text) append(if (c in '０'..'９') (c.code - 0xFEE0).toChar() else c)
        }
        val numeral = CN_CHAPTER_NUMBER.findAll(text).lastOrNull()?.groupValues?.get(1)
        if (numeral != null) {
            return if (numeral[0] in '0'..'9') numeral.toIntOrNull() else parseChineseNumber(numeral)
        }
        val numbered = LATIN_CHAPTER_NUMBER.find(text)?.groupValues?.get(1)
            ?: LEADING_NUMBER.find(text)?.groupValues?.get(1)
        if (numbered != null) return numbered.toIntOrNull()
        return LAST_PATH_DIGITS.find(entry.path)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Newest-first lists read backwards: reverse when numbers mostly descend. */
    private fun orderChapters(entries: List<LinkEntry>): List<LinkEntry> {
        var descending = 0
        var ascending = 0
        var prev: Int? = null
        for (entry in entries) {
            val num = chapterNumber(entry)
            val before = prev
            if (num != null && before != null) {
                if (num < before) descending++ else if (num > before) ascending++
            }
            if (num != null) prev = num
        }
        return if (descending > ascending) entries.asReversed() else entries
    }

    // ------------------------------------------------------------------ metadata

    private fun metaContent(doc: Document, selectors: List<String>): String? {
        for (selector in selectors) {
            val content = doc.selectFirst(selector)?.attr("content")?.trim()
            if (!content.isNullOrEmpty()) return content
        }
        return null
    }

    private val TITLE_TAG_SPLIT = Regex("\\s*[|_｜]\\s*|\\s+[-–—]\\s+")
    private val TITLE_TAG_SUFFIX = Regex("(最新章节列表|最新章节|章节列表|章节目录|全文阅读|无弹窗)+$")

    /** "斗破苍穹最新章节_笔趣阁" → "斗破苍穹"; "Title - Read Online" → "Title". */
    private fun cleanTitleTag(raw: String): String {
        val first = raw.split(TITLE_TAG_SPLIT).firstOrNull()?.trim().orEmpty().ifEmpty { raw.trim() }
        return first.replace(TITLE_TAG_SUFFIX, "").trim()
    }

    /** Meta tags that name the work itself, as opposed to the page or the site. */
    private val WORK_TITLE_META = listOf(
        "meta[property=\"og:novel:book_name\"]",
        "meta[property=\"og:title\"]",
        "meta[name=\"twitter:title\"]",
    )
    private val WORK_AUTHOR_META = listOf(
        "meta[property=\"og:novel:author\"]",
        "meta[property=\"books:author\"]",
    )

    private val AUTHOR_LABEL = Regex("(?:作者|著者)\\s*[:：]\\s*")

    /**
     * Field labels that sit right after the byline on the same line. The flattened text runs them
     * into the name with no separator ("作者：五月不行" immediately followed by "日期：2026-08-24"),
     * so the labels worth cutting at are named here.
     */
    private val NEXT_FIELD_LABEL = Regex("(?:日期|时间|更新|状态|字数|分类|类别|类型|标签|来源|最新)\\s*[:：]")
    private val AUTHOR_SEPARATOR = Regex("[\\s，,。；;、|/]")

    /** A byline name runs up to the first separator; whitespace included. */
    private fun cleanAuthorName(raw: String): String {
        val field = raw.trim().split(AUTHOR_SEPARATOR).firstOrNull().orEmpty()
        return field.split(NEXT_FIELD_LABEL).firstOrNull().orEmpty().take(32)
    }

    private fun collectTextNodes(el: Element, out: MutableList<String>) {
        for (child in el.childNodes()) {
            when (child) {
                is TextNode -> out.add(child.wholeText)
                is Element -> collectTextNodes(child, out)
                else -> Unit
            }
        }
    }

    /**
     * The writer's name from an on-page byline. Walks text nodes instead of matching the flattened
     * body text, so the name can be read out of the link that usually wraps it
     * (`作者：<a>NAME</a></span>日期：…`) without the neighbouring field bleeding into it.
     */
    private fun bylineAuthor(doc: Document): String? {
        val body = doc.body()
        val texts = ArrayList<String>()
        collectTextNodes(body, texts)
        var i = 0
        while (i < texts.size) {
            val text = texts[i]
            val label = AUTHOR_LABEL.find(text)
            if (label != null) {
                var name = cleanAuthorName(text.substring(label.range.last + 1))
                if (name.isEmpty()) {
                    // The label can end its own node, with the name in a following one. Only the
                    // first non-blank node can be the name: anything past it is the next field.
                    var ahead = i + 1
                    while (ahead < texts.size) {
                        val candidate = texts[ahead].trim()
                        if (candidate.isEmpty()) {
                            ahead++
                            continue
                        }
                        name = cleanAuthorName(candidate)
                        break
                    }
                    i = ahead
                }
                if (name.isNotEmpty()) return name
            }
            i++
        }
        return null
    }

    private class Metadata(
        val title: String,
        val author: String,
        val coverUrl: String?,
        val weakTitle: Boolean,
        val weakAuthor: Boolean,
    )

    private fun extractMetadata(doc: Document, pageUrl: String): Metadata {
        val workTitle = metaContent(doc, WORK_TITLE_META)
        val title = workTitle
            ?: cleanTitleTag(doc.title()).ifEmpty { null }
            ?: URL(pageUrl).host

        // `meta[name=author]` is routinely set site-wide (to the operator, not the writer), so it
        // counts as a guess rather than work metadata.
        val workAuthor = metaContent(doc, WORK_AUTHOR_META)
        val author = workAuthor
            ?: metaContent(doc, listOf("meta[name=\"author\"]"))
            ?: bylineAuthor(doc)
            ?: ""

        val rawCover = metaContent(doc, listOf("meta[property=\"og:image\"]"))
        var coverUrl: String? = null
        if (rawCover != null) {
            coverUrl = try {
                val resolved = URL(URL(pageUrl), rawCover)
                if (resolved.protocol == "http" || resolved.protocol == "https") resolved.toString() else null
            } catch (e: Exception) {
                null // unparseable cover URL: the cover is optional
            }
        }
        return Metadata(title, author, coverUrl, weakTitle = workTitle == null, weakAuthor = workAuthor == null)
    }

    /** Headings a chapter page uses for the work title and the writer's byline. */
    private val WORK_TITLE_HEADINGS = listOf("h1[class*=title]", "h2[class*=title]")
    private val WORK_AUTHOR_HEADINGS = listOf("[class*=byline]", "[rel=author]", "[class*=author]")

    private fun headingText(doc: Document, selectors: List<String>): String? {
        for (selector in selectors) {
            val text = doc.selectFirst(selector)?.text()?.trim()
            // A heading naming the chapter ("Chapter 7") describes this page, not the work.
            if (!text.isNullOrEmpty() && !isChapterText(text)) return text
        }
        return null
    }

    /**
     * Work title and author as advertised by a single chapter page. Chapter pages usually carry
     * the real per-work metadata even when the index page is nothing but a list of links, so this
     * backfills what the index couldn't say. A `null` field means "no better answer than the index
     * already had", notably the site-wide `meta[name=author]`, which is deliberately not consulted.
     */
    fun parseWorkMetadata(html: String, pageUrl: String = ""): WorkMetadata {
        val doc = parse(html, pageUrl)
        val titleTag = cleanTitleTag(doc.title())
        return WorkMetadata(
            title = metaContent(doc, WORK_TITLE_META)
                ?: headingText(doc, WORK_TITLE_HEADINGS)
                ?: titleTag.takeIf { it.isNotEmpty() && !isChapterText(it) },
            author = metaContent(doc, WORK_AUTHOR_META)
                ?: headingText(doc, WORK_AUTHOR_HEADINGS)?.replace(Regex("^by\\s+", RegexOption.IGNORE_CASE), ""),
        )
    }

    // ------------------------------------------------------------------ entry point

    /**
     * Parses a web-novel TOC page into title/author/cover metadata plus an ordered chapter list.
     * Returns `null` when the page doesn't look like a chapter list (fewer than
     * [MIN_CHAPTER_LINKS] chapter-like links).
     */
    fun parseChapterList(html: String, pageUrl: String): NovelToc? {
        val doc = parse(html, pageUrl)
        val links = collectLinks(doc, pageUrl)

        // Chapter-like links: recognizable chapter titles, or, when titles carry no numbers
        // ("Prologue", "The End"), the page's dominant href template.
        var candidates = links.filter { isChapterText(it.text) }
        if (candidates.size < MIN_CHAPTER_LINKS) {
            val majority = majorityTemplate(links)
            if (majority == null || majority.second < MIN_CHAPTER_LINKS) return null
            candidates = links.filter { it.template == majority.first }
        }

        val container = findChapterContainer(candidates) ?: return null

        // Keep the container's chapter-like links in document order; also admit same-template
        // links whose titles didn't match (a "Prologue" row inside a numbered list). Dedup by URL.
        val candidateSet = candidates.toHashSet()
        val template = majorityTemplate(candidates)?.first
        val seen = HashSet<String>()
        val contained = ArrayList<LinkEntry>()
        for (entry in links) {
            if (!isInside(entry.el, container)) continue
            if (!candidateSet.contains(entry) && entry.template != template) continue
            if (!seen.add(entry.url)) continue
            contained.add(entry)
        }
        if (contained.size < MIN_CHAPTER_LINKS) return null

        val ordered = orderChapters(contained)
        val meta = extractMetadata(doc, pageUrl)
        return NovelToc(
            title = meta.title,
            author = meta.author,
            coverUrl = meta.coverUrl,
            chapters = ordered.map { NovelChapterLink(it.text, it.url) },
            weakTitle = meta.weakTitle,
            weakAuthor = meta.weakAuthor,
            htmlLang = doc.selectFirst("html")?.attr("lang")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
