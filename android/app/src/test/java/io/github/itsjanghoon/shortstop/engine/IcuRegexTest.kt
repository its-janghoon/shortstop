package io.github.itsjanghoon.shortstop.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Android compiles java.util.regex patterns with ICU, which is stricter than the JVM used by these tests:
 * a "{" or "}" that is not part of a {n,m} quantifier must be escaped, or the class holding the pattern
 * fails to load (ExceptionInInitializerError) and the app crashes on the first check.
 * This test reads every top-level Regex in the engine and checks that rule.
 */
class IcuRegexTest {
    private fun patterns(): List<String> {
        TestShared.engine
        val out = mutableListOf<String>()
        fun take(v: Any?) {
            when (v) {
                is Regex -> out.add(v.pattern)
                is List<*> -> v.forEach { x ->
                    if (x is Regex) out.add(x.pattern)
                    else if (x != null && x.javaClass.name.startsWith("io.github")) {
                        x.javaClass.declaredFields.forEach { f -> f.isAccessible = true; (f.get(x) as? Regex)?.let { out.add(it.pattern) } }
                    }
                }
            }
        }
        for (cn in listOf("MaskKt", "LinksKt", "SignalsKt", "EngineKt", "ParseKt")) {
            for (f in Class.forName("io.github.itsjanghoon.shortstop.engine.$cn").declaredFields) {
                if (!Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                take(f.get(null))
            }
        }
        return out
    }

    /** Index of the first brace ICU would reject, or -1. */
    private fun badBrace(p: String): Int {
        var i = 0
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> {
                    i++
                    // \p{Lu}, \x{...} and \N{...} carry their own braces.
                    if (i + 1 < p.length && p[i] in "pPxN" && p[i + 1] == '{') i = p.indexOf('}', i)
                }
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> {
                    val end = p.indexOf('}', i)
                    if (end < 0 || !Regex("""\d+(,\d*)?""").matches(p.substring(i + 1, end))) return i
                    i = end
                }
                c == '}' -> return i
            }
            i++
        }
        return -1
    }

    @Test
    fun everyEnginePatternIsValidForIcu() {
        val all = patterns()
        assertTrue("found ${all.size} patterns", all.size > 30)
        val bad = all.filter { badBrace(it) >= 0 }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun checkerCatchesTheOldTemplatePattern() {
        assertEquals(7, badBrace("""\{(\w+)}"""))
        assertEquals(-1, badBrace("""\{(\w+)\}"""))
        assertEquals(-1, badBrace("""\d{3}[{}]x{2,}\p{Lu}{2,}"""))
    }
}
