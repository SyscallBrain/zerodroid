package pt.tiago.zerodroid.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun encodesRequestWithJsonrpc20() {
        val frame = encodeRequest(7, "status", null)
        val obj = json.parseToJsonElement(frame).jsonObject
        assertEquals("2.0", obj["jsonrpc"]!!.let { json.parseToJsonElement(it.toString()) }.let { it.toString().trim('"') })
        assertEquals(7L, obj["id"]!!.toString().toLong())
        assertEquals("status", obj["method"]!!.toString().trim('"'))
        assertTrue("must not contain params key", !obj.containsKey("params"))
    }

    @Test
    fun decodesResponse() {
        val inbound = decodeInbound("""{"jsonrpc":"2.0","id":1,"result":{"ok":true}}""")
        assertTrue(inbound is Inbound.Response)
        val r = inbound as Inbound.Response
        assertEquals(1L, r.id)
        assertEquals(true, json.parseToJsonElement(r.result.toString()).jsonObject["ok"].toString().trim().toBoolean())
    }

    @Test
    fun decodesErrorWithCode() {
        val inbound = decodeInbound("""{"jsonrpc":"2.0","id":2,"error":{"code":-32010,"message":"auth required"}}""")
        assertTrue(inbound is Inbound.Failure)
        val f = inbound as Inbound.Failure
        assertEquals(2L, f.id)
        assertEquals(RpcCodes.AUTH_REQUIRED, f.error.code)
        assertEquals("auth required", f.error.message)
    }

    @Test
    fun decodesNotification() {
        val inbound = decodeInbound("""{"jsonrpc":"2.0","method":"session/update","params":{"type":"x"}}""")
        assertTrue(inbound is Inbound.Notification)
        val n = inbound as Inbound.Notification
        assertEquals("session/update", n.method)
        assertEquals("x", n.params.toString().let {
            json.parseToJsonElement(it).jsonObject["type"]!!.toString().trim('"')
        })
    }

    @Test
    fun garbageFrameReturnsNull() {
        assertNull(decodeInbound("not json"))
        assertNull(decodeInbound("{}"))
        assertNull(decodeInbound("""{"jsonrpc":"2.0"}"""))
    }
}