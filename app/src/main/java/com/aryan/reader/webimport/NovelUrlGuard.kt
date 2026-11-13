package com.aryan.reader.webimport

import java.net.URL

/**
 * Refuses explicit private/loopback/link-local targets before any novel request is made
 * (port of Readest's `assertNovelUrlAllowed` + `isBlockedHost`). This is a string check on the
 * host: a public name whose DNS records point at a private address is not caught here.
 */
internal object NovelUrlGuard {
    private const val MESSAGE = "Cannot import from a private or unsupported URL"

    fun assertAllowed(value: String) {
        val url = try {
            URL(value)
        } catch (e: Exception) {
            throw NovelImportException(MESSAGE, NovelImportException.Kind.BLOCKED_URL)
        }
        val host = url.host.trim('[', ']').trimEnd('.')
        if ((url.protocol != "http" && url.protocol != "https") || isBlockedHost(host)) {
            throw NovelImportException(MESSAGE, NovelImportException.Kind.BLOCKED_URL)
        }
    }

    private fun isBlockedV4(a: Int, b: Int, c: Int): Boolean = when {
        a == 0 || a == 10 || a == 127 -> true // this-network, private, loopback
        a == 169 && b == 254 -> true // link-local
        a == 172 && b in 16..31 -> true // private
        a == 192 && b == 168 -> true // private
        a == 100 && b in 64..127 -> true // CGNAT / Tailscale
        a == 192 && b == 0 && c == 0 -> true // IETF protocol assignments
        a == 198 && (b == 18 || b == 19) -> true // benchmarking
        a >= 224 -> true // multicast + reserved
        else -> false
    }

    private val V4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val V4_TAIL = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val V6_HEX_MAPPED = Regex("::ffff:([0-9a-f]{1,4}):([0-9a-f]{1,4})$")
    private val NUMERIC_PART = Regex("^(0x[0-9a-f]+|[0-9]+)$")

    fun isBlockedHost(hostname: String): Boolean {
        val h = hostname.lowercase().trim('[', ']').trimEnd('.')
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan")) return true
        // Bare single-label hostnames ("intranet", "metadata") are never public.
        if (!h.contains('.') && !h.contains(':')) return true

        val v4 = V4.matchEntire(h)
        if (v4 != null) {
            val (a, b, c) = v4.groupValues.drop(1).take(3).map { it.toInt() }
            return isBlockedV4(a, b, c)
        }
        // Decimal/hex/octal IPv4 spellings ("2130706433", "0x7f.1", "0177.0.0.1") are normalized
        // by browsers but not by java.net.URL; a host made only of such parts is never a real name.
        if (h.split('.').all { NUMERIC_PART.matches(it) }) return true

        if (h.contains(':')) {
            if (h == "::" || h == "::1") return true // unspecified, loopback
            if (h.startsWith("fc") || h.startsWith("fd")) return true // unique-local
            if (Regex("^fe[89ab]").containsMatchIn(h)) return true // link-local
            if (Regex("^(ff[0-9a-f]{2}|fe[c-f][0-9a-f]):").containsMatchIn(h)) return true // multicast, site-local
            val mapped = V4_TAIL.find(h)
            if (mapped != null) {
                val (a, b, c) = mapped.groupValues.drop(1).take(3).map { it.toInt() }
                return isBlockedV4(a, b, c)
            }
            val hex = V6_HEX_MAPPED.find(h)
            if (hex != null) {
                val hi = hex.groupValues[1].toInt(16)
                val lo = hex.groupValues[2].toInt(16)
                return isBlockedV4(hi shr 8, hi and 0xFF, lo shr 8)
            }
        }
        return false
    }
}
