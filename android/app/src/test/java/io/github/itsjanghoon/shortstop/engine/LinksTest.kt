package io.github.itsjanghoon.shortstop.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of engine/test/links.test.ts. */
class LinksTest {
    private val links = TestShared.engine.links

    private fun reasons(raw: String) = links.checkLink(raw).reasons.joinToString(",")

    @Test
    fun findsLinksWithAndWithoutSchemeNotEmailDomains() {
        assertEquals(listOf("acme-parcel-redelivery.co/pay"), extractLinks("Pay at acme-parcel-redelivery.co/pay now. Mail me at a@b.com."))
        assertEquals(listOf("https://acmebank.com/login"), extractLinks("See https://acmebank.com/login."))
    }

    @Test
    fun trustsTheRealDomain() {
        assertEquals(emptyList<String>(), links.checkLink("https://www.acmebank.com/login").reasons)
    }

    @Test
    fun flagsLookAlikeHosts() {
        assertTrue(reasons("https://acrnebank.com").contains("looks like acmebank.com"))
        assertTrue(reasons("paypa1.com").contains("looks like paypal.com"))
        assertTrue(reasons("acmebank-secure-login.xyz").contains("looks like acmebank.com"))
        assertTrue(reasons("acmebank.com.verify-account.top").contains(".top"))
    }

    @Test
    fun flagsPunycodeIpsShortenersAndPlainHttp() {
        assertTrue(reasons("https://xn--acmebnk-8ya.com").contains("another alphabet"))
        assertTrue(reasons("http://192.168.0.10/login").contains("IP address"))
        assertTrue(reasons("bit.ly/3abc").contains("shortened"))
        assertTrue(reasons("http://example.org").contains("not secure"))
    }

    @Test
    fun flagsLookAlikeLettersFromAnotherAlphabet() {
        // Cyrillic "а" in place of Latin "a": the host becomes punycode, like a browser shows it.
        assertTrue(reasons("https://\u0430cmebank.com/login").contains("another alphabet"))
    }
}
