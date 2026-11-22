package pt.tiago.zerodroid.data.sop

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pt.tiago.zerodroid.data.config.ConfigRepository
import pt.tiago.zerodroid.rpc.FakeCaller

class SopEngineControlTest {
    private fun rpc(dir: String?, running: Int = 0) = FakeCaller().apply {
        val entry = if (dir == null) """{"path":"sop.sops_dir","populated":false}""" else """{"path":"sop.sops_dir","value":"$dir","populated":true}"""
        on("config/list", """{"entries":[$entry,{"path":"sop.max_concurrent_total","value":4,"populated":true}]}""")
        on("session/list", """{"sessions":[${List(running) { """{"session_id":"s$it","session_key":"k$it","created_at":"t","last_activity":"t","message_count":1}""" }.joinToString(",")}]}""")
    }

    private fun control(c: FakeCaller) = SopEngineControl(ConfigRepository(c))

    @Test
    fun dirUnsetMeansConfigure() = runTest {
        assertEquals(EngineFix.Configure, control(rpc(null)).fix())
        assertEquals(EngineFix.Configure, control(rpc("")).fix())
    }

    @Test
    fun dirSetMeansRestartWithRunningCount() = runTest {
        assertEquals(EngineFix.Restart(running = 0), control(rpc("shared/sops")).fix())
        assertEquals(EngineFix.Restart(running = 2), control(rpc("shared/sops", running = 2)).fix())
    }

    @Test
    fun readsOnlyTheSopSection() = runTest {
        val c = rpc("shared/sops")
        control(c).fix()
        assertEquals("""{"prefix":"sop"}""", c.sent.first { it.method == "config/list" }.json)
    }

    @Test
    fun restartAsksTheDaemonToReload() = runTest {
        val c = rpc("shared/sops")
        assertNull(control(c).restart())
        assertEquals(1, c.methods().count { it == "config/reload" })
    }
}
