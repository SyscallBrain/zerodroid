package pt.tiago.zerodroid.ui.sop

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.data.sop.SopRepository
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.ZdJson

class SopListViewModelTest {
    private val sops = mapOf(
        "weather" to """{"name":"weather","description":"Rain check","triggers":[{"type":"manual"},{"type":"channel","channel":"telegram"}],
                         "steps":[{"number":1},{"number":2}]}""",
        "prs" to """{"name":"prs","description":"Daily PR review","triggers":[{"type":"cron","expression":"0 9 * * *"}],"steps":[{"number":1}]}""",
    )

    private fun rpc(runsJson: String? = null) = FakeCaller().apply {
        on("sops/list", """{"sops":[{"name":"weather","description":"Rain check"},{"name":"prs","description":"Daily PR review"}]}""")
        handlers["sops/get"] = { p -> ZdJson.parseToJsonElement(sops[p!!.jsonObject["name"]!!.jsonPrimitive.content]!!) }
        if (runsJson != null) on("sops/runs", runsJson) else fail("sops/runs", RpcCodes.INTERNAL_ERROR, "SOP subsystem not enabled")
    }

    private fun TestScope.vm(caller: FakeCaller) = SopListViewModel(SopRepository(caller), scopeOverride = this)

    @Test
    fun cardsJoinLatestRun() = runTest {
        val v = vm(
            rpc(
                """{"runs":[
                  {"run_id":"old","sop_name":"weather","status":"failed","started_at":"2026-11-21T10:00:00Z"},
                  {"run_id":"new","sop_name":"weather","status":"completed","started_at":"2026-11-22T10:00:00Z"}]}""",
            ),
        )
        v.refresh(); advanceUntilIdle()
        val s = v.state.value
        assertFalse(s.loading)
        val w = s.cards.first { it.name == "weather" }
        assertEquals("new", w.last!!.runId)
        assertEquals(2, w.steps)
        assertEquals(listOf("manual", "channel"), w.triggers)
        assertNull(s.cards.first { it.name == "prs" }.last)
        assertFalse(s.engineOff)
    }

    @Test
    fun engineOffStillListsSops() = runTest {
        val v = vm(rpc())
        v.refresh(); advanceUntilIdle()
        assertTrue(v.state.value.engineOff)
        assertEquals(2, v.state.value.cards.size)
    }

    @Test
    fun queryFiltersNameAndDescription() = runTest {
        val v = vm(rpc("""{"runs":[]}"""))
        v.refresh(); advanceUntilIdle()
        v.setQuery("RAIN")
        assertEquals(listOf("weather"), v.state.value.visible.map { it.name })
        v.setQuery("pr")
        assertEquals(listOf("prs"), v.state.value.visible.map { it.name })
    }

    @Test
    fun canRunNeedsManualTrigger() = runTest {
        val v = vm(rpc("""{"runs":[]}"""))
        v.refresh(); advanceUntilIdle()
        assertTrue(v.state.value.cards.first { it.name == "weather" }.canRun)
        assertFalse(v.state.value.cards.first { it.name == "prs" }.canRun)
    }

    @Test
    fun runWithEngineOffFlagsBanner() = runTest {
        val c = rpc("""{"runs":[]}""").apply { fail("sops/run", RpcCodes.INTERNAL_ERROR, "SOP subsystem not enabled") }
        val v = vm(c)
        v.refresh(); advanceUntilIdle()
        var started: String? = null
        v.run("weather", null) { started = it }; advanceUntilIdle()
        assertNull(started)
        assertTrue(v.state.value.engineOff)
    }

    @Test
    fun runStartsAndReportsId() = runTest {
        val c = rpc("""{"runs":[]}""").apply { on("sops/run", """{"run_id":"r9"}""") }
        val v = vm(c)
        var started: String? = null
        v.run("weather", null) { started = it }; advanceUntilIdle()
        assertEquals("r9", started)
    }
}
