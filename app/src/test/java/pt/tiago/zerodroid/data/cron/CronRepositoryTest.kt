package pt.tiago.zerodroid.data.cron

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.data.config.ConfigRepository
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.proto.CronAddParams
import pt.tiago.zerodroid.rpc.proto.CronPatchParams
import pt.tiago.zerodroid.rpc.proto.SchedulerSettings

class CronRepositoryTest {
    private fun repo(rpc: FakeCaller) = CronRepository(rpc, ConfigRepository(rpc))

    private val fullJob = """{"id":"j1","expression":"0 9 * * *",
        "schedule":{"kind":"cron","expr":"0 9 * * *","tz":"Europe/Lisbon"},
        "command":"","prompt":"write","name":"Keryx","job_type":"agent","session_target":"main","model":"m",
        "agent_alias":"keryx","enabled":false,
        "delivery":{"mode":"announce","channel":"telegram.main","to":"42","best_effort":false},
        "allowed_tools":["shell"],"uses_memory":false,"source":"imperative",
        "next_run":"2026-11-23T09:00:00Z","last_run":"2026-11-22T09:00:00Z","last_status":"error","last_output":"boom"}"""

    @Test
    fun listDecodesFullJob() = runTest {
        val rpc = FakeCaller().apply { on("cron/list", """{"jobs":[$fullJob]}""") }
        val jobs = (repo(rpc).list() as CronResult.Ok).value
        val j = jobs.single()
        assertEquals("Europe/Lisbon", j.schedule!!.tz)
        assertEquals("0 9 * * *", j.schedule!!.expr)
        assertTrue(j.isAgent)
        assertFalse(j.isDeclarative)
        assertEquals("main", j.sessionTarget)
        assertEquals("telegram.main", j.delivery.channel)
        assertFalse(j.delivery.bestEffort)
        assertEquals(listOf("shell"), j.allowedTools)
        assertFalse(j.usesMemory)
        assertFalse(j.enabled)
        assertEquals("boom", j.lastOutput)
    }

    @Test
    fun addSendsOnlySetFields() = runTest {
        val rpc = FakeCaller().apply { on("cron/add", fullJob) }
        repo(rpc).add(CronAddParams(agent = "a", schedule = "0 * * * *", command = "echo hi"))
        val p = rpc.sent.single().params!!.jsonObject
        assertEquals(setOf("agent", "schedule", "command"), p.keys)
    }

    @Test
    fun patchEncodesFalseEnabled() = runTest {
        val rpc = FakeCaller().apply { on("cron/patch", fullJob) }
        repo(rpc).patch(CronPatchParams(id = "j1", agent = "keryx", enabled = false))
        val p = rpc.sent.single().params!!.jsonObject
        assertEquals("false", p["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun deliveryAlwaysSendsModeAndBestEffort() = runTest {
        val rpc = FakeCaller().apply { on("cron/patch", fullJob) }
        repo(rpc).patch(CronPatchParams(id = "j1", agent = "keryx", delivery = pt.tiago.zerodroid.rpc.proto.CronDelivery()))
        val d = rpc.sent.single().params!!.jsonObject["delivery"]!!.jsonObject
        assertEquals("none", d["mode"]!!.jsonPrimitive.content)
        assertEquals("true", d["best_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun triggerUsesLongTimeout() = runTest {
        val rpc = FakeCaller().apply { on("cron/trigger", """{"id":"j1","success":true,"status":"ok","output":"hi"}""") }
        val r = (repo(rpc).trigger("j1") as CronResult.Ok).value
        assertEquals("hi", r.output)
        assertEquals(600_000L, rpc.sent.single().timeoutMs)
    }

    @Test
    fun forbiddenMapsToForbidden() = runTest {
        val rpc = FakeCaller().apply { fail("cron/trigger", RpcCodes.FORBIDDEN) }
        assertEquals(CronResult.Forbidden, repo(rpc).trigger("j1"))
    }

    @Test
    fun timeoutMapsToLost() = runTest {
        val rpc = FakeCaller().apply { handlers["cron/add"] = { throw java.util.concurrent.TimeoutException("slow") } }
        assertTrue(repo(rpc).add(CronAddParams(agent = "a", schedule = "* * * * *", command = "x")) is CronResult.Lost)
    }

    @Test
    fun otherRpcErrorMapsToError() = runTest {
        val rpc = FakeCaller().apply { fail("cron/delete", RpcCodes.INVALID_PARAMS, "Cron job not found: j1") }
        assertEquals(CronResult.Error("Cron job not found: j1"), repo(rpc).delete("j1"))
    }

    @Test
    fun runsSendsLimit() = runTest {
        val rpc = FakeCaller().apply {
            on("cron/runs", """{"runs":[{"id":3,"job_id":"j1","started_at":"a","finished_at":"b","status":"ok","output":"o","duration_ms":12}]}""")
        }
        val runs = (repo(rpc).runs("j1", 50) as CronResult.Ok).value
        assertEquals(12L, runs.single().durationMs)
        assertEquals("50", rpc.sent.single().params!!.jsonObject["limit"]!!.jsonPrimitive.content)
    }

    @Test
    fun settingsReadsScheduler() = runTest {
        val rpc = FakeCaller().apply { on("cron/settings", """{"enabled":true,"catch_up_on_startup":false,"max_run_history":30,"max_tasks":64}""") }
        assertEquals(SchedulerSettings(true, false, 30), (repo(rpc).settings() as CronResult.Ok).value)
    }

    @Test
    fun setSettingsUsesDaemonPaths() = runTest {
        val rpc = FakeCaller().apply {
            on(
                "config/list",
                """{"entries":[{"path":"scheduler.enabled"},{"path":"scheduler.catch-up-on-startup"},{"path":"scheduler.max-run-history"},{"path":"scheduler.max-tasks"}]}""",
            )
            on("config/set-many", """{"props":[]}""")
        }
        assertTrue(repo(rpc).setSettings(SchedulerSettings(false, true, 20)) is CronResult.Ok)
        val sets = rpc.sent.last { it.method == "config/set-many" }.params!!.jsonObject.values.first().jsonArray
        val byProp = sets.associate { it.jsonObject["prop"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content }
        assertEquals(mapOf("scheduler.enabled" to "false", "scheduler.catch-up-on-startup" to "true", "scheduler.max-run-history" to "20"), byProp)
    }

    @Test
    fun agentsListsEntries() = runTest {
        val rpc = FakeCaller().apply { on("agents/list", """{"agents":[{"alias":"b","enabled":true,"channels":["telegram.main"]},{"alias":"a","enabled":false,"channels":[]}]}""") }
        val agents = (repo(rpc).agents() as CronResult.Ok).value
        assertEquals(listOf("a", "b"), agents.map { it.alias })
    }
}
