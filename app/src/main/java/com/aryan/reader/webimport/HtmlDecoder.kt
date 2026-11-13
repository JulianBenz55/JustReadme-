package com.aryan.reader.webimport

import org.jsoup.Jsoup
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes a fetched page body the way a browser would (port of Readest's `decodeHtmlBody`).
 *
 * Jsoup's own sniffing only looks at the first bytes for a `<meta charset>` and ignores the HTTP
 * header we already have, and Chinese web-novel sites overwhelmingly still serve GB2312/GBK,
 * usually declared only in a `<meta>` tag. Sniffing order: BOM, then the response header, then the
 * `<meta>` prescan, then UTF-8 if the bytes really are UTF-8, else GB18030.
 */
internal object HtmlDecoder {
    /** Matches the prescan window browsers use for the same purpose. */
    private const val CHARSET_SNIFF_BYTES = 1024

    private val CHARSET_PARAM = Regex("charset\\s*=\\s*['\"]?([^'\";,\\s]+)", RegexOption.IGNORE_CASE)

    /** WHATWG treats all of these labels as GBK/GB18030; the JDK's GB2312 table is too small. */
    private val GB_LABELS = setOf(
        "gb2312", "gbk", "gb_2312", "gb_2312-80", "gb18030", "x-gbk", "csgb2312", "chinese", "csiso58gb231280",
    )

    private fun charsetOf(contentType: String?): String? =
        contentType?.let { CHARSET_PARAM.find(it)?.groupValues?.get(1) }

    private fun charsetFor(label: String?): Charset? {
        if (label.isNullOrBlank()) return null
        val normalized = label.trim().lowercase()
        return try {
            if (normalized in GB_LABELS) Charset.forName("GB18030") else Charset.forName(normalized)
        } catch (e: Exception) {
            null // an unregistered label ("x-nonsense", a typo): treat it as undeclared
        }
    }

    /**
     * `<meta charset=…>` or `<meta http-equiv=content-type content="…charset=…">` from the head of
     * the document. The window is parsed rather than pattern-matched, so a declaration inside a
     * comment or script string cannot be mistaken for a real one. Decoding as windows-1252 first is
     * safe: every encoding this matters for is ASCII-compatible in its markup.
     */
    private fun sniffMetaCharset(bytes: ByteArray): String? {
        val window = String(bytes, 0, minOf(bytes.size, CHARSET_SNIFF_BYTES), Charset.forName("windows-1252"))
        val doc = Jsoup.parse(window)
        for (meta in doc.select("meta")) {
            val declared = if (meta.attr("http-equiv").equals("content-type", ignoreCase = true)) {
                charsetOf(meta.attr("content"))
            } else {
                meta.attr("charset")
            }
            val charset = declared?.trim()
            if (!charset.isNullOrEmpty()) return charset
        }
        return null
    }

    fun decode(bytes: ByteArray, contentType: String?): String {
        // A BOM outranks every declaration.
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }

        val declared = charsetFor(charsetOf(contentType)) ?: charsetFor(sniffMetaCharset(bytes))
        if (declared != null) return String(bytes, declared)

        // Nothing declared: UTF-8 when the bytes actually are UTF-8, otherwise GB18030, which
        // decodes every byte sequence and is what an undeclared legacy page on this part of the
        // web almost always turns out to be.
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }
}
