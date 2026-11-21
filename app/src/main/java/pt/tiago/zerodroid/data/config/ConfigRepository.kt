package pt.tiago.zerodroid.data.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pt.tiago.zerodroid.rpc.NotConnectedException
import pt.tiago.zerodroid.rpc.RpcCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.RpcException
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.callAs
import pt.tiago.zerodroid.rpc.proto.AgentDeletePreview
import pt.tiago.zerodroid.rpc.proto.AgentDeleteResult
import pt.tiago.zerodroid.rpc.proto.AliasValuesResult
import pt.tiago.zerodroid.rpc.proto.ApplicationRecord
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.ConfigListResult
import pt.tiago.zerodroid.rpc.proto.ConfigSection
import pt.tiago.zerodroid.rpc.proto.ConfigSectionsResult
import pt.tiago.zerodroid.rpc.proto.ConfigSetEntry
import pt.tiago.zerodroid.rpc.proto.ConfigSetManyParams
import pt.tiago.zerodroid.rpc.proto.ConfigSetManyResult
import pt.tiago.zerodroid.rpc.proto.ConfigStatusResult
import pt.tiago.zerodroid.rpc.proto.ConfigTemplate
import pt.tiago.zerodroid.rpc.proto.ConfigTemplatesResult
import pt.tiago.zerodroid.rpc.proto.MapKeysResult
import pt.tiago.zerodroid.rpc.proto.SessionListResult
import pt.tiago.zerodroid.rpc.userMessage

sealed interface ApplyResult {
    data class Ok(val props: List<String>) : ApplyResult
    data class Rejected(val index: Int?, val prop: String?, val reason: String) : ApplyResult
    data object Forbidden : ApplyResult
    data class Failed(val message: String) : ApplyResult
}

class ConfigForbiddenException : Exception("This device is not allowed to change settings")

private val entryRejection = Regex("""config/set-many entry (\d+) \(`([^`]*)`\) rejected; nothing was saved: (.*)""", RegexOption.DOT_MATCHES_ALL)

/** Parses `rpc-config-set-many-entry-rejected` (crates/zeroclaw-runtime/locales/en/cli.ftl). */
fun parseEntryRejection(message: String): Triple<Int?, String?, String>? =
    entryRejection.find(message)?.let { m -> Triple(m.groupValues[1].toIntOrNull(), m.groupValues[2], m.groupValues[3].trim()) }

/** Types of a typed-family section: templates whose map path sits under the section (as zerocode does). */
fun familyTypes(section: String, templates: List<ConfigTemplate>): List<ConfigTemplate> =
    templates.filter { it.path.startsWith("$section.") }

class ConfigRepository(private val rpc: RpcCaller) {
    private fun obj(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private suspend fun write(method: String, params: JsonElement): JsonElement = try {
        rpc.call(method, params, 30_000)
    } catch (e: RpcException) {
        if (e.error.code == RpcCodes.FORBIDDEN) throw ConfigForbiddenException()
        throw e
    }

    suspend fun sections(): List<ConfigSection> = rpc.callAs<ConfigSectionsResult>("config/sections").sections

    suspend fun fields(prefix: String?): List<ConfigField> =
        rpc.callAs<ConfigListResult>("config/list", if (prefix == null) JsonObject(emptyMap()) else obj("prefix" to prefix)).entries

    suspend fun mapKeys(path: String): List<String> = rpc.callAs<MapKeysResult>("config/map-keys", obj("path" to path)).keys

    suspend fun templates(): List<ConfigTemplate> = rpc.callAs<ConfigTemplatesResult>("config/templates").templates

    suspend fun aliasValues(source: String): List<String> =
        rpc.callAs<AliasValuesResult>("config/resolve-alias-source", obj("source" to source)).values

    suspend fun apply(sets: List<ConfigSetEntry>): ApplyResult = try {
        val params = ZdJson.encodeToJsonElement(ConfigSetManyParams.serializer(), ConfigSetManyParams(sets))
        ApplyResult.Ok(ZdJson.decodeFromJsonElement(ConfigSetManyResult.serializer(), rpc.call("config/set-many", params, 30_000)).props)
    } catch (e: RpcException) {
        val entry = parseEntryRejection(e.error.message)
        when {
            e.error.code == RpcCodes.FORBIDDEN -> ApplyResult.Forbidden
            entry != null -> ApplyResult.Rejected(entry.first, entry.second, entry.third)
            e.error.code == RpcCodes.INVALID_PARAMS -> ApplyResult.Rejected(null, null, e.error.message)
            else -> ApplyResult.Failed(e.userMessage())
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ApplyResult.Failed(e.userMessage())
    }

    suspend fun deleteProp(path: String) { write("config/delete", obj("prop" to path)) }

    suspend fun createKey(path: String, key: String) { write("config/map-key-create", obj("path" to path, "key" to key)) }

    suspend fun renameKey(path: String, from: String, to: String) { write("config/map-key-rename", obj("path" to path, "from" to from, "to" to to)) }

    suspend fun deleteKey(path: String, key: String) { write("config/map-key-delete", obj("path" to path, "key" to key)) }

    suspend fun agentDeletePreview(alias: String): AgentDeletePreview =
        rpc.callAs("agents/delete-preview", obj("alias" to alias))

    suspend fun deleteAgent(alias: String): AgentDeleteResult =
        ZdJson.decodeFromJsonElement(AgentDeleteResult.serializer(), write("agents/delete", obj("alias" to alias)))

    suspend fun status(): List<ApplicationRecord> =
        rpc.callAs<ConfigStatusResult>("config/status").application?.records.orEmpty()

    /**
     * Asks the daemon to restart; the connection dropping right after is expected.
     * Returns an error message, or null when the reload was accepted.
     */
    suspend fun reload(): String? = try {
        write("config/reload", JsonObject(emptyMap()))
        null
    } catch (_: NotConnectedException) {
        null
    } catch (_: java.util.concurrent.TimeoutException) {
        null
    } catch (e: ConfigForbiddenException) {
        e.message
    } catch (e: RpcException) {
        e.error.message
    }

    suspend fun runningTurns(): Int = rpc.callAs<SessionListResult>(
        "session/list",
        buildJsonObject { put("running", true); put("limit", 100) },
    ).sessions.size
}
