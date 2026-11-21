package pt.tiago.zerodroid.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes

class SopGatesTest {
    private val runs = """{"runs":[
      {"run_id":"a","sop_name":"deploy","status":"running","current_step":1,"total_steps":3,"started_at":"t","trigger_source":"manual","active":true},
      {"run_id":"b","sop_name":"deploy","status":"waiting_approval","current_step":2,"total_steps":3,"started_at":"t","trigger_source":"manual","active":true},
      {"run_id":"c","sop_name":"backup","status":"completed","current_step":3,"total_steps":3,"started_at":"t","trigger_source":"cron","active":false},
      {"run_id":"d","sop_name":"review","status":"paused_checkpoint","current_step":1,"total_steps":2,"started_at":"t","trigger_source":"manual","active":true},
      {"run_id":7,"status":"waiting_approval"},
      "garbage"
    ]}"""

    @Test
    fun parsesWaitingGatesOnly() = runTest {
        val rpc = FakeCaller().apply { on("sops/runs", runs) }
        val r = SopGates(rpc).pending() as SopGatesResult.Ok
        assertEquals(listOf("b", "d"), r.gates.map { it.runId })
        assertEquals("Step 2 of 3", r.gates[0].stepLabel)
        assertTrue(r.gates[1].checkpoint)
    }

    @Test
    fun malformedRunIsSkippedNotFatal() = runTest {
        val rpc = FakeCaller().apply { on("sops/runs", runs) }
        assertEquals(2, (SopGates(rpc).pending() as SopGatesResult.Ok).gates.size)
    }

    @Test
    fun forbiddenMapsToForbidden() = runTest {
        val rpc = FakeCaller().apply { fail("sops/runs", RpcCodes.FORBIDDEN) }
        assertEquals(SopGatesResult.Forbidden, SopGates(rpc).pending())
    }

    @Test
    fun denyEncodesDenyObject() = runTest {
        val rpc = FakeCaller()
        assertTrue(SopGates(rpc).decide(SopGate("deploy", "b", "Step 2", null, false), approve = false).isSuccess)
        assertEquals("""{"name":"deploy","run_id":"b","decision":{"deny":{}}}""", rpc.sent.single().json)
    }

    @Test
    fun approveEncodesApproveString() = runTest {
        val rpc = FakeCaller()
        SopGates(rpc).decide(SopGate("deploy", "b", "Step 2", null, false), approve = true)
        assertEquals("""{"name":"deploy","run_id":"b","decision":"approve"}""", rpc.sent.single().json)
    }
}
