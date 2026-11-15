package io.github.itsjanghoon.shortstop.engine

import java.net.IDN

/** Port of engine/src/links.ts. */

private val URL_RE = Regex(
    """\b((?:https?://)?(?:[a-z0-9\u00a1-\uffff](?:[a-z0-9\u00a1-\uffff-]{0,61}[a-z0-9\u00a1-\uffff])?\.)+(?:xn--[a-z0-9-]+|[a-z\u00a1-\uffff]{2,24})(?::\d{2,5})?(?:/[^\s<>"')\]]*)?)""",
    RegexOption.IGNORE_CASE,
)

/** Swaps scammers use to make a fake host look real. */
private val LOOKALIKE = listOf("rn" to "m", "vv" to "w", "0" to "o", "1" to "l", "3" to "e", "5" to "s", "|" to "l")

private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
private val HAS_SCHEME = Regex("""^https?://""", RegexOption.IGNORE_CASE)
private val PLAIN_HTTP = Regex("""^http://""", RegexOption.IGNORE_CASE)
private val TRAILING = Regex("""[.,;:!?]+$""")

const val REPORTED_REASON = "other Shortstop users reported this site"

fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    val prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var last = prev[0]
        prev[0] = i
        for (j in 1..b.length) {
            val tmp = prev[j]
            prev[j] = minOf(prev[j] + 1, prev[j - 1] + 1, last + if (a[i - 1] == b[j - 1]) 0 else 1)
            last = tmp
        }
    }
    return prev[b.length]
}

/** Last two labels, or three for things like co.uk. Good enough for lookalike checks. */
fun registrable(host: String): String {
    val parts = host.split(".")
    val second = parts.getOrNull(parts.size - 2) ?: ""
    val n = if (parts.size > 2 && second.length <= 3 && second in listOf("co", "com", "org", "net", "gov", "ac")) 3 else 2
    return parts.takeLast(n).joinToString(".")
}

private fun label(domain: String) = domain.split(".").first()

private fun normalize(s: String): String {
    var out = s.lowercase()
    for ((from, to) in LOOKALIKE) out = out.replace(from, to)
    return out
}

private fun mixedScript(s: String) = s.any { it.isLetter() && it.code < 0x80 } && s.any { it.code > 0x7f }

fun extractLinks(text: String): List<String> {
    val found = LinkedHashSet<String>()
    for (m in URL_RE.findAll(text)) {
        val raw = m.groupValues[1].replace(TRAILING, "")
        // Skip placeholders and e-mail domains.
        if (raw.startsWith("[")) continue
        if (m.range.first > 0 && text[m.range.first - 1] == '@') continue
        found.add(raw)
    }
    return found.toList()
}

/** Host name the way a browser's URL parser reports it: lower case, international names as punycode. */
private fun hostOf(withScheme: String): String? {
    val auth = withScheme.substringAfter("://").takeWhile { it !in "/?#\\" }.substringAfterLast('@')
    val host = auth.substringBefore(':').trimEnd('.')
    if (host.isEmpty()) return null
    return try {
        IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase()
    } catch (_: IllegalArgumentException) {
        null
    }
}

class LinkChecker(private val known: KnownDomains) {
    fun checkLink(raw: String): LinkFinding {
        val withScheme = if (HAS_SCHEME.containsMatchIn(raw)) raw else "http://$raw"
        val host = hostOf(withScheme) ?: raw.lowercase()
        val reasons = mutableListOf<String>()
        val reg = registrable(host)
        val tld = host.split(".").last()

        if (reg in known.domains) return LinkFinding(raw, host, reasons)
        if (IPV4.matches(host)) reasons.add("link goes to a bare IP address")
        if (host.split(".").any { it.startsWith("xn--") } || mixedScript(raw.split("/").getOrNull(2) ?: raw)) {
            reasons.add("link uses look-alike letters from another alphabet")
        }
        if (reg in known.shorteners) reasons.add("link is shortened, so you cannot see where it goes")
        if (tld in known.riskyTlds) reasons.add("link ends in .$tld, common in scams")
        if (PLAIN_HTTP.containsMatchIn(raw)) reasons.add("link is not secure (http)")

        val regLabel = label(reg)
        val flat = normalize(regLabel.replace("-", ""))
        val inner = host.split(".").dropLast(2)
        for (real in known.domains) {
            val realLabel = label(real)
            if (realLabel.length < 4) continue
            val near = levenshtein(flat, realLabel) <= if (realLabel.length >= 8) 2 else 1
            val brandInside = flat.contains(realLabel) || realLabel in inner
            if (near || brandInside) {
                reasons.add("looks like $real but is $reg")
                break
            }
        }
        if (regLabel.count { it == '-' } >= 2) reasons.add("link has many dashes, common in fake sites")
        return LinkFinding(raw, host, reasons)
    }

    /** Rule check for every link in the text. Runs on device, always. */
    fun checkLinks(text: String): List<LinkFinding> = extractLinks(text).map(::checkLink)
}
