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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    @Test
    fun initializeAdvertisesElicitationFormCapability() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this)
        client.start()
        // The daemon renames this one field to camelCase on the wire; snake_case would be ignored.
        val params = initParams(transport)
        assertFalse(params.containsKey("client_capabilities"))
        val caps = params["clientCapabilities"]!!.jsonObject
        assertTrue(caps["elicitation"]!!.jsonObject.containsKey("form"))
        client.stop()
    }

    @Test
    fun inboundRequestsAreEmittedAndAnsweredWithEchoedId() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this)
        client.start()
        val initId = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.listener!!.onText(initReply(initId))
        assertTrue(client.state.first() is ConnState.Ready)

        val collected = mutableListOf<Inbound.Request>()
        val job = launch { client.inboundRequests.first { collected.add(it); true } }
        transport.listener!!.onText("""{"jsonrpc":"2.0","id":"req-1","method":"elicitation/create","params":{"sessionId":"s","mode":"form","message":"?","requestedSchema":{}}}""")
        advanceTimeBy(1)
        assertEquals("elicitation/create", collected.first().method)

        assertTrue(client.respond(collected.first().id, kotlinx.serialization.json.buildJsonObject { put("action", "cancel") }))
        val response = ZdJson.parseToJsonElement(transport.sent.last()).jsonObject
        assertEquals("req-1", response["id"]!!.jsonPrimitive.content)
        assertEquals("cancel", response["result"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        assertFalse(response.containsKey("method"))

        assertTrue(client.respondError(collected.first().id, RpcCodes.METHOD_NOT_FOUND, "nope"))
        val err = ZdJson.parseToJsonElement(transport.sent.last()).jsonObject
        assertEquals("req-1", err["id"]!!.jsonPrimitive.content)
        assertEquals(-32601, err["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        job.cancel(); client.stop()
    }

    @Test
    fun multiSelectElicitationRoundTrip() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this)
        client.start()
        val initId = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.listener!!.onText(initReply(initId))
        assertTrue(client.state.first() is ConnState.Ready)

        // Daemon sends a multi-select elicitation/create (poll with multi_select on channel rpc).
        val schema = """{"type":"object","properties":{"choices":{"type":"array","minItems":1,"maxItems":3,"items":{"anyOf":[{"const":"choice-0","title":"Red"},{"const":"choice-1","title":"Green"},{"const":"choice-2","title":"Blue"}]}}},"required":["choices"]}"""
        val req = """{"jsonrpc":"2.0","id":"zc-req-7","method":"elicitation/create","params":{"sessionId":"s","mode":"form","message":"Pick colors","requestedSchema":$schema}}"""
        val collected = mutableListOf<Inbound.Request>()
        val job = launch { client.inboundRequests.first { collected.add(it); true } }
        transport.listener!!.onText(req)
        advanceTimeBy(1)
        val r = collected.first()
        assertEquals("zc-req-7", r.id.jsonPrimitive.content)

        // Answer accept with two consts; the frame must echo the string id and carry a choices array.
        val result = kotlinx.serialization.json.buildJsonObject {
            put("action", "accept")
            put("content", kotlinx.serialization.json.buildJsonObject {
                put("choices", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("choice-0"))
                    add(kotlinx.serialization.json.JsonPrimitive("choice-2"))
                })
            })
        }
        assertTrue(client.respond(r.id, result))
        val response = ZdJson.parseToJsonElement(transport.sent.last()).jsonObject
        assertEquals("zc-req-7", response["id"]!!.jsonPrimitive.content)
        val content = response["result"]!!.jsonObject["content"]!!.jsonObject
        assertEquals(listOf("choice-0", "choice-2"), content["choices"]!!.jsonArray.map { it.jsonPrimitive.content })
        job.cancel(); client.stop()
    }

    private fun initParams(t: FakeTransport) = ZdJson.parseToJsonElement(t.sent.first()).jsonObject["params"]!!.jsonObject

    @Test
    fun initializeCarriesAuthProviderWhenSet() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this, authProvider = { "oidc.corp" })
        client.start()
        assertEquals("oidc.corp", initParams(transport)["auth_provider"]!!.jsonPrimitive.content)
        client.stop()
    }

    @Test
    fun initializeOmitsAuthProviderForNative() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this)
        client.start()
        assertFalse(initParams(transport).containsKey("auth_provider"))
        client.stop()
    }

    @Test
    fun authRequiredOnLaterRequestFiresCallback() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val seen = mutableListOf<Pair<String, Boolean>>()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this, onAuthRequired = { m, atInit -> seen.add(m to atInit) })
        client.start()
        val initId = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.listener!!.onText(initReply(initId))
        var thrown: Throwable? = null
        launch { try { client.call<String, String>("x", null, kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<String>()) } catch (e: Throwable) { thrown = e } }
        val reqId = ZdJson.parseToJsonElement(transport.sent.last()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.error(reqId, -32010, "token expired")
        advanceTimeBy(1)
        assertEquals(listOf("token expired" to false), seen)
        assertTrue(thrown is RpcException)
        client.stop()
    }

    @Test
    fun otherRequestErrorsDoNotFireAuthCallback() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val seen = mutableListOf<String>()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this, onAuthRequired = { m, _ -> seen.add(m) })
        client.start()
        val initId = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.listener!!.onText(initReply(initId))
        launch { runCatching { client.call<String, String>("x", null, kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<String>()) } }
        val reqId = ZdJson.parseToJsonElement(transport.sent.last()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.error(reqId, -32012, "forbidden")
        advanceTimeBy(1)
        assertTrue(seen.isEmpty())
        client.stop()
    }

    @Test
    fun authRequiredAtInitializeIsFlagged() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val seen = mutableListOf<Pair<String, Boolean>>()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this, onAuthRequired = { m, atInit -> seen.add(m to atInit) })
        client.start()
        val id = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.error(id, -32010, "Credential expired")
        assertEquals(listOf("Credential expired" to true), seen)
        client.stop()
    }

    @Test
    fun reinitializeRefreshesTheTokenOnTheOpenSocket() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        var token = "old"
        val client = RpcClient(transport, "wss://x/", { token }, this, authProvider = { "oidc.corp" })
        client.start()
        val initId = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.listener!!.onText(initReply(initId))
        val ready = client.state.value
        token = "new"
        var info: InitializeResult? = null
        launch { info = client.reinitialize() }
        val frame = ZdJson.parseToJsonElement(transport.sent.last()).jsonObject
        assertEquals("initialize", frame["method"]!!.jsonPrimitive.content)
        assertEquals("new", frame["params"]!!.jsonObject["auth_token"]!!.jsonPrimitive.content)
        assertEquals("oidc.corp", frame["params"]!!.jsonObject["auth_provider"]!!.jsonPrimitive.content)
        transport.listener!!.onText(initReply(frame["id"]!!.jsonPrimitive.content.toLong()))
        advanceTimeBy(1)
        assertEquals("0.9.0", info!!.serverVersion)
        assertEquals(1, transport.opens)
        assertTrue("state must not flip, or chats would reload", client.state.value === ready)
        client.stop()
    }

    @Test
    fun refusedReinitializeIsReportedAsAnInitializeRefusal() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val seen = mutableListOf<Pair<String, Boolean>>()
        val client = RpcClient(transport, "wss://x/", { "tok" }, this, onAuthRequired = { m, atInit -> seen.add(m to atInit) })
        client.start()
        val initId = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.listener!!.onText(initReply(initId))
        var thrown: Throwable? = null
        launch { try { client.reinitialize() } catch (e: Throwable) { thrown = e } }
        val id = ZdJson.parseToJsonElement(transport.sent.last()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.error(id, -32010, "Credential rejected")
        advanceTimeBy(1)
        assertEquals(listOf("Credential rejected" to true), seen)
        assertTrue(thrown is RpcException)
        client.stop()
    }

    @Test
    fun rejectedIdentityIsDroppedAndTheHandshakeRetriedWithoutIt() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        var identity: ClientIdentity? = ClientIdentity("tui_old", "sig")
        var rejected = 0
        val refusals = mutableListOf<String>()
        val client = RpcClient(
            transport, "wss://x/", { "tok" }, this,
            identity = { identity },
            onIdentityRejected = { rejected++; identity = null },
            onAuthRequired = { message, _ -> refusals += message },
        )
        client.start()
        val first = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject
        assertEquals("tui_old", first["params"]!!.jsonObject["tui_id"]!!.jsonPrimitive.content)
        transport.error(first["id"]!!.jsonPrimitive.content.toLong(), -32010, "Invalid TUI signature")

        assertEquals(1, rejected)
        assertTrue(refusals.isEmpty())
        assertFalse(client.state.value is ConnState.AuthFailed)
        advanceTimeBy(60_000)
        val retry = ZdJson.parseToJsonElement(transport.sent.last()).jsonObject
        assertEquals("initialize", retry["method"]!!.jsonPrimitive.content)
        assertEquals(null, retry["params"]!!.jsonObject["tui_id"])
        transport.listener!!.onText(initReply(retry["id"]!!.jsonPrimitive.content.toLong()))
        assertTrue(client.state.first() is ConnState.Ready)
        client.stop()
    }

    @Test
    fun aRefusedTokenKeepsTheIdentityAndStops() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        var rejected = 0
        val client = RpcClient(
            transport, "wss://x/", { "tok" }, this,
            identity = { ClientIdentity("tui_old", "sig") },
            onIdentityRejected = { rejected++ },
        )
        client.start()
        val id = ZdJson.parseToJsonElement(transport.sent.first()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        transport.error(id, -32010, "auth required")
        assertTrue(client.state.first() is ConnState.AuthFailed)
        assertEquals(0, rejected)
        client.stop()
    }
}
