package pt.tiago.zerodroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DaemonKeyTest {
    private fun pem(body: String) = "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"

    private fun profile(ca: String, cert: String = "c1", device: String = "d1", host: String = "100.1.1.1") = DaemonProfile(
        id = device, name = "phone", host = host, wssPort = 9781, caPem = ca, certPem = cert,
        deviceId = device, issuedAt = 1, notAfter = 2,
    )

    @Test
    fun pairingTheSameDaemonAgainKeepsTheKey() {
        val first = profile(pem("QUJDREVG"))
        val again = profile(pem("QUJDREVG"), cert = "c2", device = "d2", host = "100.9.9.9")
        assertEquals(first.daemonKey(), again.daemonKey())
    }

    @Test
    fun anotherDaemonHasAnotherKey() {
        assertNotEquals(profile(pem("QUJDREVG")).daemonKey(), profile(pem("R0hJSktM")).daemonKey())
    }

    @Test
    fun anUnreadableCaFallsBackToTheUrl() {
        assertEquals("url:wss://100.1.1.1:9781/", profile("").daemonKey())
    }
}
