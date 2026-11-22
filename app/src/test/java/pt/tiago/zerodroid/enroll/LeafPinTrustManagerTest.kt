package pt.tiago.zerodroid.enroll

import org.junit.Assert.assertThrows
import org.junit.Test
import pt.tiago.zerodroid.security.parsePemCertificates
import pt.tiago.zerodroid.security.sha256Hex
import java.security.cert.CertificateException

class LeafPinTrustManagerTest {
    private fun pem(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().use { it.readText() }
    private val leaf = parsePemCertificates(pem("test-ca.pem")).first()
    private val other = parsePemCertificates(pem("test-ca-other.pem")).first()

    @Test
    fun acceptsPinnedLeaf() = LeafPinTrustManager(sha256Hex(leaf.encoded)).checkServerTrusted(arrayOf(leaf), "ECDHE_ECDSA")

    @Test
    fun rejectsOtherLeaf() {
        assertThrows(CertificateException::class.java) {
            LeafPinTrustManager(sha256Hex(leaf.encoded)).checkServerTrusted(arrayOf(other), "ECDHE_ECDSA")
        }
    }

    @Test
    fun rejectsEmptyChain() {
        assertThrows(CertificateException::class.java) {
            LeafPinTrustManager(sha256Hex(leaf.encoded)).checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
        }
    }
}
