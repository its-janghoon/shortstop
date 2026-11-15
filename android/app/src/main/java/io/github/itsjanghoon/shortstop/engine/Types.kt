package io.github.itsjanghoon.shortstop.engine

/** Same shapes as engine/src/types.ts. */
enum class Verdict(val wire: String) {
    SAFE("safe"),
    SUSPICIOUS("suspicious"),
    SCAM("scam");

    companion object {
        fun of(s: String): Verdict? = entries.firstOrNull { it.wire == s }
    }
}

enum class Where(val wire: String) {
    ON_DEVICE("on_device"),
    IN_CLOUD("in_cloud"),
    RULES("rules"),
}

/** What a model returns, after parsing and checking. */
data class ModelAnswer(
    val verdict: Verdict,
    val confidence: Double,
    val signals: List<String>,
    val action: String,
    /** Names and addresses the triage model found. Never sent anywhere. */
    val names: List<String> = emptyList(),
    /** Words read from a screenshot (image triage only). Stays on device. */
    val text: String? = null,
)

/** One model call as seen by the engine. The app wraps Firebase AI Logic; tests use a fake. */
data class ModelCall(val text: String, val source: Where)

/** One screenshot. Photos never leave the device. */
class Image(val bytes: ByteArray, val mime: String = "image/png")

interface Models {
    /** Triage pass. Prefers on-device (PREFER_ON_DEVICE, or ONLY_ON_DEVICE in strict mode). Throws when no model can run. */
    suspend fun triage(prompt: String, image: Image? = null): ModelCall

    /** Deep pass. Always in the cloud (ONLY_IN_CLOUD). Only ever receives masked text. */
    suspend fun deep(prompt: String): ModelCall
}

data class EngineConfig(
    val escalateBelowConfidence: Double,
    val onDeviceMaxChars: Int,
    val strictOnDevice: Boolean,
)

enum class MaskKind { EMAIL, PHONE, CARD, ACCOUNT, SSN, RRN, DOB, NAME }

data class MaskEntry(val token: String, val kind: MaskKind, val value: String)

data class LinkFinding(val url: String, val host: String, val reasons: MutableList<String>)

/** One step the engine took, shown to the user as the "receipt". */
data class ReceiptStep(
    val where: Where,
    val what: String,
    val ms: Long,
    /** Exact text that left the device in this step, if any. */
    val sent: String? = null,
)

data class FirstLook(val where: Where, val verdict: Verdict, val confidence: Double)

data class Result(
    val verdict: Verdict,
    val confidence: Double,
    val signals: List<String>,
    val action: String,
    val decidedBy: Where,
    /** The triage (first look) answer, if a model ran. */
    val firstLook: FirstLook?,
    val links: List<LinkFinding>,
    val masked: String,
    val mask: List<MaskEntry>,
    val receipt: List<ReceiptStep>,
)
