package io.github.itsjanghoon.shortstop.engine

/** Port of engine/src/mask.ts. Rules and word lists must stay identical to the TypeScript engine. */

private fun digits(s: String) = s.filter { it in '0'..'9' }

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

/**
 * Compile a pattern with JavaScript meaning, so the JVM (tests) and Android (ICU) match like the web:
 * `\b` is an ASCII word boundary and `\s` includes Unicode spaces.
 */
internal fun js(pattern: String, vararg options: RegexOption): Regex {
    val p = pattern
        .replace("""\b""", """(?:(?<=[A-Za-z0-9_])(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])(?=[A-Za-z0-9_]))""")
        .replace("""\s""", """[\s\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]""")
    return Regex(p, options.toSet())
}

private val I = RegexOption.IGNORE_CASE

private class Rule(val kind: MaskKind, val re: Regex, val check: ((String) -> Boolean)? = null)

// Order matters: longer, more specific patterns first.
private val RULES = listOf(
    Rule(MaskKind.EMAIL, js("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")),
    Rule(MaskKind.RRN, js("""\b\d{6}-[1-8]\d{6}\b""")),
    Rule(MaskKind.SSN, js("""\b(?!000|666|9\d\d)\d{3}-(?!00)\d{2}-(?!0000)\d{4}\b""")),
    Rule(MaskKind.CARD, js("""\b(?:\d[ -]?){12,18}\d\b"""), ::luhn),
    Rule(
        MaskKind.ACCOUNT,
        js("""\b(?:acct|account|a/c|iban|routing)\b[^\d\n]{0,12}([A-Z]{2}\d{2}[A-Z0-9]{8,30}|\d[\d -]{5,20}\d)""", I),
    ),
    // Policy, member, claim and other ID numbers: a label, then a code with at least 4 digits.
    Rule(
        MaskKind.ACCOUNT,
        js(
            """\b(?:policy|member|membership|claim|customer|patient|student|employee|case|reference|ref|license|licence|passport|id)\b(?:\s*(?:no\.?|number|#|id))?[:\s#]{1,3}((?=[A-Z0-9-]*\d[A-Z0-9-]*\d[A-Z0-9-]*\d[A-Z0-9-]*\d)[A-Z0-9][A-Z0-9-]{3,24}[A-Z0-9])\b""",
            I,
        ),
    ),
    Rule(MaskKind.PHONE, js("""(?:\+\d{1,3}[ .-]?)?(?:\(\d{2,4}\)[ .-]?|\b\d{2,4}[ .-])\d{3,4}[ .-]\d{4}\b""")),
    // Local numbers without an area code: "555-0147".
    Rule(MaskKind.PHONE, js("""(?<![\d-])\b\d{3}[.-]\d{4}\b(?![\d-])""")),
    // Birth dates after a label: "DOB 1989-04-12", "born 04/12/1989".
    Rule(
        MaskKind.DOB,
        js(
            """\b(?:dob|d\.o\.b\.?|date of birth|birth ?date|born)\b[^\d\n]{0,6}(\d{4}-\d{1,2}-\d{1,2}|\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}|[A-Z][a-z]{2,8}\.? \d{1,2},? \d{4})""",
            I,
        ),
    ),
    // Codes such as policy, member or case IDs: "EG-77120", "GRP-5521".
    Rule(MaskKind.ACCOUNT, js("""\b[A-Z]{1,4}-\d{4,10}(?:-[A-Z0-9]{1,6})?\b""")),
)

private val TOKEN = Regex("""^\[[A-Z]+_\d+]$""")
private val LOOSE_TOKEN = Regex("""^\[?[A-Z]+_\d+\]?$""")

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
            // ACCOUNT and DOB keep their label ("account", "DOB") and hide only the value.
            val group = m.groups.let { if (it.size > 1) it[1]?.value else null }
            val value = if ((rule.kind == MaskKind.ACCOUNT || rule.kind == MaskKind.DOB) && group != null) group else whole
            if (rule.check != null && !rule.check.invoke(value)) return@replace whole
            if (TOKEN.matches(value)) return@replace whole
            val token = nextToken(entries, rule.kind, value)
            whole.replaceFirst(value, token)
        }
    }
    return Masked(text, entries)
}

private val STARTS_DIGIT = Regex("""^\d""")
private val PART_SPLIT = js("""[\s,]+""")
private val STARTS_UPPER = Regex("""^\p{Lu}""")

/** Hide names and addresses the on-device model found. Ignores anything not literally in the text. */
fun maskNames(input: Masked, names: List<String> = emptyList()): Masked {
    val entries = input.entries.toMutableList()
    var text = input.text
    // Also hide the parts of a full name, so "Andrew" alone is caught when the model said "Andrew Shaw".
    val parts = names.flatMap { n ->
        if (STARTS_DIGIT.containsMatchIn(n.trim())) {
            emptyList()
        } else {
            n.trim().split(PART_SPLIT).filter { p ->
                p.length >= 3 && STARTS_UPPER.containsMatchIn(p) && p !in NOT_NAME && (p in GIVEN || unknownWord(p))
            }
        }
    }
    val clean = (names + parts).map { it.trim() }.distinct()
        // A model may hand back a placeholder ("NAME_1" or "[NAME_1]") as a name; it is already masked.
        .filter { it.length in 2..80 && !LOOSE_TOKEN.matches(it) }
        .sortedByDescending { it.length }
    for (name in clean) {
        // Not inside a link or an e-mail address: "acrnebank" in acrnebank.com/secure is a site, not a person.
        // Not inside a placeholder either: "[NAME_1]" stays as it is.
        val re = Regex("""(?<![\p{L}\d./@\[_-])${Regex.escape(name)}(?![\p{L}\d_]|\.\p{L}|[/@\]-])""")
        if (!re.containsMatchIn(text)) continue
        val token = nextToken(entries, MaskKind.NAME, name)
        text = re.replace(text) { token }
    }
    return Masked(text, entries)
}

private val FAMILY = setOf(
    "mom", "mum", "dad", "grandma", "grandpa", "honey", "dear", "sir", "madam", "there", "all", "team", "everyone", "customer",
)

/** Common English given names (US census top names, both sexes). Used only when no on-device model can find names. Order matters for the regex. */
private val GIVEN_LIST = (
    "James John Robert Michael Mike William David Richard Joseph Thomas Charles Chris Christopher Daniel Dan Matthew Matt Anthony Donald Steven Steve Paul Andrew Andy Joshua Kenneth Kevin Brian Brandon George Timothy Tim Ronald Edward Jason Jeffrey Ryan Jacob Gary Nicholas Nick Eric Jonathan Stephen Larry Justin Scott Benjamin Ben Gregory Samuel Sam Raymond Patrick Alexander Alex Dennis Jerry Tyler Aaron Jose Henry Adam Douglas Nathan Peter Zachary Kyle Walter Harold Carl Jeremy Keith Roger Gerald Ethan Arthur Terry Sean Lawrence Joe Noah Jesse Albert Bryan Billy Bruce Willie Dylan Alan Ralph Gabriel Roy Juan Wayne Eugene Logan Randy Louis Russell Vincent Philip Bobby Johnny Bradley Leo Lucas Liam Oliver Owen Mason Elijah Luke Isaac Jake Tom Tony Ted Fred Hank Jim Jimmy Bob Ken Rick Ron Greg Doug Phil " +
        "Mary Patricia Jennifer Linda Elizabeth Liz Barbara Susan Jessica Sarah Karen Lisa Nancy Betty Margaret Sandra Ashley Kimberly Kim Emily Donna Michelle Carol Amanda Melissa Deborah Stephanie Rebecca Sharon Laura Cynthia Dorothy Amy Kathleen Angela Shirley Emma Brenda Pamela Nicole Anna Samantha Katherine Kate Christine Debra Rachel Carolyn Janet Maria Olivia Heather Helen Catherine Diane Julie Victoria Joyce Lauren Kelly Christina Ruth Joan Judith Evelyn Hannah Andrea Megan Cheryl Jacqueline Madison Teresa Abigail Sophia Martha Sara Gloria Janice Kathryn Ann Isabella Judy Charlotte Julia Grace Alice Jean Denise Frances Danielle Marilyn Natalie Beverly Diana Brittany Theresa Kayla Alexis Doris Lori Tiffany Mia Ava Chloe Ella Lily Zoe Nora Ellie Ruby Dana Jane Jenny Jen Becky Beth Meg Tina Wendy Holly Paula Monica Erin Tracy Stacy Yuna Mina Jisoo Minji Hana"
    ).split(" ")
private val GIVEN: Set<String> = LinkedHashSet(GIVEN_LIST)

/** Words that follow a first name but are not surnames. */
private val NOT_SURNAME = setOf("The", "And", "From", "Here", "Is", "Was", "Will", "Can", "Said", "At", "On", "In", "To", "For", "Has", "Had", "Just", "Again", "Please")

/** Capitalized words that are rarely part of a person's name. */
private val NOT_NAME: Set<String> = (
    "The A An And Or Of To In On At By For From With Your Our My His Her Their This That These Those It We You I He She They Please Thanks Thank Dear Hi Hello " +
        "Monday Tuesday Wednesday Thursday Friday Saturday Sunday January February March April May June July August September October November December " +
        "Bank Account Card Credit Debit Street St Avenue Ave Road Rd Drive Lane Way Court Blvd Suite Apt Unit Floor City County State North South East West New Old " +
        "Inc LLC Ltd Corp Co Company Group Services Service Support Team Department Office Center Centre Hospital Clinic Medical School University College Agency Tax " +
        "Acme Contoso Fabrikam Northwind Globex Initech Umbrella Wingtip Tailspin Adatum Litware Zelle Venmo Wi Fi Gmail Outlook iPhone Android Chrome Google Apple Amazon Microsoft PayPal Bitcoin Ethereum USD EUR KRW Parcel Delivery Order Invoice Payment Customer Client Tenant Landlord Patient Employee Manager Director " +
        "Agreement Contract Lease Policy Terms Section Clause Notice Statement Report Summary Date Total Amount Number Phone Email Address Name Re Subject Ticket Case " +
        "Mr Mrs Ms Dr Prof Sir Madam Mom Dad Grandma Grandpa Security Fraud Alert Prime Gold Premium Plus Pro App Store Online Mobile Web Wallet Coin Crypto Rewards"
    ).split(" ").toSet()

private val MONTHS = "jan feb mar apr jun jul aug sep sept oct nov dec mon tue tues wed thu thur thurs fri sat sun".split(" ").toSet()

/** Casual words that are not in the dictionary. */
private val SLANG = "txt msg msgs pic pics info appt dept acct govt intl ok okay hey hiya yo lol omg btw pls plz thx ty ur u gonna wanna gotta yep nope yeah ya hmm ugh wow oops xoxo xo ps fyi asap tbh idk imo brb ttyl np"
    .split(" ").toSet()

private val NOT_PERSON = listOf("the", "your", "our", "a", "an", "this", "that", "not", "just", "here", "sorry", "back", "calling", "texting", "writing", "contoso", "acme")
private val SPACE_DASH = Regex("[ -]")

/** Single capitalized word that is not a common non-name. */
private fun isPersonish(s: String): Boolean {
    val w = s.split(SPACE_DASH)[0].lowercase()
    return w !in FAMILY && w !in NOT_PERSON
}

/**
 * The English word list (shared/words.txt). Install a loader once; the list is read on first use.
 * The app reads it from assets, tests from the repo.
 */
object Words {
    @Volatile
    private var loader: (() -> String)? = null

    fun install(load: () -> String) {
        if (loader == null) loader = load
    }

    val set: Set<String> by lazy {
        val l = loader ?: error("Words.install was not called")
        HashSet(l().split("\n"))
    }
}

private const val NAME = """[A-Z][a-z]+(?:[ -][A-Z][a-z]+){0,2}"""
private val LABEL = js(
    """\b(?:Tenant|Landlord|Name|Patient|Client|Customer|Employee|Buyer|Seller|Signed|Dear|Hi|Hello|Mr\.?|Mrs\.?|Ms\.?|Dr\.?)[:,]?\s+($NAME)""",
)
private val TITLE = js("""\b(?:Mr|Mrs|Ms|Mx|Dr|Prof|Coach|Officer|Agent|Nurse|Judge)\.?\s+($NAME)""")
private val INITIAL = js("""\b[A-Z]\. [A-Z][a-z]{2,}\b""")
private val OWN_LINE = js("""^([A-Z][a-z]+(?: [A-Z]\.)? [A-Z][a-z]+(?:-[A-Z][a-z]+)?)$""", RegexOption.MULTILINE)
private val COMMA_NAME = js(""",\s+([A-Z][a-z]{2,15})[!?]""")
private val INTRO = js("""\b(?:[Tt]his is|[Ii]t's|[Ii]t is|I'm|I am|[Mm]y name is|[Nn]ame's)\s+($NAME)""")
private val SIGN = js(
    """(?:^|\n|\s)(?:[-~]\s*|(?:Thanks|Thank you|Cheers|Best|Regards|Best regards|Kind regards|Sincerely|Love|Warmly|XO|Sent by)[,!]?\s+)($NAME)\b""",
)
private val GIVEN_RE = js("""\b(${GIVEN.joinToString("|")})(?:\s+[A-Z][a-z]+(?:-[A-Z][a-z]+)?)?\b""")
private val WS = js("""\s+""")
private val PAIR = js(
    """(?<=[a-z,;:(]\s|\b(?:and|with|by|for|to|from|of|or)\s)([A-Z][a-z]{1,15}) ([A-Z][a-z]{1,15}(?:-[A-Z][a-z]+)?)\b""",
)
private val STREET = js(
    """\b\d{1,5} [A-Z][a-z]+(?: [A-Z][a-z]+)? (?:St|Street|Ave|Avenue|Rd|Road|Blvd|Dr|Drive|Ln|Lane|Way|Ct|Court|Terrace|Place|Pl|Circle|Cir|Square|Parkway|Pkwy|Highway|Hwy|Row|Close|Crescent)\b\.?""",
)
private const val W = """\p{Lu}[\p{Ll}'’]+(?:-\p{Lu}?[\p{Ll}'’]+)?"""
private val RUN = js("""(?<![\p{L}\d@._/-])($W(?:[ \t]+(?:\p{Lu}\.[ \t]+)?$W){0,3})(?![\p{L}\d@_/-]|\.[a-z])""")
private val CAPS = js("""(?<![\p{L}\d])(\p{Lu}{2,}(?:\s+\p{Lu}{2,}){1,3})(?![\p{L}\d])""")
private val INITIAL_ONLY = Regex("""^\p{Lu}\.$""")
private val CONTRACTION = Regex("""['’](?:m|ll|re|ve|d|t)$""", RegexOption.IGNORE_CASE)
private val POSSESSIVE = Regex("""['’]s?$""")
private val TRAILING_DOT = Regex("""\.$""")

/** Rule-based guess of names and street addresses, for when no model is around (paste guard, no AI). */
fun guessNames(text: String): List<String> {
    val out = LinkedHashSet<String>()
    for (m in LABEL.findAll(text)) if (m.groupValues[1].lowercase() !in FAMILY) out.add(m.groupValues[1])
    // Titles: "Mrs. Chen", "Dr. Alvarez", "Coach Daniels".
    for (m in TITLE.findAll(text)) out.add(m.groupValues[1])
    // An initial and a surname: "R. Kerrigan".
    for (m in INITIAL.findAll(text)) out.add(m.value)
    // A name on its own line (resume, letter header): "Aisha Rahman".
    for (m in OWN_LINE.findAll(text)) {
        val n = m.groupValues[1]
        if (n.split(" ").all { it.replaceFirst(".", "") !in NOT_NAME }) out.add(n)
    }
    // "Welcome aboard, Marcus!": a single name right after a comma, ending with ! or ?.
    for (m in COMMA_NAME.findAll(text)) {
        val n = m.groupValues[1]
        if (n !in NOT_NAME && isPersonish(n)) out.add(n)
    }
    // "this is Dana", "it's Mike", "I'm Grace", "my name is ...": a person introducing themselves.
    for (m in INTRO.findAll(text)) if (isPersonish(m.groupValues[1])) out.add(m.groupValues[1])
    // Sign-offs: "- Mike", "Thanks, Dana", "Love,\nGrace".
    for (m in SIGN.findAll(text)) if (isPersonish(m.groupValues[1])) out.add(m.groupValues[1])
    // Common given names on their own, with an optional surname.
    for (m in GIVEN_RE.findAll(text)) {
        val full = m.value
        val last = full.split(WS).getOrNull(1)
        out.add(if (!last.isNullOrEmpty() && last !in NOT_SURNAME) full else m.groupValues[1])
    }
    // "Firstname Lastname" mid-sentence: two capitalized words that are not common non-name words.
    // Masking a few extra words is safe; sending a real name is not.
    for (m in PAIR.findAll(text)) {
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        if (a !in NOT_NAME && b !in NOT_NAME && (a in GIVEN || unknownWord(a) || unknownWord(b))) out.add("$a $b")
    }
    for (m in STREET.findAll(text)) out.add(m.value.replace(TRAILING_DOT, ""))
    // Any capitalized word that is not an English word is probably a name ("Priya", "Okafor", "Tomás").
    // Runs of them make one name ("Kenji Tanaka", "Rosa M. Fernández"); ALL-CAPS runs too ("FIONA ADEBAYO").
    for (m in RUN.findAll(text)) {
        val r = m.groupValues[1]
        val parts = r.split(WS).filter { !INITIAL_ONLY.matches(it) }
        if (parts.any { unknownWord(it) }) {
            val keep = trimRun(r)
            if (keep.isNotEmpty()) out.add(keep)
        }
    }
    for (m in CAPS.findAll(text)) {
        val r = m.groupValues[1]
        if (r.split(WS).all { p -> unknownWord(p[0] + p.substring(1).lowercase()) }) out.add(r)
    }
    // Keep the longest form: drop "John" when "John Smith" is there.
    val all = out.toList()
    return all.filter { n -> all.none { o -> o != n && o.contains(n) } }
}

/** True for a capitalized word that is not in the English word list and not a known non-name. */
private fun unknownWord(w: String): Boolean {
    val words = Words.set
    if (CONTRACTION.containsMatchIn(w)) return false // I'm, We'll, Don't
    val base = w.replaceFirst(POSSESSIVE, "")
    if (base.contains("-")) {
        return base.split("-").any { p -> p.length > 1 && unknownWord(p[0].uppercase() + p.substring(1)) }
    }
    val lower = base.lowercase()
    if (lower in MONTHS) return false
    if (base in NOT_NAME || lower in FAMILY || lower in SLANG) return false
    if (lower in words || lower.removeSuffix("s") in words) return base in GIVEN
    return base.length >= 2
}

/** Drop leading and trailing words of a run that are ordinary words ("Hi Priya" -> "Priya"). */
private fun trimRun(r: String): String {
    val parts = r.split(WS).toMutableList()
    while (parts.isNotEmpty() && !INITIAL_ONLY.matches(parts.first()) && !unknownWord(parts.first())) parts.removeAt(0)
    while (parts.isNotEmpty() && !INITIAL_ONLY.matches(parts.last()) && !unknownWord(parts.last())) parts.removeAt(parts.size - 1)
    return parts.joinToString(" ")
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
