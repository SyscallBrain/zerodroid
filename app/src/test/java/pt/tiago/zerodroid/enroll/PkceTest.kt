package pt.tiago.zerodroid.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun rfc7636VerifierMatchesIndependentSha256() = assertEquals(
        "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
    )

    @Test
    fun verifierIs43UrlSafeChars() {
        val v = Pkce.newVerifier()
        assertEquals(43, v.length)
        assertTrue(v, v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun verifiersDiffer() = assertNotEquals(Pkce.newVerifier(), Pkce.newVerifier())
}
