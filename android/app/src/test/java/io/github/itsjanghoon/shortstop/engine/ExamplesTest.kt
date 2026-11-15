package io.github.itsjanghoon.shortstop.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Port of engine/test/examples.test.ts: rules alone (no AI) must already get the demo examples right. */
@RunWith(Parameterized::class)
class ExamplesTest(@Suppress("unused") private val id: String, private val ex: Example) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = TestShared.shared.examples.map { arrayOf(it.id, it) }
    }

    @Test
    fun rulesOnly() = runTest {
        val r = TestShared.engine.analyze(Input(text = ex.text))
        when (ex.kind) {
            "scam" -> assertEquals(Verdict.SCAM, r.verdict)
            "safe" -> assertEquals(Verdict.SAFE, r.verdict)
            "mask" -> for (secret in listOf("John Smith", "48 Maple Ave", "(555) 410-7781", "john.smith@acmemail.com", "7720 1938 4410")) {
                assertFalse(secret, r.masked.contains(secret))
            }
        }
    }
}
