package io.github.itsjanghoon.shortstop

import io.github.itsjanghoon.shortstop.cloud.Answer
import io.github.itsjanghoon.shortstop.cloud.sha256
import io.github.itsjanghoon.shortstop.engine.ModelCall
import io.github.itsjanghoon.shortstop.engine.Models
import io.github.itsjanghoon.shortstop.engine.Deps
import io.github.itsjanghoon.shortstop.engine.Image
import io.github.itsjanghoon.shortstop.engine.Input
import io.github.itsjanghoon.shortstop.engine.TestShared
import io.github.itsjanghoon.shortstop.engine.Where
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class ShareTest {
    @Test
    fun sendToFamilyCarriesTheVerdictButNoSecrets() = runTest {
        // The cloud answer quotes a placeholder; on the phone it shows the real number, the shared text must not.
        val models = object : Models {
            override suspend fun triage(prompt: String, image: Image?) =
                ModelCall("""{"verdict":"suspicious","confidence":0.5,"signals":[],"action":"","names":["Jane Doe"]}""", Where.ON_DEVICE)

            override suspend fun deep(prompt: String) =
                ModelCall("""{"verdict":"scam","confidence":0.93,"signals":["fake bank site"],"action":"Do not call [PHONE_1]."}""", Where.IN_CLOUD)
        }
        val text = TestShared.shared.examples.first { it.id == "bank-locked" }.text
        val r = TestShared.engine.analyze(Input(text), Deps(models = models))
        assertEquals("Do not call 555-201-3344.", r.action)
        val out = familyText(r)
        assertTrue(out.startsWith("Shortstop says: SCAM (93% sure)"))
        assertTrue(out.contains("Do not call [PHONE_1]."))
        for (secret in listOf("Jane Doe", "302918470021", "555-201-3344")) assertFalse(secret, out.contains(secret))
    }

    @Test
    fun reportsStoreOnlyTheSameHashAsTheWebApp() {
        // engine/src/firebase.ts: SHA-256 of the lower-cased site name, hex.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc"))
        assertEquals(sha256("abc"), sha256("ABC"))
    }

    @Test
    fun onDeviceAnswerClassMatchesTheSharedSchema() {
        val schema = TestShared.shared.schema
        val props = schema.getValue("properties").jsonObject.keys
        val fields = Answer::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(props, fields)
        // Required in the schema means the class cannot leave it out; optional ones default to null.
        val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("verdict", "confidence", "signals", "action"), required)
        val minimal = Answer(verdict = "safe", confidence = 1.0, signals = emptyList(), action = "")
        assertEquals(null, minimal.names)
        assertEquals(null, minimal.text)
    }

    @Test
    fun creditLineNamesTheWordListAndItsCopyright() {
        assertEquals(
            "Name finder word list: SCOWL (wamerican), Copyright 2000-2011 by Kevin Atkinson.",
            credits(TestShared.shared.wordsNotice),
        )
    }
}
