package pt.tiago.zerodroid.data.cron

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import pt.tiago.zerodroid.data.config.ApplyResult
import pt.tiago.zerodroid.data.config.ConfigRepository
import pt.tiago.zerodroid.rpc.NotConnectedException
import pt.tiago.zerodroid.rpc.RpcCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.RpcException
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.decodeOffMain
import pt.tiago.zerodroid.rpc.proto.AgentEntry
import pt.tiago.zerodroid.rpc.proto.AgentsListResult
import pt.tiago.zerodroid.rpc.proto.ConfigSetEntry
import pt.tiago.zerodroid.rpc.proto.CronAddParams
import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.rpc.proto.CronListResult
import pt.tiago.zerodroid.rpc.proto.CronPatchParams
import pt.tiago.zerodroid.rpc.proto.CronRun
import pt.tiago.zerodroid.rpc.proto.CronRunsResult
import pt.tiago.zerodroid.rpc.proto.CronTriggerResult
import pt.tiago.zerodroid.rpc.proto.SchedulerSettings
import pt.tiago.zerodroid.rpc.userMessage

sealed interface CronResult<out T> {
    data class Ok<T>(val value: T) : CronResult<T>
    data object Forbidden : CronResult<Nothing>
    /** The call failed in transport: it may still have landed on the daemon. */
    data class Lost(val message: String) : CronResult<Nothing>
    data class Error(val message: String) : CronResult<Nothing>
}

/** The daemon's cron RPC methods, plus the scheduler settings it keeps in config. */
class CronRepository(private val rpc: RpcCaller, private val config: ConfigRepository) {
    suspend fun list(): CronResult<List<CronJob>> = call("cron/list", null) { decode<CronListResult>(it).jobs }

    suspend fun get(id: String): CronResult<CronJob> = call("cron/get", idParams(id)) { decode(it) }

    suspend fun add(p: CronAddParams): CronResult<CronJob> = call("cron/add", encode(p)) { decode(it) }

    suspend fun patch(p: CronPatchParams): CronResult<CronJob> = call("cron/patch", encode(p)) { decode(it) }

    suspend fun delete(id: String): CronResult<Unit> = call("cron/delete", idParams(id)) { }

    suspend fun runs(id: String, limit: Int = 20): CronResult<List<CronRun>> =
        call("cron/runs", buildJsonObject { put("id", id); put("limit", limit) }) { decode<CronRunsResult>(it).runs }

    /** Blocks until the job ends on the daemon; agent jobs can take minutes. */
    suspend fun trigger(id: String): CronResult<CronTriggerResult> =
        call("cron/trigger", idParams(id), timeoutMs = TRIGGER_TIMEOUT_MS) { decode(it) }

    suspend fun settings(): CronResult<SchedulerSettings> = call("cron/settings", null) { decode(it) }

    /** `cron/settings` can't write: the settings are `scheduler.*` config keys, written with `config/set-many`. */
    suspend fun setSettings(s: SchedulerSettings): CronResult<Unit> {
        val paths = try {
            config.fields("scheduler").map { it.path }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(e)
        }
        fun path(name: String) = paths.firstOrNull { it.substringAfterLast('.').replace('-', '_') == name } ?: "scheduler.$name"
        val sets = listOf(
            ConfigSetEntry(path("enabled"), JsonPrimitive(s.enabled)),
            ConfigSetEntry(path("catch_up_on_startup"), JsonPrimitive(s.catchUpOnStartup)),
            ConfigSetEntry(path("max_run_history"), JsonPrimitive(s.maxRunHistory)),
        )
        return when (val r = config.apply(sets)) {
            is ApplyResult.Ok -> CronResult.Ok(Unit)
            ApplyResult.Forbidden -> CronResult.Forbidden
            is ApplyResult.Rejected -> CronResult.Error(r.reason)
            is ApplyResult.Failed -> CronResult.Error(r.message)
        }
    }

    /** Agents sorted by alias, with the channels each is bound to (delivery targets). */
    suspend fun agents(): CronResult<List<AgentEntry>> =
        call("agents/list", null) { decode<AgentsListResult>(it).agents.sortedBy { a -> a.alias } }

    private fun idParams(id: String) = buildJsonObject { put("id", id) }

    private inline fun <reified P> encode(p: P): JsonElement = ZdJson.encodeToJsonElement(serializer<P>(), p)

    private suspend inline fun <reified R> decode(json: JsonElement): R = decodeOffMain(serializer<R>() as KSerializer<R>, json)

    private suspend fun <T> call(
        method: String,
        params: JsonElement?,
        timeoutMs: Long = 30_000,
        map: suspend (JsonElement) -> T,
    ): CronResult<T> = try {
        CronResult.Ok(map(rpc.call(method, params, timeoutMs)))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(e)
    }

    private fun failure(e: Exception): CronResult<Nothing> = when (e) {
        is RpcException -> when (e.error.code) {
            RpcCodes.FORBIDDEN, RpcCodes.AUTH_REQUIRED -> CronResult.Forbidden
            else -> CronResult.Error(e.userMessage())
        }
        is java.util.concurrent.TimeoutException, is NotConnectedException, is java.io.IOException -> CronResult.Lost(e.userMessage())
        else -> CronResult.Error(e.userMessage())
    }

    companion object {
        const val TRIGGER_TIMEOUT_MS = 600_000L
    }
}
