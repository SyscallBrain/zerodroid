package pt.tiago.zerodroid.ui.skills

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.data.config.ConfigRepository
import pt.tiago.zerodroid.data.skills.SkillsRepository
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.ZdJson

class SkillsViewModelsTest {

    private fun repo(rpc: FakeCaller) = SkillsRepository(rpc, ConfigRepository(rpc))

    private fun TestScope.list(rpc: FakeCaller) = SkillsListViewModel(repo(rpc), scopeOverride = this)
    private fun TestScope.editor(rpc: FakeCaller) = SkillEditorViewModel("core", "weather", repo(rpc), scopeOverride = this)

    private val agentsJson = """{"agents":[{"alias":"beta","enabled":true,"channels":[]},{"alias":"alpha","enabled":true,"channels":[]}]}"""

    private fun configListByPrefix(rpc: FakeCaller, byPrefix: Map<String, String>) {
        rpc.handlers["config/list"] = { p ->
            val prefix = (p as JsonObject)["prefix"]?.toString()?.trim('"') ?: ""
            ZdJson.parseToJsonElement(byPrefix[prefix] ?: """{"entries":[]}""")
        }
    }

    private fun fsListByPath(rpc: FakeCaller, byPath: Map<String, String>) {
        rpc.handlers["fs/list_dir"] = { p ->
            val path = (p as JsonObject)["path"]?.toString()?.trim('"') ?: ""
            ZdJson.parseToJsonElement(byPath[path] ?: """{"entries":[],"cwd":"$path"}""")
        }
    }

    @Test
    fun listSelectsFirstAgentAndFiltersToItsBundlesPlusWorkspace() = runTest {
        val rpc = FakeCaller().apply {
            on("agents/list", agentsJson)
            configListByPrefix(this, mapOf("agents.alpha" to """{"entries":[{"path":"agents.alpha.skill_bundles","value":"[\"core\"]","populated":true}]}"""))
            on(
                "skills/list", """
                {"skills":[
                  {"bundle":"core","name":"weather","directory":"/install/shared/skills/core/weather","frontmatter":{"name":"weather","description":"Forecast"}},
                  {"bundle":"core","name":"news","directory":"/install/shared/skills/core/news","frontmatter":{"name":"news","description":"Headlines"}},
                  {"bundle":"other","name":"secret","directory":"/install/shared/skills/other/secret","frontmatter":{"name":"secret","description":"Not alpha's"}}
                ]}
            """)
            on("skills/bundles", """{"bundles":[{"alias":"core","directory":"/install/shared/skills/core","include":[],"exclude":[]}]}""")
            fsListByPath(
                this, mapOf(
                    "/install/agents/alpha/workspace/skills" to """{"entries":[{"name":"notes","full_path":"/install/agents/alpha/workspace/skills/notes","is_dir":true},{"name":"drafts","full_path":"/install/agents/alpha/workspace/skills/drafts","is_dir":true}],"cwd":"x"}""",
                    "/install/agents/alpha/workspace/skills/notes" to """{"entries":[{"name":"SKILL.md","full_path":"...","is_dir":false}],"cwd":"x"}""",
                    "/install/agents/alpha/workspace/skills/drafts" to """{"entries":[{"name":"TODO.md","full_path":"...","is_dir":false}],"cwd":"x"}""",
                ),
            )
        }
        val v = list(rpc); v.refresh(); advanceUntilIdle()
        val s = v.state.value
        assertFalse(s.loading)
        assertEquals("alpha", s.selectedAgent)
        // The daemon parses skills/list params as a struct: they must be `{}`/object, never null.
        val listCall = rpc.sent.last { it.method == "skills/list" }
        assertTrue(listCall.params is JsonObject)
        // Only the agent's own bundle: "other/secret" is filtered out.
        assertEquals(listOf("news", "weather"), s.bundleSkills.map { it.name })
        assertTrue(s.bundleSkills.all { it.bundle == "core" && it.editable })
        // Only the workspace dir that actually holds a SKILL.md.
        assertEquals(listOf("notes"), s.workspaceSkills.map { it.name })
        assertFalse(s.workspaceSkills.first().editable)
    }

    @Test
    fun listForbiddenSkillsSetsFlag() = runTest {
        val rpc = FakeCaller().apply {
            on("agents/list", agentsJson)
            configListByPrefix(this, mapOf("agents.alpha" to """{"entries":[{"path":"agents.alpha.skill_bundles","value":"[\"core\"]","populated":true}]}"""))
            fail("skills/list", RpcCodes.FORBIDDEN)
        }
        val v = list(rpc); v.refresh(); advanceUntilIdle()
        assertTrue(v.state.value.forbidden)
        assertTrue(v.state.value.bundleSkills.isEmpty())
    }

    @Test
    fun selectAgentReloadsWithThatAgentsBundles() = runTest {
        val rpc = FakeCaller().apply {
            on("agents/list", agentsJson)
            configListByPrefix(
                this, mapOf(
                    "agents.alpha" to """{"entries":[{"path":"agents.alpha.skill_bundles","value":"[]","populated":true}]}""",
                    "agents.beta" to """{"entries":[{"path":"agents.beta.skill_bundles","value":"[\"core\"]","populated":true}]}""",
                ),
            )
            on("skills/list", """{"skills":[{"bundle":"core","name":"weather","directory":"d","frontmatter":{"name":"weather","description":"Forecast"}}]}""")
            on("skills/bundles", """{"bundles":[{"alias":"core","directory":"/install/shared/skills/core"}]}""")
            fsListByPath(this, emptyMap())
        }
        val v = list(rpc); v.refresh(); advanceUntilIdle()
        assertTrue(v.state.value.bundleSkills.isEmpty())
        v.selectAgent("beta"); advanceUntilIdle()
        assertEquals("beta", v.state.value.selectedAgent)
        assertEquals(listOf("weather"), v.state.value.bundleSkills.map { it.name })
    }

    private val readJson = """
        {"bundle":"core","name":"weather",
         "frontmatter":{"name":"weather","description":"Forecast","tags":["meteo"],"always":false,
           "slash_options":[{"name":"city","description":"City","type":"string"}]},
         "body":"# Weather\nUse the API."}
    """

    @Test
    fun editorLoadsReadsAndSavesPreservingSlashOptions() = runTest {
        val rpc = FakeCaller().apply {
            on("skills/read", readJson)
            on("skills/write", """{"bundle":"core","name":"weather","written":true}""")
        }
        val v = editor(rpc); v.load(); advanceUntilIdle()
        var s = v.state.value
        assertFalse(s.loading)
        assertFalse(s.dirty)
        assertEquals("Forecast", s.description)
        assertEquals("# Weather\nUse the API.", s.body)
        v.update { it.copy(description = "Better forecast", tags = "meteo, travel", body = "new body") }
        s = v.state.value
        assertTrue(s.dirty)
        v.save(); advanceUntilIdle()
        s = v.state.value
        assertTrue(s.saved)
        assertFalse(s.dirty)
        val write = rpc.sent.last { it.method == "skills/write" }.json
        assertTrue(write.contains(""""description":"Better forecast""""))
        assertTrue(write.contains(""""body":"new body""""))
        // slash options are not editable in the app and must round-trip untouched
        assertTrue(write.contains(""""slash_options":[{"name":"city""""))
        assertTrue(write.contains(""""tags":["meteo","travel"]"""))
    }

    @Test
    fun editorDeleteMarksDeleted() = runTest {
        val rpc = FakeCaller().apply {
            on("skills/read", readJson)
            on("skills/delete", """{"bundle":"core","name":"weather","deleted":true}""")
        }
        val v = editor(rpc); v.load(); advanceUntilIdle()
        v.delete(); advanceUntilIdle()
        assertTrue(v.state.value.deleted)
        assertNull(v.state.value.error)
    }

    @Test
    fun editorReadForbiddenShowsError() = runTest {
        val rpc = FakeCaller().apply { fail("skills/read", RpcCodes.FORBIDDEN) }
        val v = editor(rpc); v.load(); advanceUntilIdle()
        assertTrue(v.state.value.forbidden)
        assertFalse(v.state.value.loading)
    }
}
