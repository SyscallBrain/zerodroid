package pt.tiago.zerodroid.enroll

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.security.PairingPayload
import pt.tiago.zerodroid.security.caFingerprint
import pt.tiago.zerodroid.security.enrollmentSas

class EnrollClientTest {
    private val caPem = javaClass.getResourceAsStream("/fixtures/test-ca.pem")!!
        .bufferedReader().use { it.readText() }
    private val fp = caFingerprint(caPem)
    private fun payload(fingerprint: String = fp) = PairingPayload(
        host = "127.0.0.1", wssPort = 9781, enrollPort = 9782,
        enrollCode = "482913", token = "zc_x", caFp = fingerprint, name = "phone",
    )

    private class FakeHttp(
        var ca: String,
        var response: EnrollResponse? = null,
        var thrown: Exception? = null,
    ) : EnrollHttp {
        var posts = 0
        override suspend fun fetchCa(host: String, port: Int): String = thrown?.let { throw it } ?: ca
        override suspend fun postEnroll(
            host: String, port: Int, pinnedCaPem: String, pairingCode: String, csrPem: String,
        ): EnrollResponse {
            posts++
            thrown?.let { throw it }
            return response ?: error("no response configured")
        }
    }

    @Test
    fun verifiesCaAgainstPayloadFingerprint() = runBlocking {
        val client = EnrollClient(FakeHttp(caPem), csr = { "csr" })
        val ca = client.fetchAndVerify(payload()).getOrThrow()
        assertEquals(caPem, ca.caPem)
        assertEquals(enrollmentSas("482913", fp), ca.sas)
    }

    @Test
    fun fingerprintMismatchAbortsBeforePost() = runBlocking {
        val http = FakeHttp(caPem)
        val result = EnrollClient(http, csr = { "csr" }).fetchAndVerify(payload("00".repeat(32)))
        assertTrue(result.exceptionOrNull()?.message!!.contains("does not match"))
        assertEquals(0, http.posts)
    }

    @Test
    fun responseCaMustEqualVerifiedCa() = runBlocking {
        val otherCa = javaClass.getResourceAsStream("/fixtures/test-ca-other.pem")!!
            .bufferedReader().use { it.readText() }
        val client = EnrollClient(FakeHttp(caPem, response = EnrollResponse("cert", otherCa, "dev", 1)), csr = { "csr" })
        val verified = client.fetchAndVerify(payload()).getOrThrow()
        val result = client.enroll(payload(), verified)
        assertTrue(result.exceptionOrNull()?.message!!.contains("does not match"))
    }

    @Test
    fun httpErrorSurfacesAndPersistsNothing() = runBlocking {
        var persisted = false
        val http = FakeHttp(caPem, thrown = EnrollHttpException(401, "invalid or expired code"))
        val result = EnrollClient(http, csr = { "csr" }).fetchAndVerify(payload())
        assertTrue(result.exceptionOrNull()?.message!!.contains("expired"))
        assertEquals(false, persisted)
    }
}
