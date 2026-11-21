package pt.tiago.zerodroid.data.config

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.proto.ConfigSetEntry
import pt.tiago.zerodroid.rpc.proto.ConfigTemplate

class ConfigRepositoryTest {
    private val rpc = FakeCaller()
    private val repo = ConfigRepository(rpc)

    @Test
    fun fieldsSendsPrefix() = runTest {
        rpc.on("config/list", """{"entries":[{"path":"agents.coder.enabled","kind":"bool","value":"true","populated":true,"is_secret":false,"description":""}]}""")
        assertEquals(1, repo.fields("agents.coder").size)
        assertEquals("""{"prefix":"agents.coder"}""", rpc.sent.single().json)
    }

    @Test
    fun applySendsSetMany() = runTest {
        rpc.on("config/set-many", """{"props":["a"],"set":true}""")
        val r = repo.apply(listOf(ConfigSetEntry("a", JsonPrimitive(1), "ZeroDroid")))
        assertEquals(ApplyResult.Ok(listOf("a")), r)
        assertEquals("config/set-many", rpc.sent.single().method)
        assertEquals("""{"sets":[{"prop":"a","value":1,"comment":"ZeroDroid"}]}""", rpc.sent.single().json)
    }

    @Test
    fun setManyRejectionMapsToEntry() = runTest {
        rpc.fail("config/set-many", RpcCodes.INVALID_PARAMS, "config/set-many entry 1 (`agents.coder.model`) rejected; nothing was saved: unknown provider")
        val r = repo.apply(listOf(ConfigSetEntry("x", JsonPrimitive(1))))
        assertEquals(ApplyResult.Rejected(1, "agents.coder.model", "unknown provider"), r)
    }

    @Test
    fun plainInvalidParamsIsRejectedWithoutEntry() = runTest {
        rpc.fail("config/set-many", RpcCodes.INVALID_PARAMS, "bad value")
        assertEquals(ApplyResult.Rejected(null, null, "bad value"), repo.apply(listOf(ConfigSetEntry("x", JsonPrimitive(1)))))
    }

    @Test
    fun forbiddenMapsToForbidden() = runTest {
        rpc.fail("config/set-many", RpcCodes.FORBIDDEN, "no")
        rpc.fail("config/map-key-create", RpcCodes.FORBIDDEN, "no")
        assertEquals(ApplyResult.Forbidden, repo.apply(listOf(ConfigSetEntry("x", JsonPrimitive(1)))))
        try {
            repo.createKey("agents", "bob")
            fail("expected ConfigForbiddenException")
        } catch (_: ConfigForbiddenException) {
        }
    }

    @Test
    fun structuralCallsSendExactParams() = runTest {
        repo.createKey("providers.models.ollama", "local")
        repo.renameKey("agents", "bob", "rob")
        repo.deleteKey("channels.discord", "old")
        repo.deleteProp("providers.models.anthropic.default.api_key")
        assertEquals(
            listOf(
                """{"path":"providers.models.ollama","key":"local"}""",
                """{"path":"agents","from":"bob","to":"rob"}""",
                """{"path":"channels.discord","key":"old"}""",
                """{"prop":"providers.models.anthropic.default.api_key"}""",
            ),
            rpc.sent.map { it.json },
        )
    }

    @Test
    fun typedFamilyTypesFromTemplates() {
        val t = listOf(ConfigTemplate("agents"), ConfigTemplate("providers.models.anthropic"), ConfigTemplate("providers.models.ollama"), ConfigTemplate("channels.discord"))
        assertEquals(listOf("providers.models.anthropic", "providers.models.ollama"), familyTypes("providers.models", t).map { it.path })
    }

    @Test
    fun runningTurnsCounts() = runTest {
        rpc.on("session/list", """{"sessions":[{"session_id":"a","session_key":"k","created_at":"t","last_activity":"t","message_count":1}]}""")
        assertEquals(1, repo.runningTurns())
        assertTrue(rpc.sent.single().json.contains("\"running\":true"))
    }

    @Test
    fun parseEntryRejectionHandlesForeignText() {
        assertEquals(null, parseEntryRejection("something else"))
    }

    @Test
    fun entryRejectionParsedForAnyCodeButForbidden() = runTest {
        rpc.fail("config/set-many", RpcCodes.INTERNAL_ERROR, "config/set-many entry 0 (`a.b`) rejected; nothing was saved: bad enum")
        assertEquals(ApplyResult.Rejected(0, "a.b", "bad enum"), repo.apply(listOf(ConfigSetEntry("a.b", JsonPrimitive(1)))))
    }

    @Test
    fun reloadErrorsAreReturnedNotThrown() = runTest {
        rpc.fail("config/reload", RpcCodes.INTERNAL_ERROR, "Reload not available")
        assertEquals("Reload not available", repo.reload())
        rpc.fail("config/reload", RpcCodes.FORBIDDEN, "no")
        assertEquals("This device is not allowed to change settings", repo.reload())
        rpc.on("config/reload", """{"reloading":true}""")
        assertEquals(null, repo.reload())
    }
}
