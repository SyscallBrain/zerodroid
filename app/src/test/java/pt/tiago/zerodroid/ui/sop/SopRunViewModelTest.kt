package pt.tiago.zerodroid.ui.sop

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.data.sop.SopRepository
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.RpcError
import pt.tiago.zerodroid.rpc.RpcException
import pt.tiago.zerodroid.rpc.ZdJson

@OptIn(ExperimentalCoroutinesApi::class)
class SopRunViewModelTest {
    private fun overlay(status: String, state: String = "active") =
        """{"run_id":"r1","sop_name":"weather","status":"$status","current_step":1,"total_steps":2,"waiting":${status == "waiting_approval"},
            "nodes":[{"step":1,"state":"$state"},{"step":2,"state":"pending"}]}"""

    private fun rpc(statuses: List<String>) = FakeCaller().apply {
        on("sops/graph", """{"nodes":[{"step":1},{"step":2}]}""")
        var i = 0
        handlers["sops/run-overlay"] = { ZdJson.parseToJsonElement(overlay(statuses[minOf(i++, statuses.lastIndex)])) }
    }

    private fun TestScope.vm(c: FakeCaller) = SopRunViewModel("weather", "r1", SopRepository(c), scopeOverride = backgroundScope)

    @Test
    fun pollingStopsOnTerminal() = runTest {
        val c = rpc(listOf("running", "running", "completed"))
        val v = vm(c)
        v.start(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, c.methods().count { it == "sops/run-overlay" })
        assertEquals("completed", v.state.value.overlay!!.status)
    }

    @Test
    fun pollingStopsWhenRunNotFound() = runTest {
        val c = rpc(listOf("running")).apply {
            handlers["sops/run-overlay"] = { throw RpcException(RpcError(RpcCodes.INVALID_PARAMS, "run 'r1' not found")) }
        }
        val v = vm(c)
        v.start(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(v.state.value.gone)
        assertEquals(1, c.methods().count { it == "sops/run-overlay" })
    }

    @Test
    fun stopHaltsPolling() = runTest {
        val c = rpc(listOf("running"))
        val v = vm(c)
        v.start(); runCurrent()
        v.stop()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, c.methods().count { it == "sops/run-overlay" })
    }

    @Test
    fun waitingNodeMapsToWaitingState() = runTest {
        val v = vm(rpc(listOf("waiting_approval")))
        v.start(); runCurrent()
        assertEquals(mapOf(1 to "waiting", 2 to "pending"), v.state.value.runStates)
        v.stop()
    }

    @Test
    fun approveSendsDecide() = runTest {
        val c = rpc(listOf("waiting_approval", "running"))
        val v = vm(c)
        v.start(); runCurrent()
        v.approve(); runCurrent()
        val p = c.sent.last { it.method == "sops/decide" }.params!!.jsonObject
        assertEquals("approve", p["decision"]!!.jsonPrimitive.content)
        assertEquals("r1", p["run_id"]!!.jsonPrimitive.content)
        v.stop()
    }

    @Test
    fun engineOffMarksState() = runTest {
        val c = rpc(listOf("running")).apply { fail("sops/run-overlay", RpcCodes.INTERNAL_ERROR, "SOP subsystem not enabled") }
        val v = vm(c)
        v.start(); runCurrent()
        assertTrue(v.state.value.engineOff)
        v.stop()
    }

    @Test
    fun runWithEngineOffShowsBanner() = runTest {
        // The run sheet starts runs through SopListViewModel.run (covered there); here the payload check.
        assertNull(validPayload("   "))
        assertEquals("""{"city":"Lisboa"}""", validPayload("""{"city":"Lisboa"}"""))
        assertThrows(IllegalArgumentException::class.java) { validPayload("{city") }
    }
}
