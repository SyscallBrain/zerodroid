package pt.tiago.zerodroid.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonProfileCompatTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun oldProfileDecodesAsNative() {
        val p = json.decodeFromString(DaemonProfile.serializer(),
            """{"id":"d","name":"n","host":"h","wssPort":9781,"caPem":"c","certPem":"x","deviceId":"d","issuedAt":1,"notAfter":2}""")
        assertEquals("native", p.authProvider)
        assertFalse(p.isOidc)
        assertNull(p.tokenExpiresAt)
        assertEquals("wss://h:9781/", p.connectUrl())
    }

    @Test
    fun oidcProfileUsesExactWssUrl() {
        val p = DaemonProfile("d", "n", "h", 443, "c", "x", "d", 1, 2, authProvider = "oidc.corp", wssUrl = "wss://h/zc/")
        assertTrue(p.isOidc)
        assertEquals("wss://h/zc/", p.connectUrl())
    }
}
