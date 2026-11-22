package pt.tiago.zerodroid.data.sop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.ZdJson

class SopDraftTest {
    private fun obj(s: String) = ZdJson.parseToJsonElement(s).jsonObject
    private fun numbers(d: JsonObject) = SopDraft.steps(d).map { it["number"]!!.jsonPrimitive.int }

    @Test
    fun unknownFieldsSurviveEdits() {
        val d0 = obj(
            """{"name":"s","decision":{"model":"m","modes":["auto"]},"x_future":1,"triggers":[{"type":"manual"}],
               "steps":[{"number":1,"title":"a","body":"","pos":{"x":1,"y":2},"schema":{"input":{"type":"object"}},
                         "calls":[{"tool":"t","args":{},"pinned":{"a":1}}]}]}""",
        )
        var d = SopDraft.setStep(d0, 1, listOf("title"), JsonPrimitive("b"))
        val (d2, n) = SopDraft.addStep(d); d = d2
        d = SopDraft.setFailure(d, 1, SopDraft.Failure.Retry(2))
        d = SopDraft.moveStep(d, n, -1)
        d = SopDraft.deleteStep(d, n)
        assertEquals(d0["decision"], d["decision"])
        assertEquals(d0["x_future"], d["x_future"])
        val s0 = SopDraft.step(d0, 1)!!
        val s = SopDraft.step(d, 1)!!
        for (k in listOf("pos", "schema", "calls")) assertEquals(k, s0[k], s[k])
        assertEquals("b", s["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun deleteStepClearsEveryReference() {
        val d = obj(
            """{"name":"s","steps":[
              {"number":1,"routing":{"next":3}},
              {"number":2,"routing":{"depends_on":[1,3],"switch":[{"name":"a","goto":3},{"name":"b","goto":4}]}},
              {"number":3},
              {"number":4,"on_failure":{"goto":{"step":3}}}]}""",
        )
        val out = SopDraft.deleteStep(d, 3)
        assertEquals(listOf(1, 2, 4), numbers(out))
        assertNull(SopDraft.step(out, 1)!!["routing"])
        val r2 = SopDraft.step(out, 2)!!["routing"]!!.jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive(1))), r2["depends_on"])
        val rules = r2["switch"]!!.jsonArray.map { it.jsonObject }
        assertNull(rules[0]["goto"])
        assertEquals(4, rules[1]["goto"]!!.jsonPrimitive.int)
        assertNull(SopDraft.step(out, 4)!!["on_failure"])
    }

    @Test
    fun deleteStepClearsUnlessDecided() {
        val d = obj("""{"name":"s","steps":[{"number":1,"decide":"route"},{"number":2,"unless_decided":1},{"number":3,"unless_decided":2}]}""")
        val out = SopDraft.deleteStep(d, 1)
        assertNull(SopDraft.step(out, 2)!!["unless_decided"])
        assertEquals(2, SopDraft.step(out, 3)!!["unless_decided"]!!.jsonPrimitive.int)
    }

    @Test
    fun deleteStepDropsEmptiedDependsOn() {
        val d = obj("""{"name":"s","steps":[{"number":1},{"number":2,"routing":{"depends_on":[1]}}]}""")
        assertNull(SopDraft.step(SopDraft.deleteStep(d, 1), 2)!!["routing"])
    }

    @Test
    fun addStepUsesMaxPlusOne() {
        val d = obj("""{"name":"s","steps":[{"number":1},{"number":2},{"number":5}]}""")
        val (out, n) = SopDraft.addStep(d)
        assertEquals(6, n)
        assertEquals(SopDraft.blankStep(6), SopDraft.step(out, 6))
        assertEquals(1, SopDraft.addStep(obj("""{"name":"s"}""")).second)
    }

    @Test
    fun moveStepSwapsPositions() {
        val d = obj("""{"name":"s","steps":[{"number":1},{"number":2},{"number":3}]}""")
        assertEquals(listOf(1, 3, 2), numbers(SopDraft.moveStep(d, 3, -1)))
        assertEquals(listOf(1, 2, 3), numbers(SopDraft.moveStep(d, 1, -1)))
        assertEquals(listOf(1, 2, 3), numbers(SopDraft.moveStep(d, 3, 1)))
    }

    @Test
    fun duplicateStepCopiesWithoutRouting() {
        val d = obj("""{"name":"s","steps":[{"number":1,"title":"a","body":"x","routing":{"next":1},"on_failure":"fail"}]}""")
        val (out, n) = SopDraft.duplicateStep(d, 1)
        assertEquals(2, n)
        val c = SopDraft.step(out, 2)!!
        assertEquals("a (copy)", c["title"]!!.jsonPrimitive.content)
        assertEquals("x", c["body"]!!.jsonPrimitive.content)
        assertNull(c["routing"]); assertNull(c["on_failure"])
    }

    @Test
    fun failureRoundTrip() {
        fun f(json: String) = SopDraft.failure(obj("""{"number":1,"on_failure":$json}"""))
        assertEquals(SopDraft.Failure.Fail, f("\"fail\""))
        assertEquals(SopDraft.Failure.Retry(2), f("""{"retry":{"max":2}}"""))
        assertEquals(SopDraft.Failure.Goto(4), f("""{"goto":{"step":4}}"""))
        assertEquals(SopDraft.Failure.Fail, SopDraft.failure(obj("""{"number":1}""")))
        val d = obj("""{"name":"s","steps":[{"number":1,"on_failure":{"retry":{"max":2}}}]}""")
        assertNull(SopDraft.step(SopDraft.setFailure(d, 1, SopDraft.Failure.Fail), 1)!!["on_failure"])
        assertEquals(obj("""{"goto":{"step":4}}"""), SopDraft.step(SopDraft.setFailure(d, 1, SopDraft.Failure.Goto(4)), 1)!!["on_failure"])
    }

    @Test
    fun setStepNestedAndRemovesEmptyRouting() {
        val d = obj("""{"name":"s","steps":[{"number":1}]}""")
        val a = SopDraft.setStep(d, 1, listOf("routing", "when"), JsonPrimitive("$.x > 1"))
        assertEquals("$.x > 1", SopDraft.step(a, 1)!!["routing"]!!.jsonObject["when"]!!.jsonPrimitive.content)
        assertNull(SopDraft.step(SopDraft.setStep(a, 1, listOf("routing", "when"), null), 1)!!["routing"])
    }

    @Test
    fun blankMatchesWeb() {
        assertEquals(
            obj(
                """{"name":"x","description":"","version":"1.0.0","priority":"normal","execution_mode":"supervised",
                   "triggers":[{"type":"manual"}],
                   "steps":[{"number":1,"title":"","body":"","kind":"execute","requires_confirmation":false,"suggested_tools":[]}],
                   "cooldown_secs":0,"max_concurrent":1,"admission_policy":"parallel","max_pending_approvals":0,"deterministic":false}""",
            ),
            SopDraft.blank("x"),
        )
    }

    @Test
    fun toolNamesAndManualTrigger() {
        val d = obj(
            """{"name":"s","triggers":[{"type":"cron","expression":"0 9 * * *"}],
               "steps":[{"number":1,"suggested_tools":["shell","weather"]},{"number":2,"calls":[{"tool":"git_forge","args":{}}]}]}""",
        )
        assertEquals(setOf("shell", "weather", "git_forge"), SopDraft.toolNames(d))
        assertFalse(SopDraft.hasManualTrigger(d))
        assertTrue(SopDraft.hasManualTrigger(SopDraft.blank("x")))
    }
}
