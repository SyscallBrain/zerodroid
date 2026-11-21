package pt.tiago.zerodroid.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.FakeCaller

class SessionsRepositoryTest {
    private fun entry(id: String, last: String) =
        """{"session_id":"$id","session_key":"k$id","created_at":"2026-11-01T00:00:00Z","last_activity":"$last","message_count":2,"agent_alias":"main"}"""

    @Test
    fun listSortsByLastActivityDesc() = runTest {
        val rpc = FakeCaller().apply {
            on("session/list", """{"sessions":[${entry("a", "2026-11-19T10:00:00Z")},${entry("b", "2026-11-20T10:00:00Z")}]}""")
        }
        assertEquals(listOf("b", "a"), SessionsRepository(rpc).list().map { it.sessionId })
        assertEquals("""{"limit":100}""", rpc.sent.single().json)
    }

    @Test
    fun createSendsKeepSiblingsAndMarksOwned() = runTest {
        val rpc = FakeCaller().apply { on("session/new", """{"session_id":"n1","agent_alias":"coder","message_count":0,"workspace_dir":"/w"}""") }
        val repo = SessionsRepository(rpc)
        val r = repo.create("coder")
        assertEquals("""{"agent_alias":"coder","keep_siblings":true}""", rpc.sent.single().json)
        assertTrue(repo.isOwnedHere(r.sessionId))
    }

    @Test
    fun attachSendsSessionId() = runTest {
        val rpc = FakeCaller().apply { on("session/new", """{"session_id":"x","agent_alias":"main","message_count":3,"workspace_dir":"/w"}""") }
        SessionsRepository(rpc).attach("x", "main")
        assertEquals("""{"agent_alias":"main","session_id":"x","keep_siblings":true}""", rpc.sent.single().json)
    }

    @Test
    fun liveElsewhereDetected() = runTest {
        val rpc = FakeCaller().apply { on("session/list", """{"sessions":[${entry("a", "2026-11-20T10:00:00Z")}]}""") }
        val repo = SessionsRepository(rpc)
        assertTrue(repo.isLiveElsewhere("a", runningHere = emptySet()))
        assertFalse(repo.isLiveElsewhere("a", runningHere = setOf("a")))
        assertFalse(repo.isLiveElsewhere("zzz", runningHere = emptySet()))
    }

    @Test
    fun deleteSendsSessionIdAndDropsOwnership() = runTest {
        val rpc = FakeCaller().apply {
            on("session/new", """{"session_id":"d1","agent_alias":"main","message_count":0,"workspace_dir":"/w"}""")
            on("session/delete", """{}""")
        }
        val repo = SessionsRepository(rpc)
        repo.create("main")
        repo.delete("d1")
        assertEquals("session/delete", rpc.sent.last().method)
        assertEquals("""{"session_id":"d1"}""", rpc.sent.last().json)
        assertFalse(repo.isOwnedHere("d1"))
    }
}
