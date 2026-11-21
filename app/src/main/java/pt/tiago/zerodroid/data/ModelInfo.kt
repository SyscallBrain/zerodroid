package pt.tiago.zerodroid.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pt.tiago.zerodroid.data.config.ConfigRepository
import pt.tiago.zerodroid.data.config.displayValue
import pt.tiago.zerodroid.rpc.RpcCaller
import pt.tiago.zerodroid.rpc.callAs
import pt.tiago.zerodroid.rpc.proto.CatalogModelsResult
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.SessionOverrides

/** What the chat header shows: the model answering, its provider alias, and whether the session overrides the agent. */
data class ModelLabel(val model: String, val provider: String?, val overridden: Boolean, val reasoning: Reasoning = Reasoning())

/**
 * The daemon's reasoning settings as configured. `runtime.reasoning_*` are global (every agent and
 * model); `passthrough` is the provider's `reasoning_effort_passthrough`. Nothing configured means
 * the daemon sends no reasoning field and the provider's own default applies.
 */
data class Reasoning(val effort: String? = null, val enabled: Boolean? = null, val passthrough: Boolean = false) {
    /**
     * Short label for the header, or null when nothing is configured. The daemon sends the effort
     * whatever `reasoning_enabled` says, and `reasoning_enabled` only switches thinking on Ollama,
     * so on other providers it leaves the provider's default in place.
     */
    val label: String?
        get() = when {
            effort != null -> effort
            enabled != null -> "default"
            else -> null
        }
}

private fun List<ConfigField>.exact(path: String): String? =
    firstOrNull { it.path == path && it.populated }?.let(::displayValue)?.takeIf { it != "—" }

fun resolveReasoning(runtimeFields: List<ConfigField>, providerFields: List<ConfigField>?): Reasoning = Reasoning(
    effort = runtimeFields.exact("runtime.reasoning_effort"),
    enabled = runtimeFields.exact("runtime.reasoning_enabled")?.toBooleanStrictOrNull(),
    passthrough = providerFields?.valueOf("reasoning_effort_passthrough") == "true",
)

const val DEFAULT_MODEL = "default model"

fun providerConfigPath(alias: String): String = "providers.models.$alias"

private fun List<ConfigField>.valueOf(suffix: String): String? =
    firstOrNull { it.path.endsWith(".$suffix") && it.populated }?.let(::displayValue)?.takeIf { it != "—" }

/** Session override first, else the agent's provider alias and that provider's configured model. */
fun resolveModel(
    agentFields: List<ConfigField>,
    providerFields: List<ConfigField>?,
    overrides: SessionOverrides?,
    runtimeFields: List<ConfigField> = emptyList(),
): ModelLabel {
    val provider = overrides?.modelProvider ?: agentFields.valueOf("model_provider")
    val model = overrides?.model ?: providerFields?.valueOf("model") ?: DEFAULT_MODEL
    // An override that names exactly what the agent already uses changes nothing worth flagging.
    val agentProvider = agentFields.valueOf("model_provider")
    val differs = overrides != null && (
        (overrides.modelProvider != null && overrides.modelProvider != agentProvider) ||
            (overrides.model != null && overrides.model != providerFields?.valueOf("model")) ||
            overrides.temperature != null
        )
    return ModelLabel(model, provider, differs, resolveReasoning(runtimeFields, providerFields))
}

class ModelInfo(private val rpc: RpcCaller, private val config: ConfigRepository) {
    suspend fun label(agentAlias: String, overrides: SessionOverrides?): ModelLabel {
        val agent = runCatching { config.fields("agents.$agentAlias") }.getOrDefault(emptyList())
        val provider = overrides?.modelProvider ?: agent.valueOf("model_provider")
        val providerFields = provider?.let { runCatching { config.fields(providerConfigPath(it)) }.getOrNull()?.takeIf { f -> f.isNotEmpty() } }
        val runtime = runCatching { config.fields("runtime") }.getOrDefault(emptyList())
        return resolveModel(agent, providerFields, overrides, runtime)
    }

    suspend fun providers(): List<String> = config.aliasValues("model_providers")

    suspend fun models(provider: String): List<String> =
        rpc.callAs<CatalogModelsResult>("config/catalog-models", buildJsonObject { put("model_provider", provider) }).models
}
