package com.aryan.reader.webimport

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One chapter: [bodyXhtml] must already be well-formed XHTML (output of [ContentExtractor]). */
internal class EpubChapter(val title: String, val bodyXhtml: String)

internal fun escapeXml(text: String?): String {
    if (text == null) return ""
    val out = StringBuilder(text.length + 16)
    for (ch in text) {
        when (ch) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            else -> out.append(ch)
        }
    }
    return out.toString()
}

/** Writes a plain text-only EPUB 3 file. */
internal object EpubWriter {
    private const val CONTAINER_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
            "  <rootfiles>\n" +
            "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
            "  </rootfiles>\n" +
            "</container>\n"

    private const val STYLE_CSS =
        "body { line-height: 1.6; margin: 0 4%; }\n" +
            "h1, h2, h3 { line-height: 1.25; }\n" +
            "p { margin: 0 0 0.9em 0; }\n" +
            "blockquote { margin: 1em 1.5em; font-style: italic; }\n"

    fun write(
        target: File,
        title: String,
        author: String,
        language: String,
        identifier: String,
        chapters: List<EpubChapter>,
    ) {
        require(chapters.isNotEmpty()) { "No chapters to write" }
        target.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val crc = CRC32()
            crc.update(mime)
            val mimeEntry = ZipEntry("mimetype")
            mimeEntry.method = ZipEntry.STORED
            mimeEntry.size = mime.size.toLong()
            mimeEntry.compressedSize = mime.size.toLong()
            mimeEntry.crc = crc.value
            zip.putNextEntry(mimeEntry)
            zip.write(mime)
            zip.closeEntry()

            put(zip, "META-INF/container.xml", CONTAINER_XML)
            put(zip, "OEBPS/style.css", STYLE_CSS)
            put(zip, "OEBPS/content.opf", buildOpf(title, author, language, identifier, chapters.size))
            put(zip, "OEBPS/nav.xhtml", buildNav(title, language, chapters))
            chapters.forEachIndexed { index, chapter ->
                put(zip, "OEBPS/c${index + 1}.xhtml", buildChapter(chapter, language))
            }
        }
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildOpf(title: String, author: String, language: String, identifier: String, count: Int): String {
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val manifest = StringBuilder()
        val spine = StringBuilder()
        for (i in 1..count) {
            manifest.append("    <item id=\"c$i\" href=\"c$i.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            spine.append("    <itemref idref=\"c$i\"/>\n")
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\" xml:lang=\"${escapeXml(language)}\">\n" +
            "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
            "    <dc:identifier id=\"bookid\">${escapeXml(identifier)}</dc:identifier>\n" +
            "    <dc:title>${escapeXml(title)}</dc:title>\n" +
            "    <dc:creator>${escapeXml(author)}</dc:creator>\n" +
            "    <dc:language>${escapeXml(language)}</dc:language>\n" +
            "    <meta property=\"dcterms:modified\">$modified</meta>\n" +
            "  </metadata>\n" +
            "  <manifest>\n" +
            "    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n" +
            "    <item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n" +
            manifest +
            "  </manifest>\n" +
            "  <spine>\n" +
            spine +
            "  </spine>\n" +
            "</package>\n"
    }

    private fun buildNav(title: String, language: String, chapters: List<EpubChapter>): String {
        val items = StringBuilder()
        chapters.forEachIndexed { index, chapter ->
            items.append("      <li><a href=\"c${index + 1}.xhtml\">${escapeXml(chapter.title)}</a></li>\n")
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"${escapeXml(language)}\">\n" +
            "  <head><meta charset=\"utf-8\"/><title>${escapeXml(title)}</title></head>\n" +
            "  <body>\n" +
            "    <nav epub:type=\"toc\" id=\"toc\">\n" +
            "      <h1>${escapeXml(title)}</h1>\n" +
            "      <ol>\n" +
            items +
            "      </ol>\n" +
            "    </nav>\n" +
            "  </body>\n" +
            "</html>\n"
    }

    private fun buildChapter(chapter: EpubChapter, language: String): String {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"${escapeXml(language)}\">\n" +
            "  <head>\n" +
            "    <meta charset=\"utf-8\"/>\n" +
            "    <title>${escapeXml(chapter.title)}</title>\n" +
            "    <link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/>\n" +
            "  </head>\n" +
            "  <body>\n" +
            "    <h2>${escapeXml(chapter.title)}</h2>\n" +
            chapter.bodyXhtml + "\n" +
            "  </body>\n" +
            "</html>\n"
    }
}
