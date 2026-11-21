package pt.tiago.zerodroid.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.proto.InitializeResult

@OptIn(ExperimentalCoroutinesApi::class)
class RpcClientTest {
    private class FakeTransport : WsTransport {
        val sent = mutableListOf<String>()
        var opens = 0
        var listener: WsListener? = null
        override fun open(url: String, listener: WsListener): WsHandle {
            opens++
            this.listener = listener
            listener.onOpen()
            return object : WsHandle {
                override fun send(text: String): Boolean { sent.add(text); return true }
                override fun close(code: Int, reason: String) = Unit
            }
        }
        fun reply(id: Long, result: String) =
            listener!!.onText("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        fun error(id: Long, code: Int, message: String) =
            listener!!.onText("""{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"}}""")
    }

    private fun initReply(id: Long) = """{"jsonrpc":"2.0","id":$id,"result":{"protocol_version":1,"server_version":"0.9.0","server_pid":42,"session_ids":[]}}"""

    @Test
    fun sendsInitializeFirstAndBecomesReady() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this)
        client.start()
        val frame = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject
        assertEquals("initialize", frame["method"]!!.jsonPrimitive.content)
        assertEquals("tok", frame["params"]!!.jsonObject["auth_token"]!!.jsonPrimitive.content)
        val id = frame["id"]!!.jsonPrimitive.content.toLong()
        transport.listener!!.onText(initReply(id))
        assertTrue(client.state.first() is ConnState.Ready)
        client.stop()
    }

    @Test
    fun correlatesConcurrentCallsById() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this)
        client.start()
        val initId = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.listener!!.onText(initReply(initId))
        var a: String? = null; var b: String? = null
        launch { a = client.call<String, String>("x", null, kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<String>()) }
        launch { b = client.call<String, String>("x", null, kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<String>()) }
        val ids = transport.sent.drop(1).map { ZdJson.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content.toLong() }
        transport.reply(ids[1], "\"B\"")
        transport.reply(ids[0], "\"A\"")
        advanceTimeBy(1)
        assertEquals("A", a); assertEquals("B", b)
        client.stop()
    }

    @Test
    fun authRequiredStopsReconnecting() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this)
        client.start()
        val id = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.error(id, -32010, "auth required")
        assertTrue(client.state.first() is ConnState.AuthFailed)
        val opens = transport.opens
        advanceTimeBy(60_000)
        assertEquals(opens, transport.opens)
        client.stop()
    }

    @Test
    fun disconnectFailsPendingCallsAndSchedulesBackoff() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this)
        client.start()
        val id = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.listener!!.onText(initReply(id))
        var failed = false
        launch { try { client.call<Unit, Unit>("x", null, kotlinx.serialization.serializer<Unit>(), kotlinx.serialization.serializer<Unit>()) } catch (e: NotConnectedException) { failed = true } }
        transport.listener!!.onFailure(RuntimeException("net"))
        advanceTimeBy(1)
        assertTrue(failed)
        assertTrue(client.state.first() is ConnState.Backoff)
    }

    @Test
    fun notificationsAreEmitted() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this)
        client.start()
        val collected = mutableListOf<Inbound.Notification>()
        val job = launch { client.notifications.first { collected.add(it); true } }
        transport.listener!!.onText("""{"jsonrpc":"2.0","method":"session/update","params":{"type":"agent_message_chunk"}}""")
        advanceTimeBy(1)
        assertEquals("session/update", collected.first().method)
        job.cancel(); client.stop()
    }
}
