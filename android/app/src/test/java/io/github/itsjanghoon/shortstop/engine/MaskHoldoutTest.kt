package io.github.itsjanghoon.shortstop.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Rules-only masking over shared/bench-holdout.json must give exactly the numbers of the TypeScript engine
 * (engine/test/mask-holdout.test.ts prints them). A different count means the port drifted.
 */
class MaskHoldoutTest {
    @Test
    fun sameMissedCountAsTheTypeScriptEngine() {
        TestShared.shared
        val items = Json.parseToJsonElement(File(TestShared.dir, "bench-holdout.json").readText()).jsonObject.getValue("items").jsonArray
        var total = 0
        var missed = 0
        val misses = mutableListOf<String>()
        for (it in items) {
            val o = it.jsonObject
            val text = o.getValue("text").jsonPrimitive.content
            val masked = maskNames(maskByRules(text), guessNames(text)).text
            for (s in o.getValue("secrets").jsonArray.map { x -> x.jsonPrimitive.content }) {
                total++
                if (masked.contains(s)) {
                    missed++
                    misses.add("${o.getValue("id").jsonPrimitive.content}: $s")
                }
            }
        }
        println("""{"total":$total,"missed":$missed}""")
        misses.forEach(::println)
        assertEquals(314, total)
        assertEquals(misses.joinToString("\n"), 13, missed)
    }
}
