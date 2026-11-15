package io.github.itsjanghoon.shortstop.cloud

import com.google.firebase.ai.annotations.Generable
import com.google.firebase.ai.annotations.Guide
import kotlinx.serialization.Serializable

/**
 * The answer shape from shared/verdict.schema.json, as a @Generable class.
 * Firebase AI Logic needs this form to constrain the on-device model (ML Kit Prompt API);
 * annotations must be compile-time constants, so the fields are mirrored here and a unit test
 * checks they match the shared schema. The cloud deep check builds its schema from the JSON file itself.
 */
@Serializable
@Generable(description = "Scam check answer for one message")
data class Answer(
    @Guide(description = "scam, suspicious or safe", enumValues = ["safe", "suspicious", "scam"])
    val verdict: String,
    @Guide(description = "How sure, 0 to 1", minimum = 0.0, maximum = 1.0)
    val confidence: Double,
    @Guide(description = "Up to 5 short reasons", maxItems = 5)
    val signals: List<String>,
    @Guide(description = "One short sentence telling an older person what to do")
    val action: String,
    @Guide(description = "Person names and street addresses found in the text, copied exactly", maxItems = 10)
    val names: List<String>? = null,
    @Guide(description = "Words read from a screenshot. Image triage only.")
    val text: String? = null,
) {
    companion object
}
