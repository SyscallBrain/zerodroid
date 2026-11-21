package pt.tiago.zerodroid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertRenewalTest {
    @Test
    fun renewalDueAtHalfLifetime() {
        assertFalse(renewalDue(issuedAt = 0, notAfter = 1_000, now = 499))
        assertTrue(renewalDue(issuedAt = 0, notAfter = 1_000, now = 500))
        assertTrue(renewalDue(issuedAt = 0, notAfter = 1_000, now = 2_000))
    }

    @Test
    fun secondsBasedThirtyDayCertIsNotDueWhenFresh() {
        val issued = 1_795_000_000L
        assertFalse(renewalDue(issued, issued + 30 * 86_400, issued + 3_600))
    }

    @Test
    fun missingExpiryIsNeverDue() {
        assertFalse(renewalDue(0, 0, 5))
    }
}
