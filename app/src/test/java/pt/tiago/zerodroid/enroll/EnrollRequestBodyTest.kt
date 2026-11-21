package pt.tiago.zerodroid.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class EnrollRequestBodyTest {
    private val csr = """
        -----BEGIN CERTIFICATE REQUEST-----
        MIHOMHYCAQAwFDESMBAGA1UEAwwJemVyb2Ryb2lkMFkwEwYHKoZIzj0CAQYIKoZI
        zj0DAQcDQgAE8fr85iiH94/qldzvXq0+eYzZq0knyv/e3soj8by4bUgqJl2WWyXs
        UaUI241gOk+vqi+tWluX8TwpPQJ7U1mII6AAMAoGCCqGSM49BAMCA0gAMEUCIQCj
        RJ7T58YKVNs0fwZDpzlgHK90JVqyy36uvXjWWRnFHQIgIwwWcwdi5XBrSoMxi+Yb
        +l/EZcDOFFhBtUa+VauY3nc=
        -----END CERTIFICATE REQUEST-----
    """.trimIndent()

    @Test
    fun csrWithNewlinesIsValidJson() {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("pairing_code", "code")
            put("csr_pem", csr)
        }.toString()
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals(csr, parsed["csr_pem"].toString().trim('"').replace("\\n", "\n"))
        assertTrue(body.contains("\\n"))
    }
}
