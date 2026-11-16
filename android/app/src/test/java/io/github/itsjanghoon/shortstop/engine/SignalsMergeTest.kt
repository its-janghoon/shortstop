package io.github.itsjanghoon.shortstop.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of engine/test/signals-merge.test.ts. */
class SignalsMergeTest {
    @Test
    fun keepsOneReasonPerTopicAndAllLinkFindings() {
        val out = mergeSignals(
            listOf("looks like acmebank.com but is acrnebank.com"),
            listOf("Claims account is locked", "Fake website URL using typo-squatting", "Urges immediate verification", "says your account is locked", "pushes you to hurry"),
        )
        assertEquals(listOf("looks like acmebank.com but is acrnebank.com", "Claims account is locked", "Urges immediate verification"), out)
    }

    @Test
    fun dropsExactRepeatsAndCapsAtFive() {
        assertEquals(listOf("a", "b", "c", "d", "e"), mergeSignals(emptyList(), listOf("a", "A", "b", "c", "d", "e", "f")))
    }
}
