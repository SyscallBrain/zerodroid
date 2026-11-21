package pt.tiago.zerodroid.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.RpcError
import pt.tiago.zerodroid.rpc.RpcException
import pt.tiago.zerodroid.rpc.ZdJson

class CodeModeTest {
    private val newResult = """{"session_id":"c1","agent_alias":"coder","message_count":0,"workspace_dir":"/w/proj"}"""

    @Test
    fun createCodeSendsAcpModeLikeZerocode() = runTest {
        val rpc = FakeCaller().apply { on("session/new", newResult) }
        val repo = SessionsRepository(rpc)
        val r = repo.createCode("coder", "/w/proj")
        assertEquals(
            """{"agent_alias":"coder","cwd":"/w/proj","keep_siblings":true,"chat_mode":"acp","exclude_memory":true,"interaction_surface":"zerocode_code"}""",
            rpc.sent.single().json,
        )
        assertEquals("/w/proj", r.workspaceDir)
        assertTrue(repo.isOwnedHere("c1"))
    }

    @Test
    fun createCodeWithoutFolderOmitsCwd() = runTest {
        val rpc = FakeCaller().apply { on("session/new", newResult) }
        SessionsRepository(rpc).createCode("coder", null)
        assertEquals(false, rpc.sent.single().json.contains("cwd"))
    }

    @Test
    fun listCodeUsesAcpListNewestFirst() = runTest {
        fun e(id: String, t: String) = """{"session_id":"$id","session_key":"$id","created_at":"$t","last_activity":"$t","message_count":1,"agent_alias":"coder"}"""
        val rpc = FakeCaller().apply { on("session/list-acp", """{"sessions":[${e("a", "2026-11-19T10:00:00Z")},${e("b", "2026-11-20T10:00:00Z")}]}""") }
        assertEquals(listOf("b", "a"), SessionsRepository(rpc).listCode().map { it.sessionId })
        assertEquals("session/list-acp", rpc.sent.single().method)
    }

    @Test
    fun listDirsKeepsFoldersSortedByName() = runTest {
        val rpc = FakeCaller().apply {
            on(
                "fs/list_dir",
                """{"cwd":"/w","entries":[
                  {"name":"zeta","full_path":"/w/zeta","is_dir":true,"is_hidden":false,"size":0},
                  {"name":"notes.txt","full_path":"/w/notes.txt","is_dir":false,"is_hidden":false,"size":3},
                  {"name":"Alpha","full_path":"/w/Alpha","is_dir":true,"is_hidden":false,"size":0}]}""",
            )
        }
        val r = SessionsRepository(rpc).listDirs("/w")
        assertEquals(listOf("Alpha", "zeta"), r.entries.map { it.name })
        assertEquals("""{"path":"/w"}""", rpc.sent.single().json)
    }

    private fun TestScope.codeSession(rpc: FakeCaller) =
        ChatSession("c1", "coder", rpc, ApprovalCenter(rpc), backgroundScope, code = true)

    @Test
    fun reattachOfCodeSessionKeepsAcpMode() = runTest(UnconfinedTestDispatcher()) {
        var first = true
        val rpc = FakeCaller().apply {
            handlers["session/prompt"] = {
                if (first) { first = false; throw RpcException(RpcError(RpcCodes.SESSION_NOT_FOUND, "gone")) }
                ZdJson.parseToJsonElement("{}")
            }
            on("session/new", newResult)
        }
        codeSession(rpc).send("hi")
        val attach = rpc.sent.first { it.method == "session/new" }
        assertEquals(
            """{"agent_alias":"coder","session_id":"c1","keep_siblings":true,"chat_mode":"acp","exclude_memory":true,"interaction_surface":"zerocode_code"}""",
            attach.json,
        )
    }

    @Test
    fun registryMarksExistingSessionAsCode() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        val chats = ChatSessions(rpc, ApprovalCenter(rpc), backgroundScope)
        val plain = chats.get("c1", "coder")
        assertEquals(false, plain.code)
        assertTrue(chats.get("c1", "coder", code = true).code)
        // A later plain lookup (notification tap) does not demote it.
        assertTrue(chats.get("c1", "coder").code)
    }

    @Test
    fun historyStripsRuntimeDatePrefix() {
        val s = fromHistory(
            ZdJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(pt.tiago.zerodroid.rpc.proto.MessageEntry.serializer()),
                """[{"role":"user","content":"[CURRENT DATE & TIME: 2026-11-21 18:00 WET]\nfix the build"}]""",
            ),
        )
        assertEquals("fix the build", (s.items.single() as ChatItem.User).text)
    }

    private class MemPersistence(var stored: CodeFolderState = CodeFolderState()) : CodeFolderPersistence {
        override suspend fun load() = stored
        override suspend fun save(s: CodeFolderState) { stored = s }
    }

    @Test
    fun foldersRememberedPerSessionAndRecentDeduped() = runTest {
        val p = MemPersistence()
        val f = CodeFolders(p, maxRecent = 2)
        f.remember("a", "/w/one")
        f.remember("b", "/w/two")
        f.remember("c", "/w/one")
        f.remember("d", "/w/three")
        assertEquals("/w/one", f.folderOf("a"))
        assertEquals(listOf("/w/three", "/w/one"), p.stored.recent)
        f.forget("a")
        assertNull(f.folderOf("a"))
        val reloaded = CodeFolders(p).apply { load() }
        assertEquals("/w/two", reloaded.folderOf("b"))
    }

    @Test
    fun folderPathHelpers() {
        assertEquals("proj", folderName("/w/proj/"))
        assertEquals("/", folderName("/"))
        assertEquals("/w", parentFolder("/w/proj"))
        assertEquals("/", parentFolder("/w"))
        assertNull(parentFolder("/"))
    }
}
