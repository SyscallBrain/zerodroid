package pt.tiago.zerodroid.security

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.cert.CertificateFactory

class PinnedTrustManagerTest {
    private val daemonPem = javaClass.getResourceAsStream("/fixtures/daemon-ca.pem")!!
        .bufferedReader().use { it.readText() }

    @Test
    fun parsesTheCaServedByTheDaemon() {
        val tm = TlsFactory.pinnedTrustManager(daemonPem)
        assertNotNull(tm)
    }

    @Test
    fun parsesPemWithCrLf() {
        val tm = TlsFactory.pinnedTrustManager(daemonPem.replace("\n", "\r\n"))
        assertNotNull(tm)
    }
}
