package com.aryan.reader.epub

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EpubUtilsLocalNameTest {
    // A package as written by Python's ElementTree: the OPF namespace has a generated `ns0:` prefix.
    private val prefixedOpf = """<?xml version='1.0' encoding='utf-8'?>
<ns0:package xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:ns0="http://www.idpf.org/2007/opf" version="2.0">
  <ns0:metadata><dc:title>Titel</dc:title></ns0:metadata>
  <ns0:manifest><ns0:item id="a" href="a.html" media-type="application/xhtml+xml"/></ns0:manifest>
  <ns0:spine><ns0:itemref idref="a"/></ns0:spine>
</ns0:package>"""

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(xml.byteInputStream())

    @Test
    fun `finds package sections that carry a generated namespace prefix`() {
        val document = parse(prefixedOpf)

        // The literal-name lookup is what used to fail for these books.
        assertNull(document.selectFirstTag("metadata"))
        assertNotNull(document.selectFirstTagByLocalName("metadata"))
        assertNotNull(document.selectFirstTagByLocalName("manifest"))
        assertNotNull(document.selectFirstTagByLocalName("spine"))
    }

    @Test
    fun `finds the first child by local name`() {
        val metadata = parse(prefixedOpf).selectFirstTagByLocalName("metadata")!!
        assertEquals("Titel", metadata.selectFirstChildTagByLocalName("title")?.textContent)
    }
}
