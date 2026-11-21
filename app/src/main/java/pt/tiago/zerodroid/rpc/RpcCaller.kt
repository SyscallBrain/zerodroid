package pt.tiago.zerodroid.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/** The one operation the data layer needs from a connection. Faked in tests. */
fun interface RpcCaller {
    /** [timeoutMs] `<= 0` waits without a deadline. */
    suspend fun call(method: String, params: JsonElement?, timeoutMs: Long): JsonElement
}

suspend inline fun <reified P> RpcCaller.callWith(method: String, params: P, timeoutMs: Long = 30_000): JsonElement =
    call(method, ZdJson.encodeToJsonElement(serializer<P>(), params), timeoutMs)

suspend inline fun <reified R> RpcCaller.callAs(method: String, params: JsonElement? = null, timeoutMs: Long = 30_000): R =
    decodeOffMain(serializer<R>(), call(method, params, timeoutMs))

suspend inline fun <reified P, reified R> RpcCaller.request(method: String, params: P, timeoutMs: Long = 30_000): R =
    decodeOffMain(serializer<R>(), callWith(method, params, timeoutMs))

/**
 * Where typed results are decoded. The app sets [kotlinx.coroutines.Dispatchers.Default] at start so
 * large results (config field lists) never decode on the main thread; the default (no switch) keeps
 * unit tests on their test dispatcher.
 */
object RpcDecode {
    @Volatile var context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
}

suspend fun <R> decodeOffMain(ser: kotlinx.serialization.KSerializer<R>, json: JsonElement): R =
    kotlinx.coroutines.withContext(RpcDecode.context) { ZdJson.decodeFromJsonElement(ser, json) }

/** Adapts an [RpcClient] supplier; throws [NotConnectedException] when there is no client. */
class ClientCaller(private val client: () -> RpcClient?) : RpcCaller {
    override suspend fun call(method: String, params: JsonElement?, timeoutMs: Long): JsonElement {
        val c = client() ?: throw NotConnectedException()
        return c.call(method, params, JsonElement.serializer(), JsonElement.serializer(), timeoutMs)
    }
}

/** A short, user-facing message for a failed call. */
fun Throwable.userMessage(): String = when (this) {
    is NotConnectedException -> "Not connected to the daemon"
    is java.util.concurrent.TimeoutException -> message ?: "The daemon did not answer in time"
    is RpcException -> when (error.code) {
        RpcCodes.SESSION_BUSY -> "This session is busy with another turn"
        RpcCodes.SESSION_NOT_OWNED -> "This session belongs to another client"
        RpcCodes.SESSION_NOT_FOUND -> "This session no longer exists on the daemon"
        RpcCodes.SESSION_LIMIT_REACHED -> "The daemon has reached its session limit"
        RpcCodes.FORBIDDEN -> "This device is not allowed to do that"
        else -> error.message
    }
    else -> message ?: javaClass.simpleName
}
