package pt.tiago.zerodroid.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import pt.tiago.zerodroid.data.config.ConfigRepository
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.PropKind
import pt.tiago.zerodroid.rpc.proto.SessionOverrides

class ModelInfoTest {
    private fun f(path: String, v: String) = ConfigField(path = path, kind = PropKind.String, value = JsonPrimitive(v), populated = true)
    private val agent = listOf(f("agents.coder.model_provider", "anthropic.default"), f("agents.coder.risk_profile", "yolo"))
    private val provider = listOf(f("providers.models.anthropic.default.model", "claude-opus-4-5"))

    @Test
    fun agentDefaultResolvesThroughProvider() {
        assertEquals(ModelLabel("claude-opus-4-5", "anthropic.default", false), resolveModel(agent, provider, null))
        assertEquals(ModelLabel("claude-opus-4-5", "anthropic.default", false), resolveModel(agent, provider, SessionOverrides()))
    }

    @Test
    fun overrideWins() {
        assertEquals(
            ModelLabel("qwen3", "ollama.local", true),
            resolveModel(agent, provider, SessionOverrides(model = "qwen3", modelProvider = "ollama.local")),
        )
    }

    @Test
    fun danglingProviderShowsAlias() {
        assertEquals(ModelLabel("default model", "anthropic.default", false), resolveModel(agent, null, null))
        assertEquals(ModelLabel("default model", null, false), resolveModel(emptyList(), null, null))
    }

    @Test
    fun labelReadsAgentThenProviderConfig() = runTest {
        val rpc = FakeCaller()
        rpc.handlers["config/list"] = { p ->
            val prefix = (p as kotlinx.serialization.json.JsonObject)["prefix"].toString().trim('"')
            pt.tiago.zerodroid.rpc.ZdJson.parseToJsonElement(
                if (prefix == "agents.coder") """{"entries":[{"path":"agents.coder.model_provider","kind":"alias_ref","value":"anthropic.default","populated":true,"is_secret":false,"description":""}]}"""
                else """{"entries":[{"path":"providers.models.anthropic.default.model","kind":"string","value":"claude-opus-4-5","populated":true,"is_secret":false,"description":""}]}""",
            )
        }
        val label = ModelInfo(rpc, ConfigRepository(rpc)).label("coder", null)
        assertEquals("claude-opus-4-5", label.model)
        assertEquals("""{"prefix":"providers.models.anthropic.default"}""", rpc.sent[1].json)
    }

    @Test
    fun catalogAndProviders() = runTest {
        val rpc = FakeCaller().apply {
            on("config/resolve-alias-source", """{"source":"model_providers","values":["anthropic.default","ollama.local"]}""")
            on("config/catalog-models", """{"model_provider":"ollama.local","models":["qwen3","llama4"],"local":true,"live":false}""")
        }
        val info = ModelInfo(rpc, ConfigRepository(rpc))
        assertEquals(listOf("anthropic.default", "ollama.local"), info.providers())
        assertEquals(listOf("qwen3", "llama4"), info.models("ollama.local"))
        assertEquals("""{"model_provider":"ollama.local"}""", rpc.sent.last().json)
    }

    @Test
    fun overrideEqualToAgentDefaultsIsNotFlagged() {
        val same = resolveModel(agent, provider, SessionOverrides(model = "claude-opus-4-5", modelProvider = "anthropic.default"))
        assertEquals(false, same.overridden)
        val providerOnly = resolveModel(agent, provider, SessionOverrides(modelProvider = "anthropic.default"))
        assertEquals(false, providerOnly.overridden)
    }

    private fun b(path: String, v: Boolean) = ConfigField(path = path, kind = PropKind.Bool, value = JsonPrimitive(v), populated = true)

    @Test
    fun reasoningUnsetLeavesItToTheProvider() {
        val r = resolveModel(agent, provider, null).reasoning
        assertEquals(Reasoning(), r)
        assertEquals(null, r.label)
    }

    @Test
    fun reasoningReadsRuntimeAndProviderPassthrough() {
        val runtime = listOf(f("runtime.reasoning_effort", "high"), b("runtime.reasoning_enabled", true))
        val prov = provider + b("providers.models.anthropic.default.reasoning_effort_passthrough", true)
        assertEquals(Reasoning("high", true, true), resolveModel(agent, prov, null, runtime).reasoning)
        assertEquals("high", Reasoning("high", true).label)
    }

    @Test
    fun reasoningLabels() {
        assertEquals("default", Reasoning(null, false).label)
        // The effort is still sent when reasoning_enabled is false.
        assertEquals("high", Reasoning("high", false).label)
        assertEquals("default", Reasoning(null, true).label)
        // Only the exact runtime path counts, not a same-named field elsewhere.
        assertEquals(null, resolveReasoning(listOf(f("runtime_profiles.x.reasoning_effort", "low")), null).effort)
    }
}
