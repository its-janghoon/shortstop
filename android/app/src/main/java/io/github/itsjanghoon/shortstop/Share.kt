package io.github.itsjanghoon.shortstop

import io.github.itsjanghoon.shortstop.engine.Result
import io.github.itsjanghoon.shortstop.engine.Verdict
import java.util.Locale

/** Signals and the action are shown unmasked on this phone; anything that leaves it gets the placeholders back. */
fun remask(text: String, r: Result): String {
    var out = text
    for (m in r.mask) out = out.replace(m.value, m.token)
    return out
}

/** A4: the masked message plus the verdict, ready for the share sheet. */
fun familyText(r: Result): String = buildString {
    val word = when (r.verdict) {
        Verdict.SCAM -> "SCAM"
        Verdict.SUSPICIOUS -> "CAREFUL"
        Verdict.SAFE -> "SAFE"
    }
    append("Shortstop says: $word (${String.format(Locale.US, "%.0f", r.confidence * 100)}% sure)\n")
    append(remask(r.action, r)).append('\n')
    if (r.signals.isNotEmpty()) append("Why: ").append(r.signals.joinToString("; ") { remask(it, r) }).append('\n')
    append("\nThe message (private details hidden):\n").append(r.masked)
}
