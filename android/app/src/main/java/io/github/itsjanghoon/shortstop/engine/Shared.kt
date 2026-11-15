package io.github.itsjanghoon.shortstop.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Prompts(val triage: String, val image: String, val deep: String)

data class Example(val id: String, val label: String, val kind: String, val text: String)

data class KnownDomains(val domains: List<String>, val riskyTlds: List<String>, val shorteners: List<String>)

/** Remote Config keys with their defaults from shared/config.defaults.json. */
data class AppConfig(
    val engine: EngineConfig,
    val cloudModel: String,
    val reportMinCount: Int,
)

/**
 * Everything in ../shared, the same files the web app and the extension read.
 * The app copies them into assets/shared at build time; tests read them from disk.
 */
class Shared(
    val prompts: Prompts,
    val examples: List<Example>,
    val known: KnownDomains,
    val defaults: AppConfig,
    /** Raw config.defaults.json values (without $comment), used as Remote Config defaults. */
    val defaultsMap: Map<String, Any>,
    /** shared/verdict.schema.json, used to build the cloud response schema. */
    val schema: JsonObject,
    /** shared/words.NOTICE, the license of the word list. */
    val wordsNotice: String = "",
) {
    companion object {
        val FILES = listOf("prompts.json", "examples.json", "known-domains.json", "config.defaults.json", "verdict.schema.json")

        fun load(read: (String) -> String): Shared {
            Words.install { read("words.txt") }
            val json = { name: String -> Json.parseToJsonElement(read(name)).jsonObject }
            val p = json("prompts.json")
            val ex = json("examples.json")
            val k = json("known-domains.json")
            val c = json("config.defaults.json")
            val list = { o: JsonObject, key: String -> o.getValue(key).jsonArray.map { it.jsonPrimitive.content } }
            val str = { o: JsonObject, key: String -> o.getValue(key).jsonPrimitive.content }
            val defaultsMap = c.filterKeys { !it.startsWith("$") }.mapValues { (_, v) ->
                val prim = v.jsonPrimitive
                when {
                    prim.isString -> prim.content
                    prim.content == "true" || prim.content == "false" -> prim.boolean
                    else -> prim.double
                }
            }
            return Shared(
                prompts = Prompts(str(p, "triage"), str(p, "image"), str(p, "deep")),
                examples = ex.getValue("examples").jsonArray.map {
                    val o = it.jsonObject
                    Example(str(o, "id"), str(o, "label"), str(o, "kind"), str(o, "text"))
                },
                known = KnownDomains(list(k, "domains"), list(k, "risky_tlds"), list(k, "shorteners")),
                defaults = AppConfig(
                    engine = EngineConfig(
                        escalateBelowConfidence = c.getValue("escalate_below_confidence").jsonPrimitive.double,
                        onDeviceMaxChars = c.getValue("on_device_max_chars").jsonPrimitive.int,
                        strictOnDevice = c.getValue("strict_on_device").jsonPrimitive.boolean,
                    ),
                    cloudModel = str(c, "cloud_model"),
                    reportMinCount = c.getValue("report_min_count").jsonPrimitive.int,
                ),
                defaultsMap = defaultsMap,
                schema = json("verdict.schema.json"),
                wordsNotice = runCatching { read("words.NOTICE") }.getOrDefault(""),
            )
        }
    }
}
