package pt.tiago.zerodroid.data.doctor

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes

class DoctorStoreTest {
    private val body = """{"results":[{"severity":"warn","category":"config","message":"openai.ss: no model configured"},
        {"severity":"ok","category":"daemon","message":"heartbeat fresh"}],
        "summary":{"ok":1,"warnings":1,"errors":0},"timed_out_phase":"model_probe"}"""

    @Test
    fun runDecodesAndKeysByDaemon() = runTest {
        val rpc = FakeCaller().apply { on("doctor/run", body) }
        val store = DoctorStore(rpc, TestScope(), now = { 42 })
        store.run("d1")
        val s = store.state.value
        assertEquals(listOf("doctor/run"), rpc.methods())
        assertEquals(DoctorStore.RUN_TIMEOUT_MS, rpc.sent.single().timeoutMs)
        assertEquals(1, s.resultFor("d1")?.summary?.warnings)
        assertEquals("model_probe", s.resultFor("d1")?.timedOutPhase)
        assertEquals(42L, s.at)
        assertFalse(s.running)
        assertNull(s.resultFor("d2"))
    }

    @Test
    fun oldDaemonGetsAPlainMessageAndKeepsTheLastResult() = runTest {
        val rpc = FakeCaller().apply { on("doctor/run", body) }
        val store = DoctorStore(rpc, TestScope())
        store.run("d1")
        rpc.fail("doctor/run", RpcCodes.METHOD_NOT_FOUND)
        store.run("d1")
        val s = store.state.value
        assertEquals("This daemon has no Doctor over RPC. Update ZeroClaw.", s.error)
        assertEquals(1, s.resultFor("d1")?.summary?.ok)
        assertFalse(s.running)
    }

    @Test
    fun forbiddenSaysWhichGrantIsMissing() = runTest {
        val rpc = FakeCaller().apply { fail("doctor/run", RpcCodes.FORBIDDEN) }
        val store = DoctorStore(rpc, TestScope())
        store.run(null)
        assertEquals("This device may not run Doctor. Grant it system execute on the daemon.", store.state.value.error)
    }
}
