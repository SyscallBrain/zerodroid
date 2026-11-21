package pt.tiago.zerodroid.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.security.caFingerprint

class EnrollCaParseTest {
    private val caPem = javaClass.getResourceAsStream("/fixtures/test-ca.pem")!!
        .bufferedReader().use { it.readText() }

    @Test
    fun parsesEscapedJsonCaChain() {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("ca_chain_pem", kotlinx.serialization.json.JsonPrimitive(caPem))
        }.toString()
        assertTrue(body.contains("\\n"))
        val parsed = parseCaChainPem(body)
        assertEquals(caPem.trim(), parsed.trim())
        assertEquals(caFingerprint(caPem), caFingerprint(parsed))
    }
}
