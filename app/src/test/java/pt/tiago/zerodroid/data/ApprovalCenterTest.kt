package pt.tiago.zerodroid.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCaller
import pt.tiago.zerodroid.rpc.proto.ApprovalDecision
import pt.tiago.zerodroid.rpc.proto.SessionUpdate

class ApprovalCenterTest {
    private var now = 1_000L
    private val rpc = FakeCaller()
    private val center = ApprovalCenter(rpc) { now }

    private fun request(id: String = "r1", timeout: Long = 30) =
        center.onEvent("s", "main", SessionUpdate.ApprovalRequest("s", id, "shell", "rm -rf /tmp/x", timeout))

    @Test
    fun approveSendsAllowOnce() = runTest {
        request()
        assertTrue(center.decide("r1", ApprovalDecision.APPROVE).isSuccess)
        assertEquals("session/approve", rpc.sent.single().method)
        assertEquals("""{"session_id":"s","request_id":"r1","decision":"allow_once"}""", rpc.sent.single().json)
    }

    @Test
    fun alwaysSendsAllowAlways() = runTest {
        request()
        center.decide("r1", ApprovalDecision.ALWAYS)
        assertTrue(rpc.sent.single().json.contains("\"decision\":\"allow_always\""))
    }

    @Test
    fun denySendsRejectOnce() = runTest {
        request()
        center.decide("r1", ApprovalDecision.DENY)
        assertTrue(rpc.sent.single().json.contains("\"decision\":\"reject_once\""))
    }

    @Test
    fun expiredApprovalSendsNothing() = runTest {
        request(timeout = 30)
        now += 30_000
        val r = center.decide("r1", ApprovalDecision.APPROVE)
        assertTrue(r.exceptionOrNull() is ApprovalExpiredException)
        assertTrue(rpc.sent.isEmpty())
        assertEquals(ApprovalStatus.Expired, center.recent.value.single().status)
        assertEquals(0, center.pendingCount.value)
    }

    @Test
    fun expireDueResolvesWithoutSending() {
        request(timeout = 5)
        now += 6_000
        center.expireDue()
        assertTrue(center.pending.value.isEmpty())
        assertEquals(ApprovalStatus.Expired, center.recent.value.single().status)
        assertTrue(rpc.sent.isEmpty())
    }

    @Test
    fun pendingApprovalBecomesConnectionLostOnDisconnect() {
        request()
        center.onConnectionLost()
        assertEquals(0, center.pendingCount.value)
        assertEquals(ApprovalStatus.ConnectionLost, center.recent.value.single().status)
    }

    @Test
    fun answeredMovesToRecent() = runTest {
        request()
        center.decide("r1", ApprovalDecision.APPROVE)
        assertTrue(center.pending.value.isEmpty())
        assertEquals(ApprovalStatus.Approved, center.recent.value.single().status)
        assertEquals(now, center.recent.value.single().answeredAt)
    }

    @Test
    fun doubleTapSendsOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val slow = RpcCaller { _, _, _ -> calls++; gate.await(); JsonObject(emptyMap()) }
        val c = ApprovalCenter(slow) { now }
        c.onEvent("s", "main", SessionUpdate.ApprovalRequest("s", "r1", "shell", "x", 30))
        val a = async { c.decide("r1", ApprovalDecision.APPROVE) }
        val b = async { c.decide("r1", ApprovalDecision.APPROVE) }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        val results = listOf(a, b).awaitAll()
        assertEquals(1, calls)
        assertEquals(1, results.count { it.isSuccess })
    }

    @Test
    fun failedSendKeepsApprovalPending() = runTest {
        rpc.fail("session/approve", -32603)
        request()
        assertTrue(center.decide("r1", ApprovalDecision.APPROVE).isFailure)
        assertEquals(1, center.pendingCount.value)
    }
}
