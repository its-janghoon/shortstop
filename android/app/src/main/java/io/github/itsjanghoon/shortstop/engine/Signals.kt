package io.github.itsjanghoon.shortstop.engine

// Port of engine/src/signals.ts. Word rules for common scam tricks (FBI IC3 / FTC patterns).
// Used as hints for the models, and as the whole answer when no model can run.

private class Trick(pattern: String, val reason: String, val weight: Int) {
    val re = Regex(pattern, RegexOption.IGNORE_CASE)
}

private val TRICKS = listOf(
    Trick("""\b(gift ?cards?|itunes|google play cards?|steam cards?)\b""", "asks for gift cards", 2),
    Trick("""\b(wire|western union|moneygram|zelle|bitcoin|crypto|usdt|btc)\b""", "asks for money in a hard-to-undo way", 2),
    Trick("""\b(verification|security|one[- ]time|6[- ]digit)\s+code\b|\bsend (me )?the codes?\b""", "asks for a code", 2),
    Trick("""\b(don'?t|do not) tell\b|\bkeep (this|it) (a )?secret\b""", "asks you to keep it secret", 2),
    Trick("""\b(within|in) (24|48) hours\b|\b(urgent|immediately|right away|act now|final notice|today only)\b""", "pushes you to hurry", 1),
    Trick(
        """\b(account|card) (is|has been|was) (locked|suspended|frozen|disabled|blocked)\b|\bunusual (sign-in|activity|login)\b""",
        "says your account is locked",
        1,
    ),
    Trick(
        """\b(unpaid|outstanding|small|customs|redelivery|shipping) fee\b|\bpackage (is )?on hold\b""",
        "asks for a small fee to release a package",
        1,
    ),
    Trick("""\b(lost|broke|new) (my )?phone\b|\bthis is (a friend'?s|my new) number\b""", "sender says they have a new number", 1),
    Trick("""\b(you('ve| have)? won|winner|lottery|prize|claim your reward)\b""", "says you won something", 2),
    Trick("""\b(irs|tax office|police|warrant|arrest)\b.*\b(pay|fine|penalty)\b""", "threatens you with the law", 2),
    Trick("""\b(guaranteed|double your|risk[- ]free) (returns?|profit|money)\b""", "promises sure profit", 2),
    Trick("""\b(remote access|anydesk|teamviewer|install this app)\b""", "asks for remote access to your device", 2),
    Trick("""\b(password|pin|social security number|ssn)\b""", "asks for private login details", 1),
)

data class TextSignals(val reasons: List<String>, val score: Int)

fun textSignals(text: String): TextSignals {
    val hits = TRICKS.filter { it.re.containsMatchIn(text) }
    return TextSignals(hits.map { it.reason }, hits.sumOf { it.weight })
}
