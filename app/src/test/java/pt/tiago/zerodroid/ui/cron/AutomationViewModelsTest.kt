package pt.tiago.zerodroid.ui.cron

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
import pt.tiago.zerodroid.data.config.ConfigRepository
import pt.tiago.zerodroid.data.cron.CronRepository
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.ZdJson

class AutomationViewModelsTest {
    private fun job(id: String, extra: String = "") =
        """{"id":"$id","expression":"0 9 * * *","schedule":{"kind":"cron","expr":"0 9 * * *"},"agent_alias":"a"$extra}"""

    private fun repo(rpc: FakeCaller) = CronRepository(rpc, ConfigRepository(rpc))

    private fun TestScope.list(rpc: FakeCaller) = AutomationListViewModel(repo(rpc), scopeOverride = this)
    private fun TestScope.detail(rpc: FakeCaller, id: String = "j1") = AutomationDetailViewModel(id, repo(rpc), scopeOverride = this, launchScope = this)
    private fun TestScope.editor(rpc: FakeCaller, id: String? = null) = AutomationEditorViewModel(id, repo(rpc), scopeOverride = this)

    private fun FakeCaller.agents() = on("agents/list", """{"agents":[{"alias":"off","enabled":false,"channels":[]},{"alias":"a","enabled":true,"channels":["telegram.main"]}]}""")

    @Test
    fun listSortsEnabledFirstThenNextRun() = runTest {
        val rpc = FakeCaller().apply {
            on(
                "cron/list",
                """{"jobs":[${job("late", ""","next_run":"2026-11-23T10:00:00Z"""")},${job("paused", ""","enabled":false,"next_run":"2026-11-22T00:00:00Z"""")},${job("soon", ""","next_run":"2026-11-23T08:00:00Z"""")}]}""",
            )
            on("cron/settings", """{"enabled":true}""")
        }
        val v = list(rpc); v.refresh(); advanceUntilIdle()
        assertEquals(listOf("soon", "late", "paused"), v.state.value.jobs.map { it.id })
        assertFalse(v.state.value.loading)
    }

    @Test
    fun detailDeclarativeIsReadOnly() = runTest {
        val rpc = FakeCaller().apply { on("cron/get", job("j1", ""","source":"declarative"""")); on("cron/runs", """{"runs":[]}""") }
        val v = detail(rpc); v.refresh(); advanceUntilIdle()
        assertEquals("Defined in config.toml — edit it there.", v.state.value.readOnlyReason)
    }

    @Test
    fun detailForbiddenAgentTriggerSetsReason() = runTest {
        val rpc = FakeCaller().apply {
            on("cron/get", job("j1", ""","job_type":"agent""""))
            on("cron/runs", """{"runs":[]}""")
            fail("cron/trigger", RpcCodes.FORBIDDEN)
        }
        val v = detail(rpc); v.refresh(); advanceUntilIdle()
        v.runNow(); advanceUntilIdle()
        assertEquals("Agent jobs need admin access for this device.", v.state.value.readOnlyReason)
        assertFalse(v.state.value.running)
    }

    @Test
    fun runNowKeepsResultAndRefreshesHistory() = runTest {
        val rpc = FakeCaller().apply {
            on("cron/get", job("j1"))
            on("cron/runs", """{"runs":[]}""")
            on("cron/trigger", """{"id":"j1","success":true,"status":"ok","output":"hi","duration_ms":5}""")
        }
        val v = detail(rpc); v.refresh(); advanceUntilIdle()
        v.runNow(); advanceUntilIdle()
        assertEquals("hi", v.state.value.lastTrigger!!.output)
        assertEquals(2, rpc.methods().count { it == "cron/runs" })
    }

    @Test
    fun pauseOnOldDaemonShowsUpdateNeeded() = runTest {
        val rpc = FakeCaller().apply {
            on("cron/get", job("j1"))
            on("cron/runs", """{"runs":[]}""")
            on("cron/patch", job("j1", ""","enabled":true"""))
        }
        val v = detail(rpc); v.refresh(); advanceUntilIdle()
        v.setEnabled(false); advanceUntilIdle()
        assertEquals("The daemon needs the automation update to change this.", v.state.value.error)
        assertTrue(v.state.value.job!!.enabled)
    }

    @Test
    fun loadMoreStopsAt100() = runTest {
        val rpc = FakeCaller()
        rpc.on("cron/get", job("j1"))
        rpc.handlers["cron/runs"] = { p ->
            val n = p!!.jsonObject["limit"]!!.jsonPrimitive.content.toInt()
            ZdJson.parseToJsonElement("""{"runs":[${(1..n).joinToString(",") { """{"id":$it,"job_id":"j1","started_at":"s","finished_at":"f","status":"ok"}""" }}]}""")
        }
        val v = detail(rpc); v.refresh(); advanceUntilIdle()
        assertTrue(v.state.value.canLoadMore)
        v.loadMore(); advanceUntilIdle()
        assertEquals(50, v.state.value.runs.size)
        v.loadMore(); advanceUntilIdle()
        assertEquals(100, v.state.value.runs.size)
        assertFalse(v.state.value.canLoadMore)
    }

    @Test
    fun deleteMarksDeleted() = runTest {
        val rpc = FakeCaller().apply { on("cron/get", job("j1")); on("cron/runs", """{"runs":[]}""") }
        val v = detail(rpc); v.refresh(); advanceUntilIdle()
        v.delete(); advanceUntilIdle()
        assertTrue(v.state.value.deleted)
    }

    @Test
    fun newJobDefaultsToFirstEnabledAgent() = runTest {
        val rpc = FakeCaller().apply { agents() }
        val v = editor(rpc); v.load(); advanceUntilIdle()
        assertEquals("a", v.state.value.draft.agent)
        assertFalse(v.state.value.dirty)
    }

    @Test
    fun problemsBlockSave() = runTest {
        val rpc = FakeCaller().apply { agents() }
        val v = editor(rpc); v.load(); advanceUntilIdle()
        v.save(); advanceUntilIdle()
        assertEquals(listOf("Enter a schedule", "Enter a command"), v.state.value.problems)
        assertFalse("cron/add" in rpc.methods())
    }

    @Test
    fun agentAddOnOldDaemonDeletesShellJob() = runTest {
        val rpc = FakeCaller().apply { agents(); on("cron/add", job("new1", ""","job_type":"shell"""")) }
        val v = editor(rpc); v.load(); advanceUntilIdle()
        v.update { it.copy(agentJob = true, expr = "0 9 * * *", prompt = "hi") }
        v.save(); advanceUntilIdle()
        val del = rpc.sent.single { it.method == "cron/delete" }
        assertEquals("new1", del.params!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("The daemon needs the automation update to create agent jobs.", v.state.value.error)
        assertNull(v.state.value.savedId)
    }

    @Test
    fun addSavesAndReportsId() = runTest {
        val rpc = FakeCaller().apply { agents(); on("cron/add", job("new1", ""","job_type":"shell"""")) }
        val v = editor(rpc); v.load(); advanceUntilIdle()
        v.update { it.copy(expr = "0 9 * * *", command = "echo hi") }
        v.save(); advanceUntilIdle()
        assertEquals("new1", v.state.value.savedId)
    }

    @Test
    fun lostAddFindsExistingJob() = runTest {
        val rpc = FakeCaller().apply {
            agents()
            handlers["cron/add"] = { throw java.util.concurrent.TimeoutException("slow") }
            on("cron/list", """{"jobs":[${job("other", ""","command":"echo no"""")},${job("found", ""","name":"n","command":"echo hi"""")}]}""")
        }
        val v = editor(rpc); v.load(); advanceUntilIdle()
        v.update { it.copy(name = "n", expr = "0 9 * * *", command = "echo hi") }
        v.save(); advanceUntilIdle()
        assertEquals("found", v.state.value.savedId)
        assertEquals(1, rpc.methods().count { it == "cron/add" })
    }

    @Test
    fun lostAddWithoutMatchOffersRetry() = runTest {
        val rpc = FakeCaller().apply {
            agents()
            handlers["cron/add"] = { throw java.util.concurrent.TimeoutException("slow") }
            on("cron/list", """{"jobs":[]}""")
        }
        val v = editor(rpc); v.load(); advanceUntilIdle()
        v.update { it.copy(expr = "0 9 * * *", command = "echo hi") }
        v.save(); advanceUntilIdle()
        assertEquals("Couldn't confirm the job was saved. Check the list, then try again.", v.state.value.error)
        assertNull(v.state.value.savedId)
        assertFalse(v.state.value.saving)
    }

    @Test
    fun unchangedEditSendsNothing() = runTest {
        val rpc = FakeCaller().apply { agents(); on("cron/get", job("j1", ""","command":"echo"""")) }
        val v = editor(rpc, "j1"); v.load(); advanceUntilIdle()
        v.save(); advanceUntilIdle()
        assertEquals("j1", v.state.value.savedId)
        assertFalse("cron/patch" in rpc.methods())
    }

    @Test
    fun editOnOldDaemonShowsUpdateNeeded() = runTest {
        val rpc = FakeCaller().apply {
            agents()
            on("cron/get", job("j1", ""","job_type":"agent","prompt":"p""""))
            on("cron/patch", job("j1", ""","job_type":"agent","prompt":"p""""))
        }
        val v = editor(rpc, "j1"); v.load(); advanceUntilIdle()
        v.update { it.copy(model = "m") }
        v.save(); advanceUntilIdle()
        assertEquals("The daemon needs the automation update to change this.", v.state.value.error)
        assertNull(v.state.value.savedId)
    }

    @Test
    fun dirtyAfterEdit() = runTest {
        val rpc = FakeCaller().apply { agents(); on("cron/get", job("j1", ""","command":"echo"""")) }
        val v = editor(rpc, "j1"); v.load(); advanceUntilIdle()
        assertFalse(v.state.value.dirty)
        v.update { it.copy(name = "x") }
        assertTrue(v.state.value.dirty)
    }
}
