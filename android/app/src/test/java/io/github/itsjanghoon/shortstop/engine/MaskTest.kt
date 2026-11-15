package io.github.itsjanghoon.shortstop.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of engine/test/mask.test.ts. */
class MaskTest {
    init {
        TestShared.shared // installs the word list
    }

    @Test
    fun hidesEmailPhoneCardAndAccount() {
        val raw = "Hi, write to jane.doe@acmemail.com or call (555) 201-3344. Card 4111 1111 1111 1111, account no. 123456789012."
        val m = maskByRules(raw)
        for (t in listOf("[EMAIL_1]", "[PHONE_1]", "[CARD_1]", "[ACCOUNT_1]", "account no.")) assertTrue(t, m.text.contains(t))
        assertEquals(emptyList<String>(), leaks(m.text, m.entries))
        assertEquals(raw, unmask(m.text, m.entries))
    }

    @Test
    fun doesNotCallRandomNumbersCards() {
        assertFalse(luhn("1234 5678 9012 3456"))
        assertFalse(maskByRules("Order 1234 5678 9012 3456 shipped").text.contains("[CARD_"))
    }

    @Test
    fun hidesUsSsnAndKoreanRrn() {
        assertEquals("SSN [SSN_1] and [RRN_1]", maskByRules("SSN 123-45-6789 and 900101-1234567").text)
    }

    @Test
    fun reusesTheSameTokenForTheSameValue() {
        assertEquals("Call [PHONE_1]. Again: [PHONE_1].", maskByRules("Call 555-201-3344. Again: 555-201-3344.").text)
    }

    @Test
    fun hidesNamesTheModelFoundOnlyWhenPresentLongestFirst() {
        val m = maskNames(maskByRules("Jane Doe lives at 12 Oak St. Jane will pay."), listOf("Jane Doe", "Jane", "Bob", "12 Oak St"))
        assertEquals("[NAME_2] lives at [NAME_1]. [NAME_3] will pay.", m.text)
        assertEquals(listOf("12 Oak St", "Jane Doe", "Jane"), m.entries.map { it.value })
    }

    @Test
    fun doesNotHidePartOfALongerWord() {
        assertEquals("Annabel met [NAME_1].", maskNames(maskByRules("Annabel met Ann."), listOf("Ann")).text)
    }

    @Test
    fun guessNamesFindsLabelledNamesAndStreetAddresses() {
        val t = "Tenant: John Smith, 48 Maple Ave, Springfield. Dear Jane Doe, thanks."
        // Unknown capitalized words such as the town name are masked too: extra masking is safe.
        assertTrue(guessNames(t).containsAll(listOf("John Smith", "Jane Doe", "48 Maple Ave")))
    }
}
