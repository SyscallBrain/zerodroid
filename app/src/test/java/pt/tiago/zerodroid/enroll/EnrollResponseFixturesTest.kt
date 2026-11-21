package pt.tiago.zerodroid.enroll

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.junit.Assert.assertEquals
import org.junit.Test

class EnrollResponseFixturesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesRealDaemonEnrollResponse() {
        val stream = javaClass.getResourceAsStream("/fixtures/enroll-response.json")!!
        val r = json.decodeFromStream<EnrollResponse>(stream)
        assertEquals("dev_", r.deviceId.take(4))
        assertEquals(574, r.caChainPem.length)
        assertEquals(542, r.certPem.length)
        assert(r.notAfter > 1_700_000_000L)
    }
}
