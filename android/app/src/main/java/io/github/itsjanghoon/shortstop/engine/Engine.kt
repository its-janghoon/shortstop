package io.github.itsjanghoon.shortstop.engine

import java.util.Locale
import kotlin.math.roundToLong

/** Port of engine/src/engine.ts: rules first, then triage on device, then (only if unsure) masked text to the cloud. */

data class Input(
    val text: String? = null,
    /** One screenshot. Photos never leave the device: they need an on-device model. */
    val image: Image? = null,
)

class Deps(
    val models: Models? = null,
    val config: EngineConfig? = null,
    /** Firestore lookup: has this registrable domain been reported by others? */
    val isReported: (suspend (String) -> Boolean)? = null,
    val now: () -> Double = { System.nanoTime() / 1e6 },
)

class NeedsOnDeviceException : Exception("Photo checks need on-device AI. Paste the text instead.")

private fun worst(a: Verdict, b: Verdict) = if (a.ordinal >= b.ordinal) a else b

private val ACTIONS = mapOf(
    Verdict.SCAM to "Do not click or reply. If it names your bank or a company, call them using the number on their official website or card.",
    Verdict.SUSPICIOUS to "Wait before you act. Check with the company using a number you already trust.",
    Verdict.SAFE to "No warning signs found. Still never share codes or passwords.",
)

// Both braces escaped: Android compiles regexes with ICU, which rejects a bare "}" (the JVM accepts it).
private val VAR = Regex("""\{(\w+)\}""")

private fun fill(tpl: String, vars: Map<String, String>) = VAR.replace(tpl) { vars[it.groupValues[1]] ?: "" }

private fun strongLink(reasons: List<String>) =
    reasons.any { it.startsWith("looks like") || it.contains("another alphabet") || it == REPORTED_REASON }

private fun chunks(text: String, max: Int, limit: Int = 4): List<String> {
    if (text.length <= max) return listOf(text)
    val out = mutableListOf<String>()
    var i = 0
    while (i < text.length && out.size < limit) {
        out.add(text.substring(i, minOf(text.length, i + max)))
        i += max
    }
    return out
}

private fun fixed2(d: Double) = String.format(Locale.US, "%.2f", d)

class Engine(private val shared: Shared) {
    val links = LinkChecker(shared.known)

    private fun hintsFor(links: List<LinkFinding>, masked: Masked, words: TextSignals): String {
        val out = words.reasons.toMutableList()
        for (l in links) out.addAll(l.reasons)
        // Private data in a message is not a scam sign by itself (forms, contracts, receipts have it), so it is not a hint.
        if (masked.entries.isNotEmpty()) out.add("${masked.entries.size} private values were masked on device (normal, not a warning sign)")
        return if (out.isEmpty()) "none" else out.joinToString("; ")
    }

    /** Rules-only answer, used when no model can run at all. */
    private fun rulesAnswer(links: List<LinkFinding>, words: TextSignals): ModelAnswer {
        val linkReasons = links.flatMap { it.reasons }
        val strong = strongLink(linkReasons) || words.score >= 3
        val verdict = when {
            strong -> Verdict.SCAM
            linkReasons.isNotEmpty() || words.score > 0 -> Verdict.SUSPICIOUS
            else -> Verdict.SAFE
        }
        return ModelAnswer(verdict, if (strong) 0.7 else 0.3, (linkReasons + words.reasons).take(5), ACTIONS.getValue(verdict))
    }

    suspend fun analyze(input: Input, deps: Deps = Deps()): Result {
        val cfg = deps.config ?: shared.defaults.engine
        val now = deps.now
        val receipt = mutableListOf<ReceiptStep>()
        fun <T> step(where: Where, what: String, fn: () -> T): T {
            val t = now()
            val out = fn()
            receipt.add(ReceiptStep(where, what, (now() - t).roundToLong()))
            return out
        }

        var raw = input.text?.trim() ?: ""
        require(raw.isNotEmpty() || input.image != null) { "nothing to check" }
        if (input.image != null && deps.models == null) throw NeedsOnDeviceException()

        // 1. Rules, on device, always.
        var masked = step(Where.RULES, "Hid private data with rules") { maskNames(maskByRules(raw), guessNames(raw)) }
        var links = step(Where.RULES, "Checked links") { this.links.checkLinks(raw) }
        var words = textSignals(raw)
        deps.isReported?.let { reported ->
            for (l in links) if (reported(registrable(l.host))) l.reasons.add(REPORTED_REASON)
        }

        var triage: ModelAnswer? = null
        var triageWhere = Where.ON_DEVICE

        fun finish(a: ModelAnswer, decidedBy: Where): Result {
            var verdict = a.verdict
            val linkReasons = links.flatMap { it.reasons }
            // Safety net: a model "safe" never hides a fake link or a pile of scam tricks.
            if ((strongLink(linkReasons) || words.score >= 3) && verdict == Verdict.SAFE) verdict = Verdict.SUSPICIOUS
            val extra = if (verdict == Verdict.SAFE) emptyList() else words.reasons
            val signals = (linkReasons + a.signals + extra).map { unmask(it, masked.entries) }.distinct().take(5)
            val action = if (a.action.isNotEmpty() && a.verdict == verdict) unmask(a.action, masked.entries) else ACTIONS.getValue(verdict)
            val t = triage
            return Result(
                verdict = verdict,
                confidence = a.confidence,
                signals = signals,
                action = action,
                decidedBy = decidedBy,
                firstLook = t?.let { FirstLook(triageWhere, it.verdict, it.confidence) },
                links = links,
                masked = masked.text,
                mask = masked.entries,
                receipt = receipt,
            )
        }

        val models = deps.models ?: return finish(rulesAnswer(links, words), Where.RULES)

        // 2. Triage. Prefer on device. Photos must stay on device.
        val image = input.image
        val parts = if (image != null) listOf(masked.text) else chunks(masked.text, cfg.onDeviceMaxChars)
        val names = mutableListOf<String>()
        for (part in parts) {
            val tpl = if (image != null) shared.prompts.image else shared.prompts.triage
            val prompt = fill(tpl, mapOf("text" to part.ifEmpty { "(no text, read the picture)" }, "hints" to hintsFor(links, masked, words)))
            val t = now()
            val call = try {
                models.triage(prompt, image)
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                if (image != null) throw NeedsOnDeviceException()
                receipt.add(ReceiptStep(Where.ON_DEVICE, "On-device model unavailable (${e.message})", 0))
                break
            }
            if (image != null && call.source != Where.ON_DEVICE) throw NeedsOnDeviceException()
            triageWhere = call.source
            receipt.add(
                ReceiptStep(
                    where = call.source,
                    what = if (call.source == Where.ON_DEVICE) "First look on device" else "First look in the cloud (no on-device model)",
                    ms = (now() - t).roundToLong(),
                    sent = if (call.source == Where.IN_CLOUD) prompt else null,
                ),
            )
            val a = try {
                parseAnswer(call.text)
            } catch (_: Exception) {
                ModelAnswer(Verdict.SUSPICIOUS, 0.0, emptyList(), "")
            }
            names.addAll(a.names)
            if (image != null && a.text != null) {
                raw = a.text
                masked = maskByRules(raw, masked.entries)
                links = this.links.checkLinks(raw)
                words = textSignals(raw)
            }
            val prev = triage
            triage = if (prev == null) {
                a
            } else {
                prev.copy(
                    verdict = worst(prev.verdict, a.verdict),
                    confidence = minOf(prev.confidence, a.confidence),
                    signals = prev.signals + a.signals,
                )
            }
        }
        masked = maskNames(masked, names)

        val tri = triage
        val long = masked.text.length > cfg.onDeviceMaxChars
        val unsure = tri == null || tri.confidence < cfg.escalateBelowConfidence || tri.verdict == Verdict.SUSPICIOUS
        if ((!unsure && !long) || cfg.strictOnDevice) {
            return finish(tri ?: rulesAnswer(links, words), if (tri != null) triageWhere else Where.RULES)
        }

        // 3. Deep look in the cloud. Only masked text leaves the device.
        val triageNote = tri?.let { "${it.verdict.wire} (${fixed2(it.confidence)}): ${it.signals.joinToString("; ")}" } ?: "not available"
        var prompt = fill(
            shared.prompts.deep,
            mapOf("text" to masked.text, "hints" to hintsFor(links, masked, words), "triage" to maskValues(triageNote, masked)),
        )
        // Should never happen: the triage note could quote private data. Mask it again.
        if (leaks(prompt, masked.entries).isNotEmpty()) prompt = maskValues(prompt, masked)
        return try {
            val t = now()
            val call = models.deep(prompt)
            receipt.add(ReceiptStep(Where.IN_CLOUD, "Deep check in the cloud (masked text only)", (now() - t).roundToLong(), prompt))
            finish(parseAnswer(call.text), Where.IN_CLOUD)
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            receipt.add(ReceiptStep(Where.IN_CLOUD, "Cloud check failed (${e.message})", 0))
            finish(tri ?: rulesAnswer(links, words), if (tri != null) triageWhere else Where.RULES)
        }
    }
}

/** Make sure text built on device carries placeholders, not values. */
private fun maskValues(note: String, masked: Masked): String {
    var out = note
    for (e in masked.entries) out = out.replace(e.value, e.token)
    return out
}
