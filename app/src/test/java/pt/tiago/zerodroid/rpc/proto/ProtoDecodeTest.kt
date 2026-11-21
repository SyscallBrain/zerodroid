package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.decodeInbound
import pt.tiago.zerodroid.rpc.encodeRequest

class ProtoDecodeTest {
    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name.json")!!
            .bufferedReader().use { it.readText() }

    private fun updateFrom(name: String): SessionUpdate {
        val frame = fixture(name)
        val inbound = decodeInbound(frame)
        assertTrue("fixture $name is a notification", inbound is pt.tiago.zerodroid.rpc.Inbound.Notification)
        val params = (inbound as pt.tiago.zerodroid.rpc.Inbound.Notification).params.jsonObject
        return ZdJson.decodeFromJsonElement(SessionUpdate.serializer(), kotlinx.serialization.json.JsonObject(params))
    }

    @Test
    fun decodesEverySessionUpdateVariant() {
        assertTrue(updateFrom("session_update_agent_message_chunk") is SessionUpdate.AgentMessageChunk)
        assertTrue(updateFrom("session_update_agent_thought_chunk") is SessionUpdate.AgentThoughtChunk)
        assertTrue(updateFrom("session_update_tool_call") is SessionUpdate.ToolCall)
        assertTrue(updateFrom("session_update_tool_result") is SessionUpdate.ToolResult)
        val approval = updateFrom("session_update_approval_request") as SessionUpdate.ApprovalRequest
        assertEquals("req-9", approval.requestId)
        assertEquals(120L, approval.timeoutSecs)
        assertTrue(updateFrom("session_update_context_usage") is SessionUpdate.ContextUsage)
        assertTrue(updateFrom("session_update_plan") is SessionUpdate.Plan)
        assertTrue(updateFrom("session_update_turn_complete") is SessionUpdate.TurnComplete)
        assertTrue(updateFrom("session_update_budget_warning") is SessionUpdate.BudgetWarning)
        assertTrue(updateFrom("session_update_history_trimmed") is SessionUpdate.HistoryTrimmed)
    }

    @Test
    fun unknownSessionUpdateTypeDecodesToUnknown() {
        val unknown = updateFrom("session_update_future") as SessionUpdate.Unknown
        assertEquals("brand_new_thing", unknown.type)
        assertEquals(42, unknown.raw["extra"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun extraFieldsAreIgnored() {
        val agents = ZdJson.decodeFromString(
            AgentsStatusResult.serializer(),
            fixture("agents_status_extra_fields")
        )
        assertEquals(1, agents.agents.size)
        assertEquals("coder", agents.agents[0].alias)
        assertEquals(2, agents.agents[0].liveSessions)
    }

    @Test
    fun sessionNewParamsOmitsNulls() {
        val encoded = ZdJson.encodeToString(SessionNewParams.serializer(), SessionNewParams("coder"))
        assertEquals("""{"agent_alias":"coder","keep_siblings":true}""", encoded)
    }
}
