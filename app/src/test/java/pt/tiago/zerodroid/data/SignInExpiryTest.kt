package pt.tiago.zerodroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignInExpiryTest {
    private val native = DaemonProfile("d", "n", "h", 9781, "c", "x", "d", 1, 2)
    private fun oidc(obtained: Long, expires: Long?) =
        native.copy(authProvider = "oidc.corp", tokenObtainedAt = obtained, tokenExpiresAt = expires)

    @Test
    fun nativeIsNotOidc() = assertEquals(SignInStatus.NotOidc, signInStatus(native, 0))

    @Test
    fun hourTokenWarnsFiveMinutesBefore() {
        val p = oidc(0, 3600)
        assertEquals(SignInStatus.Fresh, signInStatus(p, 3299))
        assertEquals(SignInStatus.Expiring(3600), signInStatus(p, 3300))
        assertEquals(SignInStatus.Expiring(3600), signInStatus(p, 3569))
        assertEquals(SignInStatus.Expired, signInStatus(p, 3570))
    }

    @Test
    fun shortTokenWarnsAtHalfLife() {
        val p = oidc(0, 300)
        assertEquals(SignInStatus.Fresh, signInStatus(p, 149))
        assertEquals(SignInStatus.Expiring(300), signInStatus(p, 150))
    }

    @Test
    fun unknownExpiryStaysFresh() = assertEquals(SignInStatus.Fresh, signInStatus(oidc(0, null), 1_000_000))

    @Test
    fun nextCheckIsWarningThenExpiry() {
        val p = oidc(0, 3600)
        assertEquals(3300L, nextSignInCheck(p, 0))
        assertEquals(3570L, nextSignInCheck(p, 3300))
        assertNull(nextSignInCheck(p, 3570))
        assertNull(nextSignInCheck(oidc(0, null), 0))
        assertNull(nextSignInCheck(native, 0))
    }
}
