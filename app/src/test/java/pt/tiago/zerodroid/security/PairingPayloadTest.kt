package pt.tiago.zerodroid.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {
    private val fp = "ab".repeat(32)
    private fun link(extra: String = "") =
        "zerodroid://pair?v=1&name=phone&host=127.0.0.1&wss=9781&enroll=9782&code=482913&token=zc_x&ca_fp=$fp$extra"

    @Test
    fun parsesDeepLink() {
        val p = PairingPayload.parse(link()).getOrThrow()
        assertEquals("phone", p.name)
        assertEquals("127.0.0.1", p.host)
        assertEquals(9781, p.wssPort)
        assertEquals(9782, p.enrollPort)
        assertEquals("482913", p.enrollCode)
        assertEquals("zc_x", p.token)
        assertEquals(fp, p.caFp)
    }

    @Test
    fun parsesBareQueryString() {
        val raw = link().substringAfter('?')
        assertEquals("zc_x", PairingPayload.parse(raw).getOrThrow().token)
    }

    @Test
    fun rejectsMalformedPayloads() {
        assertTrue(PairingPayload.parse(link().replace("&token=zc_x", "")).exceptionOrNull()?.message!!.contains("token"))
        assertTrue(PairingPayload.parse(link().replace("ca_fp=$fp", "ca_fp=xyz")).exceptionOrNull()?.message!!.contains("fingerprint"))
        assertTrue(PairingPayload.parse(link().replace("v=1", "v=2")).exceptionOrNull()?.message!!.contains("version"))
        assertTrue(PairingPayload.parse(link().replace("wss=9781", "wss=0")).exceptionOrNull()?.message!!.contains("port"))
        assertTrue(PairingPayload.parse(link().replace("zerodroid://pair", "http://pair")).exceptionOrNull()?.message!!.contains("link"))
        assertTrue(PairingPayload.parse("").exceptionOrNull()?.message!!.contains("empty"))
    }

    @Test
    fun parsesLinkFromPairPhoneScript() {
        // Exact parameter set emitted by tools/pair-phone.sh.
        val raw = "zerodroid://pair?v=1&code=AbCdEf0123456789AbCdEf0123456789" +
            "&token=zc%2Btoken%3D%3D&host=100.64.0.10&wss=9781&enroll=9782" +
            "&ca_fp=$fp&name=Meu%20telemovel"
        val p = PairingPayload.parse(raw).getOrThrow()
        assertEquals("AbCdEf0123456789AbCdEf0123456789", p.enrollCode)
        assertEquals("zc+token==", p.token)
        assertEquals("100.64.0.10", p.host)
        assertEquals(9781, p.wssPort)
        assertEquals(9782, p.enrollPort)
        assertEquals("Meu telemovel", p.name)
    }

    @Test
    fun toStringRedactsSecrets() {
        val s = PairingPayload.parse(link()).getOrThrow().toString()
        assertFalse(s.contains("482913"))
        assertFalse(s.contains("zc_x"))
    }
}
