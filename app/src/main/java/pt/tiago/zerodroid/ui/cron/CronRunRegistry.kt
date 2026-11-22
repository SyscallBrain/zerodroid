package pt.tiago.zerodroid.ui.cron

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import pt.tiago.zerodroid.data.cron.CronResult
import pt.tiago.zerodroid.rpc.proto.CronTriggerResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Manual runs, by job id, for the life of the process. A run outlives the screen that started it,
 * so a reopened job shows it still running (and can't start it twice), then its result.
 */
class CronRunRegistry {
    private val runs = ConcurrentHashMap<String, Deferred<CronResult<CronTriggerResult>>>()

    /** The run for [id]: the one in flight if there is one, else a new one started in [scope]. */
    @Synchronized
    fun start(id: String, scope: CoroutineScope, block: suspend () -> CronResult<CronTriggerResult>): Deferred<CronResult<CronTriggerResult>> {
        runs[id]?.takeIf { it.isActive }?.let { return it }
        return scope.async { block() }.also { runs[id] = it }
    }

    /** The latest run for [id], in flight or finished; null if none this session. */
    fun latest(id: String): Deferred<CronResult<CronTriggerResult>>? = runs[id]

    companion object {
        val shared = CronRunRegistry()
    }
}
