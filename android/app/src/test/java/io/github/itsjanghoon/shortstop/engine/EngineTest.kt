package io.github.itsjanghoon.shortstop.engine

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of engine/test/engine.test.ts. */
class EngineTest {
    private val engine = TestShared.engine

    private fun answer(verdict: String, confidence: Double, vararg extra: Pair<String, Any>): String {
        val m = mutableMapOf<String, JsonElement>(
            "signals" to JsonArray(emptyList()),
            "action" to JsonPrimitive(""),
            "verdict" to JsonPrimitive(verdict),
            "confidence" to JsonPrimitive(confidence),
        )
        for ((k, v) in extra) {
            m[k] = when (v) {
                is String -> JsonPrimitive(v)
                is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) })
                else -> error("unsupported")
            }
        }
        return JsonObject(m).toString()
    }

    private class Fake(
        val triage: ((String) -> String)? = null,
        val deep: ((String) -> String)? = null,
        val triageSource: Where = Where.ON_DEVICE,
        val noDevice: Boolean = false,
        val defaultTriage: String,
        val defaultDeep: String,
    ) : Models {
        val sent = mutableListOf<String>()

        override suspend fun triage(prompt: String, image: Image?): ModelCall {
            if (noDevice) throw IllegalStateException("no model")
            if (triageSource == Where.IN_CLOUD) sent.add(prompt)
            return ModelCall(triage?.invoke(prompt) ?: defaultTriage, triageSource)
        }

        override suspend fun deep(prompt: String): ModelCall {
            sent.add(prompt)
            return ModelCall(deep?.invoke(prompt) ?: defaultDeep, Where.IN_CLOUD)
        }
    }

    private fun fake(
        triage: ((String) -> String)? = null,
        deep: ((String) -> String)? = null,
        triageSource: Where = Where.ON_DEVICE,
    ) = Fake(triage, deep, triageSource, defaultTriage = answer("safe", 0.95), defaultDeep = answer("scam", 0.9))

    private val scam = "Hi Jane Doe, your Acme Bank account 123456789012 is locked. Verify at acrnebank.com/verify or call 555-201-3344."

    @Test
    fun stopsOnDeviceWhenSure() = runTest {
        val f = fake(triage = { answer("scam", 0.95, "signals" to listOf("asks for money")) })
        val r = engine.analyze(Input("Pay $1.99 now at acme-parcel-redelivery.co"), Deps(models = f))
        assertEquals(Verdict.SCAM, r.verdict)
        assertEquals(Where.ON_DEVICE, r.decidedBy)
        assertEquals(emptyList<String>(), f.sent)
        assertFalse(r.receipt.any { it.sent != null })
    }

    @Test
    fun sendsOnlyMaskedTextToTheCloudWhenUnsure() = runTest {
        val f = fake(
            triage = { answer("suspicious", 0.5, "names" to listOf("Jane Doe")) },
            deep = { answer("scam", 0.92, "action" to "Call Acme Bank, not [PHONE_1].", "signals" to listOf("fake site")) },
        )
        val r = engine.analyze(Input(scam), Deps(models = f))
        assertEquals(Where.IN_CLOUD, r.decidedBy)
        assertEquals(Verdict.SCAM, r.verdict)
        assertEquals(1, f.sent.size)
        for (secret in listOf("Jane Doe", "123456789012", "555-201-3344")) assertFalse(secret, f.sent[0].contains(secret))
        assertTrue(f.sent[0].contains("[NAME_1]"))
        assertEquals("Call Acme Bank, not 555-201-3344.", r.action)
        assertTrue(r.signals.joinToString().contains("looks like acmebank.com"))
    }

    @Test
    fun strictModeNeverCallsTheCloud() = runTest {
        val f = fake(triage = { answer("suspicious", 0.4) })
        val cfg = TestShared.shared.defaults.engine.copy(strictOnDevice = true)
        val r = engine.analyze(Input(scam), Deps(models = f, config = cfg))
        assertEquals(emptyList<String>(), f.sent)
        assertEquals(Where.ON_DEVICE, r.decidedBy)
    }

    @Test
    fun withoutOnDeviceModelCloudSeesOnlyRuleMaskedTextAndTheReceiptSaysSo() = runTest {
        val f = fake(triageSource = Where.IN_CLOUD, triage = { answer("scam", 0.9) })
        val r = engine.analyze(Input(scam), Deps(models = f))
        assertFalse(f.sent[0].contains("123456789012"))
        assertNotNull(r.receipt.first { it.where == Where.IN_CLOUD }.sent)
    }

    @Test
    fun worksWithRulesOnlyWhenNoModelExists() = runTest {
        val r = engine.analyze(Input(scam))
        assertEquals(Where.RULES, r.decidedBy)
        assertEquals(Verdict.SCAM, r.verdict)
    }

    @Test
    fun neverLetsASafeModelAnswerHideALookAlikeLink() = runTest {
        val f = fake(triage = { answer("safe", 0.99) })
        assertEquals(Verdict.SUSPICIOUS, engine.analyze(Input("Login: paypa1.com"), Deps(models = f)).verdict)
    }

    @Test
    fun usesCommunityReports() = runTest {
        val f = fake(triage = { answer("safe", 0.99) })
        val r = engine.analyze(Input("Track it at shipnow-help.com"), Deps(models = f, isReported = { it == "shipnow-help.com" }))
        assertTrue(r.signals.contains(REPORTED_REASON))
        assertEquals(Verdict.SUSPICIOUS, r.verdict)
    }

    @Test
    fun photosNeedOnDeviceAi() = runTest {
        val f = fake(triageSource = Where.IN_CLOUD)
        try {
            engine.analyze(Input(image = Image(byteArrayOf(1))), Deps(models = f))
            fail("expected NeedsOnDeviceException")
        } catch (_: NeedsOnDeviceException) {
        }
    }

    @Test
    fun photosWithoutAnyModelNeedOnDeviceAi() = runTest {
        try {
            engine.analyze(Input(image = Image(byteArrayOf(1))))
            fail("expected NeedsOnDeviceException")
        } catch (_: NeedsOnDeviceException) {
        }
    }

    @Test
    fun photoTextReadOnDeviceIsMaskedBeforeTheCloud() = runTest {
        val f = fake(triage = { answer("suspicious", 0.4, "text" to "Jane Doe call 555-201-3344 now", "names" to listOf("Jane Doe")) })
        val r = engine.analyze(Input(image = Image(byteArrayOf(1))), Deps(models = f))
        assertTrue(f.sent[0].contains("[NAME_1] call [PHONE_1]"))
        assertEquals(Where.IN_CLOUD, r.decidedBy)
    }

    @Test
    fun fallsBackToTriageWhenTheCloudFails() = runTest {
        val models = object : Models {
            override suspend fun triage(prompt: String, image: Image?) = ModelCall(answer("suspicious", 0.5), Where.ON_DEVICE)
            override suspend fun deep(prompt: String): ModelCall = throw IllegalStateException("quota")
        }
        val r = engine.analyze(Input(scam), Deps(models = models))
        assertEquals(Where.ON_DEVICE, r.decidedBy)
        assertTrue(r.receipt.last().what.contains("failed"))
    }

    @Test
    fun parseAnswerDigsJsonOutOfProseAndScalesPercent() {
        val a = parseAnswer("Sure! ```json\n{\"verdict\":\"SCAM\",\"confidence\":92,\"signals\":[\"x\",\"\"],\"action\":\" Hang up. \"}\n```")
        assertEquals(Verdict.SCAM, a.verdict)
        assertEquals(0.92, a.confidence, 1e-9)
        assertEquals(listOf("x"), a.signals)
        assertEquals("Hang up.", a.action)
    }

    @Test
    fun hintsCountMaskedValuesInsteadOfCallingThemWarningSigns() = runTest {
        val prompts = mutableListOf<String>()
        val models = object : Models {
            override suspend fun triage(prompt: String, image: Image?): ModelCall {
                prompts.add(prompt)
                return ModelCall(answer("safe", 0.95), Where.ON_DEVICE)
            }
            override suspend fun deep(prompt: String) = ModelCall(answer("safe", 0.95), Where.IN_CLOUD)
        }
        engine.analyze(Input("Card 4111 1111 1111 1111, account no. 123456789012, SSN 123-45-6789."), Deps(models = models))
        assertTrue(prompts[0].contains("3 private values were masked on device (normal, not a warning sign)"))
        for (old in listOf("card number", "account number", "ID number")) assertFalse(old, prompts[0].contains("message contains a $old") || prompts[0].contains("message mentions an $old"))
    }

    @Test
    fun loginDetailsRuleNeedsAnAskingVerb() {
        assertFalse(textSignals("Patient form. SSN: [SSN_1]. PIN on file.").reasons.contains("asks for private login details"))
        assertTrue(textSignals("Please verify your password here today").reasons.contains("asks for private login details"))
        assertTrue(textSignals("Reply with your PIN to unlock").reasons.contains("asks for private login details"))
    }
}
