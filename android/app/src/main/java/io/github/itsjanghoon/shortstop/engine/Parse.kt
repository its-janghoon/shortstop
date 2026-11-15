package io.github.itsjanghoon.shortstop.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** Port of engine/src/parse.ts. Small models sometimes wrap JSON in prose or code fences, so dig it out. */
fun parseAnswer(raw: String): ModelAnswer {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start >= 0 && end > start) { "model answer has no JSON object" }
    val obj: JsonObject = Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject

    val v = obj["verdict"]
    val verdictText = when (v) {
        null, JsonNull -> ""
        is JsonPrimitive -> v.content
        else -> v.toString()
    }.lowercase()
    val verdict = Verdict.of(verdictText) ?: throw IllegalArgumentException("bad verdict: $verdictText")

    var confidence = number(obj["confidence"])
    if (!confidence.isFinite()) confidence = 0.0
    if (confidence > 1 && confidence <= 100) confidence /= 100
    confidence = confidence.coerceIn(0.0, 1.0)

    val text = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    return ModelAnswer(
        verdict = verdict,
        confidence = confidence,
        signals = strings(obj["signals"], 5),
        action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: "",
        names = strings(obj["names"], 10),
        text = text?.ifEmpty { null },
    )
}

/** Same as JavaScript Number(x) for the shapes a model can send. */
private fun number(e: JsonElement?): Double = when (e) {
    null, JsonNull -> 0.0
    is JsonPrimitive -> e.takeUnless { it.isString }?.booleanOrNull?.let { if (it) 1.0 else 0.0 }
        ?: e.content.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
    else -> Double.NaN
}

private fun strings(e: JsonElement?, max: Int): List<String> =
    (e as? JsonArray)?.mapNotNull { x -> (x as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content }
        ?.take(max) ?: emptyList()
