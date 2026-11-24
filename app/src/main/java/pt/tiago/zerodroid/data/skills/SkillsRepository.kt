package pt.tiago.zerodroid.data.skills

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import pt.tiago.zerodroid.data.config.ConfigForbiddenException
import pt.tiago.zerodroid.data.config.ConfigRepository
import pt.tiago.zerodroid.rpc.NotConnectedException
import pt.tiago.zerodroid.rpc.RpcCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.RpcException
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.decodeOffMain
import pt.tiago.zerodroid.rpc.proto.AgentEntry
import pt.tiago.zerodroid.rpc.proto.AgentsListResult
import pt.tiago.zerodroid.rpc.proto.FsListDirParams
import pt.tiago.zerodroid.rpc.proto.FsListDirResult
import pt.tiago.zerodroid.rpc.proto.SkillBundleEntry
import pt.tiago.zerodroid.rpc.proto.SkillFrontmatter
import pt.tiago.zerodroid.rpc.proto.SkillListEntry
import pt.tiago.zerodroid.rpc.proto.SkillsBundlesResult
import pt.tiago.zerodroid.rpc.proto.SkillsDeleteParams
import pt.tiago.zerodroid.rpc.proto.SkillsDeleteResult
import pt.tiago.zerodroid.rpc.proto.SkillsListParams
import pt.tiago.zerodroid.rpc.proto.SkillsListResult
import pt.tiago.zerodroid.rpc.proto.SkillsReadParams
import pt.tiago.zerodroid.rpc.proto.SkillsReadResult
import pt.tiago.zerodroid.rpc.proto.SkillsWriteParams
import pt.tiago.zerodroid.rpc.proto.SkillsWriteResult
import pt.tiago.zerodroid.rpc.request
import pt.tiago.zerodroid.rpc.userMessage

sealed interface SkillResult<out T> {
    data class Ok<T>(val value: T) : SkillResult<T>
    data object Forbidden : SkillResult<Nothing>
    /** The call failed in transport: it may still have landed on the daemon. */
    data class Lost(val message: String) : SkillResult<Nothing>
    data class Error(val message: String) : SkillResult<Nothing>
}

/** One skill in the per-agent list: a bundle skill (editable via the skills RPC) or a workspace dir (read-only). */
data class AgentSkill(
    val name: String,
    val description: String,
    /** Bundle alias for bundle skills; null for workspace skills. */
    val bundle: String?,
    val origin: String,
    val directory: String = "",
    val frontmatter: SkillFrontmatter? = null,
) {
    val editable: Boolean get() = bundle != null
}

/**
 * The daemon's skills RPC methods (skills/…), plus the per-agent view composed from
 * `agents/list`, the `agents.<alias>.skill_bundles` config list and — best effort — the
 * agent workspace's `skills/` directory via `fs/list_dir`.
 */
class SkillsRepository(private val rpc: RpcCaller, private val config: ConfigRepository) {

    suspend fun bundles(): SkillResult<List<SkillBundleEntry>> = call("skills/bundles", null) { decode<SkillsBundlesResult>(it).bundles }

    suspend fun list(bundle: String? = null): SkillResult<List<SkillListEntry>> =
        // The daemon parses params as a struct even when every field is optional: send `{}`, never null.
        call("skills/list", encode(SkillsListParams(bundle))) { decode<SkillsListResult>(it).skills }

    suspend fun read(bundle: String, name: String): SkillResult<SkillsReadResult> =
        call("skills/read", buildJsonObject { put("bundle", bundle); put("name", name) }) { decode(it) }

    suspend fun write(bundle: String, name: String, frontmatter: SkillFrontmatter, body: String): SkillResult<SkillsWriteResult> =
        call("skills/write", encode(SkillsWriteParams(bundle, name, frontmatter, body))) { decode(it) }

    suspend fun delete(bundle: String, name: String): SkillResult<SkillsDeleteResult> =
        call("skills/delete", buildJsonObject { put("bundle", bundle); put("name", name) }) { decode(it) }

    /** Agents sorted by alias, for the agent picker. */
    suspend fun agents(): SkillResult<List<AgentEntry>> =
        call("agents/list", null) { decode<AgentsListResult>(it).agents.sortedBy { a -> a.alias } }

    /** The bundle aliases `[agents.<alias>].skill_bundles` assigns to this agent. */
    suspend fun agentSkillBundles(alias: String): SkillResult<List<String>> = try {
        val fields = config.fields("agents.$alias")
        // Match on the last path segment (kebab/snake tolerant): "agents.x.skill_bundles" / "skill-bundles".
        // config/list reports values as display strings, so parse via arrayItems (handles both shapes).
        val field = fields.firstOrNull {
            it.path.startsWith("agents.$alias.") && it.path.substringAfterLast('.').replace('-', '_') == "skill_bundles"
        }
        SkillResult.Ok(field?.let { pt.tiago.zerodroid.data.config.arrayItems(it) } ?: emptyList())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ConfigForbiddenException) {
        SkillResult.Forbidden
    } catch (e: Exception) {
        failure(e)
    }

    /**
     * The agent's workspace `skills/` directory, or null when it can't be derived: the
     * `[agents.<alias>.workspace.path]` override when set, otherwise `<install>/agents/<alias>/workspace/skills`
     * where the install root is recovered from a bundle's default `shared/skills/<alias>` directory.
     */
    suspend fun agentWorkspaceSkillsDir(alias: String): String? {
        val fields = runCatching { config.fields("agents.$alias.workspace") }.getOrNull() ?: return null
        val override = fields.firstOrNull {
            it.path.startsWith("agents.$alias.workspace.") && it.path.substringAfterLast('.').replace('-', '_') == "path"
        }?.takeIf { it.populated }?.value?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (override != null) return override.trimEnd('/', '\\') + "/skills"
        val root = when (val b = bundles()) {
            is SkillResult.Ok -> b.value.firstNotNullOfOrNull { bundle ->
                val dir = bundle.directory.replace('\\', '/')
                val suffix = "/shared/skills/${bundle.alias}"
                if (dir.endsWith(suffix)) dir.removeSuffix(suffix).takeIf { it.isNotEmpty() } else null
            }
            else -> null
        } ?: return null
        return "$root/agents/$alias/workspace/skills"
    }

    /**
     * Workspace skill names for the agent (directories holding a SKILL.md). Best effort:
     * an unlistable workspace (permissions, missing dir) yields an empty list, not an error.
     */
    suspend fun workspaceSkills(alias: String): List<String> {
        val dir = agentWorkspaceSkillsDir(alias) ?: return emptyList()
        val entries = runCatching { listDir(dir) }.getOrNull()?.entries.orEmpty().filter { it.isDir && !it.isHidden }
        return entries.mapNotNull { e ->
            val child = e.fullPath.ifEmpty { "$dir/${e.name}" }
            val hasManifest = runCatching { listDir(child).entries.any { !it.isDir && it.name == SKILL_MANIFEST } }.getOrDefault(false)
            e.name.takeIf { hasManifest }
        }
    }

    private suspend fun listDir(path: String): FsListDirResult = rpc.request("fs/list_dir", FsListDirParams(path))

    private suspend inline fun <reified P> encode(p: P): JsonElement = ZdJson.encodeToJsonElement(serializer<P>(), p)

    private suspend inline fun <reified R> decode(json: JsonElement): R = decodeOffMain(serializer<R>() as KSerializer<R>, json)

    private suspend fun <T> call(method: String, params: JsonElement?, map: suspend (JsonElement) -> T): SkillResult<T> = try {
        SkillResult.Ok(map(rpc.call(method, params, 30_000)))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(e)
    }

    private fun failure(e: Exception): SkillResult<Nothing> = when (e) {
        is RpcException -> when (e.error.code) {
            RpcCodes.FORBIDDEN, RpcCodes.AUTH_REQUIRED -> SkillResult.Forbidden
            else -> SkillResult.Error(e.userMessage())
        }
        is java.util.concurrent.TimeoutException, is NotConnectedException, is java.io.IOException -> SkillResult.Lost(e.userMessage())
        else -> SkillResult.Error(e.userMessage())
    }

    companion object {
        const val SKILL_MANIFEST = "SKILL.md"
    }
}
