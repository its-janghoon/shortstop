package io.github.itsjanghoon.shortstop.engine

/** Port of engine/src/mask.ts. */

private fun digits(s: String) = s.filter { it.isDigit() }

/** Luhn check, so random long numbers are not called cards. */
fun luhn(num: String): Boolean {
    val d = digits(num)
    if (d.length < 13 || d.length > 19) return false
    var sum = 0
    for (i in d.indices) {
        var n = d[d.length - 1 - i] - '0'
        if (i % 2 == 1) {
            n *= 2
            if (n > 9) n -= 9
        }
        sum += n
    }
    return sum % 10 == 0
}

private class Rule(val kind: MaskKind, val re: Regex, val check: ((String) -> Boolean)? = null)

// Order matters: longer, more specific patterns first.
private val RULES = listOf(
    Rule(MaskKind.EMAIL, Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")),
    Rule(MaskKind.RRN, Regex("""\b\d{6}-[1-8]\d{6}\b""")),
    Rule(MaskKind.SSN, Regex("""\b(?!000|666|9\d\d)\d{3}-(?!00)\d{2}-(?!0000)\d{4}\b""")),
    Rule(MaskKind.CARD, Regex("""\b(?:\d[ -]?){12,18}\d\b"""), ::luhn),
    Rule(
        MaskKind.ACCOUNT,
        Regex("""\b(?:acct|account|a/c|iban|routing)\b[^\d\n]{0,12}([A-Z]{2}\d{2}[A-Z0-9]{8,30}|\d[\d -]{5,20}\d)""", RegexOption.IGNORE_CASE),
    ),
    Rule(MaskKind.PHONE, Regex("""(?:\+\d{1,3}[ .-]?)?(?:\(\d{2,4}\)[ .-]?|\b\d{2,4}[ .-])\d{3,4}[ .-]\d{4}\b""")),
)

private val TOKEN = Regex("""^\[[A-Z]+_\d+]$""")

data class Masked(val text: String, val entries: List<MaskEntry>)

private fun nextToken(entries: MutableList<MaskEntry>, kind: MaskKind, value: String): String {
    entries.firstOrNull { it.kind == kind && it.value == value }?.let { return it.token }
    val n = entries.count { it.kind == kind } + 1
    val token = "[${kind.name}_$n]"
    entries.add(MaskEntry(token, kind, value))
    return token
}

/** Replace private data found by rules with placeholders like [PHONE_1]. Runs on device, always. */
fun maskByRules(input: String, start: List<MaskEntry> = emptyList()): Masked {
    val entries = start.toMutableList()
    var text = input
    for (rule in RULES) {
        text = rule.re.replace(text) { m ->
            val whole = m.value
            // ACCOUNT keeps its label ("account") and hides only the number.
            val value = if (rule.kind == MaskKind.ACCOUNT) m.groups[1]?.value ?: whole else whole
            if (rule.check != null && !rule.check.invoke(value)) return@replace whole
            if (TOKEN.matches(value)) return@replace whole
            val token = nextToken(entries, rule.kind, value)
            whole.replaceFirst(value, token)
        }
    }
    return Masked(text, entries)
}

/** Hide names and addresses the on-device model found. Ignores anything not literally in the text. */
fun maskNames(input: Masked, names: List<String> = emptyList()): Masked {
    val entries = input.entries.toMutableList()
    var text = input.text
    val clean = names.map { it.trim() }.distinct()
        .filter { it.length in 2..80 && !TOKEN.matches(it) }
        .sortedByDescending { it.length }
    for (name in clean) {
        val re = Regex("""(?<![\p{L}\d])${Regex.escape(name)}(?![\p{L}\d])""")
        if (!re.containsMatchIn(text)) continue
        val token = nextToken(entries, MaskKind.NAME, name)
        text = re.replace(text) { token }
    }
    return Masked(text, entries)
}

private val FAMILY = setOf(
    "mom", "mum", "dad", "grandma", "grandpa", "honey", "dear", "sir", "madam", "there", "all", "team", "everyone", "customer",
)
private const val NAME = """[A-Z][a-z]+(?:[ -][A-Z][a-z]+){0,2}"""
private val LABEL = Regex(
    """\b(?:Tenant|Landlord|Name|Patient|Client|Customer|Employee|Buyer|Seller|Signed|Dear|Hi|Hello|Mr\.?|Mrs\.?|Ms\.?|Dr\.?)[:,]?\s+($NAME)""",
)
private val STREET = Regex("""\b\d{1,5} [A-Z][a-z]+(?: [A-Z][a-z]+)? (?:St|Street|Ave|Avenue|Rd|Road|Blvd|Dr|Drive|Ln|Lane|Way|Ct|Court)\b\.?""")

/** Rule-based guess of names and street addresses, for when no model is around. */
fun guessNames(text: String): List<String> {
    val out = LinkedHashSet<String>()
    for (m in LABEL.findAll(text)) {
        val name = m.groupValues[1]
        if (name.lowercase() !in FAMILY) out.add(name)
    }
    for (m in STREET.findAll(text)) out.add(m.value.removeSuffix("."))
    return out.toList()
}

/** Put the real values back, on device, before showing a cloud answer. */
fun unmask(text: String, entries: List<MaskEntry>): String {
    var out = text
    for (e in entries) out = out.replace(e.token, e.value)
    return out
}

/** Tokens whose real value still appears in text that is about to leave the device. */
fun leaks(text: String, entries: List<MaskEntry>): List<String> =
    entries.filter { text.contains(it.value) }.map { it.token }
