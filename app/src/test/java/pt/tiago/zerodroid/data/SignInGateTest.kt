package pt.tiago.zerodroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInGateTest {
    private var now = 0L
    private val native = DaemonProfile("d", "n", "h", 9781, "c", "x", "d", 1, 2)
    private val oidc = native.copy(authProvider = "oidc.corp", tokenObtainedAt = 0, tokenExpiresAt = 3600)

    @Test
    fun daemonRefusalHoldsExpiredDespiteTheClock() {
        val gate = SignInGate { now }
        assertTrue(gate.onAuthRequired(oidc, fromCurrentClient = true))
        now = 3400 // the clock alone would say Expiring
        assertEquals(SignInStatus.Expired, gate.status(oidc))
        assertFalse(gate.canConnect(oidc))
    }

    @Test
    fun renewClearsTheRefusal() {
        val gate = SignInGate { now }
        gate.onAuthRequired(oidc, fromCurrentClient = true)
        gate.onRenewed()
        assertEquals(SignInStatus.Fresh, gate.status(oidc))
        assertTrue(gate.canConnect(oidc))
    }

    @Test
    fun staleClientRefusalIsIgnored() {
        val gate = SignInGate { now }
        assertFalse(gate.onAuthRequired(oidc, fromCurrentClient = false))
        assertTrue(gate.canConnect(oidc))
    }

    @Test
    fun nativeProfilesAreNeverGated() {
        val gate = SignInGate { now }
        assertFalse(gate.onAuthRequired(native, fromCurrentClient = true))
        assertTrue(gate.canConnect(native))
        assertEquals(SignInStatus.NotOidc, gate.status(native))
    }

    @Test
    fun clockExpiryBlocksConnecting() {
        now = 3580
        assertFalse(SignInGate { now }.canConnect(oidc))
    }
}
