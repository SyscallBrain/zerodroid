package pt.tiago.zerodroid.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import pt.tiago.zerodroid.data.DaemonProfile

class RenewKeyTest {
    private val p = DaemonProfile("dev-1", "h", "h", 9781, "c", "x", "dev-1", 1, 2,
        authProvider = "oidc.corp", gatewayUrl = "http://127.0.0.1:42790", tokenObtainedAt = 100)

    @Test
    fun aNewSignInGetsAFreshRenewViewModel() {
        // Unpair + sign in again resets the saveable generation to 0: the key must still change.
        val signedInAgain = p.copy(id = "dev-2", deviceId = "dev-2", gatewayUrl = "https://127.0.0.1:42793", tokenObtainedAt = 500)
        assertNotEquals(renewViewModelKey(p, 0), renewViewModelKey(signedInAgain, 0))
    }

    @Test
    fun aRenewedTokenGetsAFreshRenewViewModel() =
        assertNotEquals(renewViewModelKey(p, 0), renewViewModelKey(p.copy(tokenObtainedAt = 400), 0))

    @Test
    fun sameProfileAndGenerationReuse() = assertEquals(renewViewModelKey(p, 3), renewViewModelKey(p.copy(), 3))
}
