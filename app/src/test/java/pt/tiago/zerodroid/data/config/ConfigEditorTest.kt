package pt.tiago.zerodroid.data.config

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.data.ApprovalCenter
import pt.tiago.zerodroid.data.ChatSessions
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.proto.ApplyOutcome
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.ConfigSection
import pt.tiago.zerodroid.rpc.proto.PropKind
import pt.tiago.zerodroid.rpc.proto.SessionUpdate

class ConfigEditorTest {
    private val rpc = FakeCaller()

    private fun TestScope.editor(): Pair<ConfigEditor, ChatSessions> {
        val chats = ChatSessions(rpc, ApprovalCenter(rpc), backgroundScope)
        return ConfigEditor(ConfigRepository(rpc), chats, backgroundScope) to chats
    }

    private fun field(path: String, value: String) = ConfigField(path = path, kind = PropKind.String, value = JsonPrimitive(value), populated = true)

    private fun listJson(vararg f: Pair<String, String>) =
        """{"entries":[${f.joinToString(",") { (p, v) -> """{"path":"$p","kind":"string","value":"$v","populated":true,"is_secret":false,"description":""}""" }}]}"""

    private val status = """{"application":{"records":[{"path":["agents","coder","model"],"outcome":"applied_live"},{"path":["gateway","port"],"outcome":"queued_for_reload"}]}}"""

    @Test
    fun applyClearsDraftOnSuccessAndReportsOutcomes() = runTest(UnconfinedTestDispatcher()) {
        val (ed, _) = editor()
        rpc.on("config/list", listJson("agents.coder.model" to "a"))
        rpc.on("config/set-many", """{"props":["agents.coder.model"],"set":true}""")
        rpc.on("config/status", status)
        ed.draft.stage(field("agents.coder.model", "a"), "agents", DraftValue.Plain(JsonPrimitive("b")))
        val r = ed.apply()
        assertTrue(r.result is ApplyResult.Ok)
        assertTrue(ed.draft.entries.value.isEmpty())
        assertEquals(ApplyOutcome.AppliedLive, r.outcomes["agents.coder.model"])
        assertEquals("records for props this apply did not write are left out", null, r.outcomes["gateway.port"])
        assertEquals(r, ed.lastApply.value)
    }

    @Test
    fun rejectedApplyKeepsDraftAndFlagsEntry() = runTest(UnconfinedTestDispatcher()) {
        val (ed, _) = editor()
        rpc.on("config/list", listJson("agents.coder.model" to "a"))
        rpc.fail("config/set-many", RpcCodes.INVALID_PARAMS, "config/set-many entry 0 (`agents.coder.model`) rejected; nothing was saved: unknown model")
        ed.draft.stage(field("agents.coder.model", "a"), "agents", DraftValue.Plain(JsonPrimitive("zzz")))
        val r = ed.apply()
        assertEquals(ApplyResult.Rejected(0, "agents.coder.model", "unknown model"), r.result)
        assertEquals(1, ed.draft.entries.value.size)
        assertFalse(ed.readOnly.value)
    }

    @Test
    fun forbiddenMakesEditorReadOnly() = runTest(UnconfinedTestDispatcher()) {
        val (ed, _) = editor()
        rpc.on("config/list", listJson("agents.coder.model" to "a"))
        rpc.fail("config/set-many", RpcCodes.FORBIDDEN, "no")
        ed.draft.stage(field("agents.coder.model", "a"), "agents", DraftValue.Plain(JsonPrimitive("b")))
        assertEquals(ApplyResult.Forbidden, ed.apply().result)
        assertTrue(ed.readOnly.value)
        rpc.fail("config/map-key-create", RpcCodes.FORBIDDEN, "no")
        val e2 = editor().first
        runCatching { e2.addEntry("agents", "bob") }
        assertTrue(e2.readOnly.value)
    }

    @Test
    fun applyStopsOnConflict() = runTest(UnconfinedTestDispatcher()) {
        val (ed, _) = editor()
        rpc.on("config/list", listJson("agents.coder.model" to "changed-on-desktop"))
        rpc.on("config/set-many", """{"props":["agents.coder.model"],"set":true}""")
        ed.draft.stage(field("agents.coder.model", "a"), "agents", DraftValue.Plain(JsonPrimitive("b")))
        val r = ed.apply()
        assertEquals(listOf("agents.coder.model"), r.conflicts)
        assertFalse(rpc.methods().contains("config/set-many"))
        val r2 = ed.apply(confirmedConflicts = setOf("agents.coder.model"))
        assertTrue(r2.result is ApplyResult.Ok)
        assertTrue(rpc.methods().contains("config/set-many"))
    }

    @Test
    fun over256IsRefused() = runTest(UnconfinedTestDispatcher()) {
        val (ed, _) = editor()
        repeat(257) { ed.draft.stage(field("f.k$it", "a"), "f", DraftValue.Plain(JsonPrimitive("b"))) }
        val r = ed.apply()
        assertEquals(ApplyResult.Failed("Too many changes at once (max 256). Apply in parts."), r.result)
        assertFalse(rpc.methods().contains("config/set-many"))
    }

    @Test
    fun renameRefusedWithStagedEdits() = runTest(UnconfinedTestDispatcher()) {
        val (ed, _) = editor()
        ed.draft.stage(field("agents.bob.model", "a"), "agents", DraftValue.Plain(JsonPrimitive("b")))
        val r = runCatching { ed.renameEntry("agents", "bob", "rob") }
        assertTrue(r.isFailure)
        assertTrue(rpc.sent.isEmpty())
        ed.draft.clear()
        ed.renameEntry("agents", "bob", "rob")
        assertEquals("config/map-key-rename", rpc.sent.single().method)
    }

    @Test
    fun deleteEntryUnstagesItsFields() = runTest(UnconfinedTestDispatcher()) {
        val (ed, _) = editor()
        ed.draft.stage(field("channels.discord.old.token_env", "a"), "channels", DraftValue.Plain(JsonPrimitive("b")))
        ed.draft.stage(field("channels.discord.keep.token_env", "a"), "channels", DraftValue.Plain(JsonPrimitive("b")))
        val before = ed.structureVersion.value
        ed.deleteEntry("channels.discord", "old")
        assertEquals(before + 1, ed.structureVersion.value)
        assertEquals(listOf("channels.discord.keep.token_env"), ed.draft.entries.value.map { it.path })
        assertEquals("""{"path":"channels.discord","key":"old"}""", rpc.sent.single().json)
    }

    @Test
    fun reloadBlockedWhileTurnsRun() = runTest(UnconfinedTestDispatcher()) {
        val (ed, chats) = editor()
        assertEquals(ReloadGate.NotNeeded, ed.reloadGate())
        rpc.on("config/list", listJson("gateway.port" to "1"))
        rpc.on("config/set-many", """{"props":["gateway.port"],"set":true}""")
        rpc.on("config/status", status)
        rpc.on("session/list", """{"sessions":[]}""")
        ed.draft.stage(field("gateway.port", "1"), "gateway", DraftValue.Plain(JsonPrimitive("2")))
        ed.apply()
        assertEquals(ReloadGate.Allowed, ed.reloadGate())
        chats.get("s", "coder").onUpdate(SessionUpdate.AgentMessageChunk("s", "x"))
        assertEquals(ReloadGate.Blocked(1), ed.reloadGate())
    }

    @Test
    fun searchRanksPathPrefixFirst() {
        val idx = listOf(
            field("channels.discord.x.agent", "coder"),
            field("agents.coder.model", "opus"),
            ConfigField(path = "providers.models.a.d.api_key", kind = PropKind.String, isSecret = true, description = "Key for coder"),
        )
        val sections = listOf(ConfigSection("agents", "Agents"), ConfigSection("channels", "Channels"), ConfigSection("providers.models", "Model providers"))
        val hits = ConfigEditor.search("coder", idx, sections)
        assertEquals("agents.coder.model", hits.first().field.path)
        assertEquals(3, hits.size)
        assertEquals("Model providers", hits.first { it.field.isSecret }.sectionLabel)
        assertTrue(ConfigEditor.search("opus", idx, sections).single().field.path == "agents.coder.model")
        assertTrue(ConfigEditor.search("sk-", idx, sections).isEmpty())
    }

    @Test
    fun validateAliasRules() {
        assertNotNull(ConfigEditor.validateAlias(" ", emptyList()))
        assertNotNull(ConfigEditor.validateAlias("a.b", emptyList()))
        assertNotNull(ConfigEditor.validateAlias("a b", emptyList()))
        assertNotNull(ConfigEditor.validateAlias("bob", listOf("bob")))
        assertNull(ConfigEditor.validateAlias("rob", listOf("bob")))
    }

    @Test
    fun applyBumpsVersionSoFormsReload() = runTest(UnconfinedTestDispatcher()) {
        val (ed, _) = editor()
        rpc.on("config/list", listJson("a.b" to "1"))
        rpc.on("config/set-many", """{"props":["a.b"],"set":true}""")
        ed.draft.stage(field("a.b", "1"), "a", DraftValue.Plain(JsonPrimitive("2")))
        val v = ed.structureVersion.value
        ed.apply()
        assertEquals(v + 1, ed.structureVersion.value)
    }

    @Test
    fun concurrentApplySendsOnce() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        rpc.on("config/list", listJson("a.b" to "1"))
        rpc.on("config/set-many", """{"props":["a.b"],"set":true}""")
        val slow = pt.tiago.zerodroid.rpc.RpcCaller { m, p, t ->
            if (m == "config/set-many") gate.await()
            rpc.call(m, p, t)
        }
        val chats = ChatSessions(slow, ApprovalCenter(slow), backgroundScope)
        val ed = ConfigEditor(ConfigRepository(slow), chats, backgroundScope)
        ed.draft.stage(field("a.b", "1"), "a", DraftValue.Plain(JsonPrimitive("2")))
        ed.applyInBackground()
        testScheduler.runCurrent()
        ed.applyInBackground()
        testScheduler.runCurrent()
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, rpc.methods().count { it == "config/set-many" })
        assertTrue(ed.lastApply.value?.result is ApplyResult.Ok)
    }

    @Test
    fun failedDeleteKeepsStagedEdits() = runTest(UnconfinedTestDispatcher()) {
        val (ed, _) = editor()
        rpc.fail("config/map-key-delete", RpcCodes.INTERNAL_ERROR, "disk full")
        ed.draft.stage(field("channels.discord.old.token_env", "a"), "channels", DraftValue.Plain(JsonPrimitive("b")))
        assertTrue(runCatching { ed.deleteEntry("channels.discord", "old") }.isFailure)
        assertEquals(1, ed.draft.entries.value.size)
        rpc.fail("config/delete", RpcCodes.INTERNAL_ERROR, "disk full")
        ed.draft.stage(ConfigField(path = "p.k", kind = PropKind.String, isSecret = true), "p", DraftValue.Secret("x"))
        assertTrue(runCatching { ed.clearSecret("p.k") }.isFailure)
        assertTrue(ed.draft.isStaged("p.k"))
    }

    @Test
    fun outcomesOnlyCoverWhatWasApplied() = runTest(UnconfinedTestDispatcher()) {
        val (ed, _) = editor()
        rpc.on("config/list", listJson("agents.coder.model" to "a"))
        rpc.on("config/set-many", """{"props":["agents.coder.model"],"set":true}""")
        rpc.on("config/status", status) // also carries an unrelated gateway.port queued_for_reload
        rpc.on("session/list", """{"sessions":[]}""")
        ed.draft.stage(field("agents.coder.model", "a"), "agents", DraftValue.Plain(JsonPrimitive("b")))
        val r = ed.apply()
        assertEquals(setOf("agents.coder.model"), r.outcomes.keys)
        assertEquals(ReloadGate.NotNeeded, ed.reloadGate())
    }

    @Test
    fun rejectionNotMatchingAnyDraftEntryGetsABanner() {
        assertEquals("bad", ConfigEditor.rejectionBanner(ApplyResult.Rejected(0, "other.path", "bad"), setOf("a.b")))
        assertEquals("bad", ConfigEditor.rejectionBanner(ApplyResult.Rejected(null, null, "bad"), setOf("a.b")))
        assertEquals(null, ConfigEditor.rejectionBanner(ApplyResult.Rejected(0, "a.b", "bad"), setOf("a.b")))
        assertEquals(null, ConfigEditor.rejectionBanner(ApplyResult.Ok(emptyList()), setOf("a.b")))
    }

    @Test
    fun savedDraftSurvivesAFailedIndexAndRestoresLater() = runTest(UnconfinedTestDispatcher()) {
        val saved = ConfigDraft().also {
            it.stage(field("a.b", "1"), "a", DraftValue.Plain(JsonPrimitive("2")))
        }.persistable()
        var stored: String? = saved
        val persistence = object : DraftPersistence {
            override suspend fun load() = stored
            override suspend fun save(json: String) { stored = json }
        }
        val chats = ChatSessions(rpc, ApprovalCenter(rpc), backgroundScope)
        val ed = ConfigEditor(ConfigRepository(rpc), chats, backgroundScope, persistence)
        rpc.on("config/sections", """{"sections":[]}""")
        rpc.fail("config/list", RpcCodes.INTERNAL_ERROR, "busy")
        ed.loadSections()
        assertTrue(ed.draft.entries.value.isEmpty())
        assertEquals(saved, stored)
        rpc.on("config/list", listJson("a.b" to "1"))
        ed.loadSections()
        assertEquals(listOf("a.b"), ed.draft.entries.value.map { it.path })
    }
}
