package com.aryan.reader.webimport

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.safety.Safelist

internal class ExtractedPage(
    val title: String,
    val bodyXhtml: String,
    val textLength: Int,
)

/** Small reader-mode extractor: finds the main text block of an HTML page and returns clean XHTML. */
internal object ContentExtractor {
    private val CONTENT_SELECTORS = listOf(
        "article",
        "[itemprop=articleBody]",
        "#chapter-content",
        ".chapter-content",
        ".chapter_content",
        "#chr-content",
        ".reading-content",
        ".entry-content",
        ".post-content",
        ".text-left",
        "#content",
        "main",
    )

    private const val NOISE =
        "script,style,noscript,nav,header,footer,aside,form,iframe,svg,button,select,input," +
            "figure,img,picture,video,audio,.ads,.advertisement,.comments,#comments,.share," +
            ".sharedaddy,.navigation,.nav-links"

    private val SAFELIST: Safelist = Safelist.none().addTags(
        "p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "em", "strong", "i", "b", "u",
        "blockquote", "ul", "ol", "li", "hr", "pre", "code", "span", "div", "sub", "sup",
        "table", "thead", "tbody", "tr", "td", "th",
    )

    fun xhtmlSettings(): Document.OutputSettings =
        Document.OutputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)

    /** Cleans arbitrary HTML (or plain text) into well-formed XHTML with only reading markup. */
    fun cleanToXhtml(html: String): String = Jsoup.clean(html, "", SAFELIST, xhtmlSettings())

    fun pageTitle(doc: Document): String {
        val og = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        if (og.isNotEmpty()) return og
        val heading = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (heading.isNotEmpty()) return heading
        return doc.title().trim()
    }

    fun extract(source: Document): ExtractedPage {
        val title = pageTitle(source).ifBlank { "Webpage" }
        val working = source.clone()
        working.select(NOISE).remove()
        val root = pickRoot(working)
        val cleaned = cleanToXhtml(root.html())
        val length = Jsoup.parse(cleaned).text().length
        return ExtractedPage(title, cleaned, length)
    }

    /**
     * Tries a list of known content selectors in order and returns the first one that holds at
     * least [minText] characters, or `null`. Unlike [extract] this never falls back to a
     * best-guess block or `<body>`, so callers can tell "found the chapter body" from "guessed".
     */
    fun extractBySelectors(source: Document, selectors: List<String>, minText: Int): ExtractedPage? {
        val working = source.clone()
        working.select(NOISE).remove()
        for (selector in selectors) {
            val element = working.selectFirst(selector) ?: continue
            val cleaned = cleanToXhtml(element.html())
            val length = Jsoup.parse(cleaned).text().length
            if (length >= minText) {
                return ExtractedPage(pageTitle(source).ifBlank { "Webpage" }, cleaned, length)
            }
        }
        return null
    }

    private fun pickRoot(doc: Document): Element {
        for (selector in CONTENT_SELECTORS) {
            val found = doc.select(selector).maxByOrNull { it.text().length }
            if (found != null && found.text().length >= 200) return found
        }
        val best = doc.select("div, section, article, main").maxByOrNull { element ->
            element.children().filter { it.tagName() == "p" }.sumOf { it.text().length }
        }
        if (best != null && best.children().any { it.tagName() == "p" }) return best
        return doc.body()
    }
}
