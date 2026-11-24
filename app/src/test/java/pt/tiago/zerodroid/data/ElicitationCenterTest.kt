package pt.tiago.zerodroid.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.Inbound
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.ZdJson

class ElicitationCenterTest {
    private class FakeTransport : ElicitationTransport {
        val results = mutableListOf<Pair<String, JsonElement>>()
        val errors = mutableListOf<Triple<String, Int, String>>()
        override fun respond(id: JsonElement, result: JsonElement): Boolean {
            results += (id as JsonPrimitive).content to result
            return true
        }
        override fun respondError(id: JsonElement, code: Int, message: String): Boolean {
            errors += Triple((id as JsonPrimitive).content, code, message)
            return true
        }
    }

    private var now = 1_000L
    private val transport = FakeTransport()
    private val center = ElicitationCenter(transport) { now }

    private val singleSchema = """{"type":"object","properties":{"choice":{"type":"string","oneOf":[{"const":"choice-0","title":"Yes"},{"const":"choice-1","title":"No"}]}},"required":["choice"]}"""
    private val multiSchema = """{"type":"object","properties":{"choices":{"type":"array","items":{"anyOf":[{"const":"choice-0","title":"Red"},{"const":"choice-1","title":"Green"}]}}},"required":["choices"]}"""

    private fun req(id: String, schema: String, message: String = "Pick one", mode: String = "form") =
        Inbound.Request(
            JsonPrimitive(id), "elicitation/create",
            ZdJson.parseToJsonElement("""{"sessionId":"s","mode":"$mode","message":"$message","requestedSchema":$schema}"""),
        )

    @Test
    fun registersQuestionWithParsedOptions() {
        val q = center.onRequest(req("r1", singleSchema, "Continue?"))
        assertEquals("r1", q!!.requestId)
        assertEquals("s", q.sessionId)
        assertEquals("Continue?", q.message)
        assertEquals(listOf("choice-0" to "Yes", "choice-1" to "No"), q.options.map { it.const to it.title })
        assertEquals(false, q.multi)
        assertEquals(QuestionStatus.Pending, q.status)
        assertEquals(1, center.pendingCount.value)
    }

    @Test
    fun parsesMultiSelectSchema() {
        val q = center.onRequest(req("r1", multiSchema))
        assertEquals(true, q!!.multi)
        assertEquals("choices", q.property)
        assertEquals(listOf("Red", "Green"), q.options.map { it.title })
    }

    @Test
    fun answerSendsAcceptWithChoiceConst() = runTest {
        center.onRequest(req("r1", singleSchema))
        val r = center.answer("r1", listOf("choice-1"))
        assertTrue(r.isSuccess)
        val (id, result) = transport.results.single()
        assertEquals("r1", id)
        assertEquals("accept", result.jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("choice-1", result.jsonObject["content"]!!.jsonObject["choice"]!!.jsonPrimitive.content)
        assertEquals(QuestionStatus.Answered, center.recent.value.single().status)
        assertEquals(listOf("No"), center.recent.value.single().answer)
        assertEquals(0, center.pendingCount.value)
    }

    @Test
    fun multiAnswerSendsArrayOfConsts() = runTest {
        center.onRequest(req("r1", multiSchema))
        center.answer("r1", listOf("choice-1", "choice-0"))
        val content = transport.results.single().second.jsonObject["content"]!!.jsonObject
        assertEquals(listOf("choice-1", "choice-0"), content["choices"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun answerRejectsUnknownConst() = runTest {
        center.onRequest(req("r1", singleSchema))
        val r = center.answer("r1", listOf("choice-99"))
        assertTrue(r.exceptionOrNull() is IllegalArgumentException)
        assertTrue(transport.results.isEmpty())
        assertEquals(1, center.pendingCount.value)
    }

    @Test
    fun singleSelectRejectsTwoChoices() = runTest {
        center.onRequest(req("r1", singleSchema))
        assertTrue(center.answer("r1", listOf("choice-0", "choice-1")).exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun declineAndCancelSendMatchingActions() = runTest {
        center.onRequest(req("r1", singleSchema))
        center.onRequest(req("r2", singleSchema))
        center.decline("r1")
        center.cancel("r2")
        assertEquals("decline", transport.results[0].second.jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("cancel", transport.results[1].second.jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals(QuestionStatus.Declined, center.recent.value.first { it.requestId == "r1" }.status)
        assertEquals(QuestionStatus.Cancelled, center.recent.value.first { it.requestId == "r2" }.status)
    }

    @Test
    fun doubleAnswerFailsAndSendsOnce() = runTest {
        center.onRequest(req("r1", singleSchema))
        assertTrue(center.answer("r1", listOf("choice-0")).isSuccess)
        assertTrue(center.answer("r1", listOf("choice-0")).exceptionOrNull() is ElicitationAnsweredException)
        assertEquals(1, transport.results.size)
    }

    @Test
    fun malformedParamsRespondErrorAndRegisterNothing() {
        val q = center.onRequest(Inbound.Request(JsonPrimitive("bad"), "elicitation/create", JsonPrimitive("not-an-object")))
        assertEquals(null, q)
        assertEquals(1, transport.errors.size)
        assertEquals(RpcCodes.INVALID_PARAMS, transport.errors.single().second)
        assertEquals(0, center.pendingCount.value)
    }

    @Test
    fun nonFormModeIsRefused() {
        val q = center.onRequest(req("r1", singleSchema, mode = "url"))
        assertEquals(null, q)
        assertEquals(RpcCodes.INVALID_REQUEST, transport.errors.single().second)
    }

    @Test
    fun expireDueSendsCancelAndResolvesExpired() = runTest {
        center.onRequest(req("r1", singleSchema))
        now += ElicitationCenter.DEFAULT_TIMEOUT_SECS * 1_000L
        center.expireDue()
        assertEquals(0, center.pendingCount.value)
        assertEquals(QuestionStatus.Expired, center.recent.value.single().status)
        assertEquals("cancel", transport.results.single().second.jsonObject["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun connectionLostResolvesWithoutSending() {
        center.onRequest(req("r1", singleSchema))
        center.onConnectionLost()
        assertEquals(0, center.pendingCount.value)
        assertEquals(QuestionStatus.ConnectionLost, center.recent.value.single().status)
        assertTrue(transport.results.isEmpty())
        assertTrue(transport.errors.isEmpty())
    }
}
