package pt.tiago.zerodroid.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer
import pt.tiago.zerodroid.rpc.proto.InitializeParams
import pt.tiago.zerodroid.rpc.proto.InitializeResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RpcException(val error: RpcError) : Exception(error.message)
class NotConnectedException : Exception("Not connected to the daemon")

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val attempt: Int) : ConnState
    data class Ready(val info: InitializeResult) : ConnState
    data class Backoff(val retryInMs: Long, val cause: String) : ConnState
    data class AuthFailed(val message: String) : ConnState
}

/** Identity presented on `initialize` so the daemon keeps this client's session ownership. */
data class ClientIdentity(val tuiId: String, val tuiSig: String) {
    override fun toString() = "ClientIdentity(tuiId=$tuiId, tuiSig=***)"
}

/** Queues frames until the real socket handle exists (transports may call onOpen inside open()). */
private class BufferedHandle : WsHandle {
    private val delegate = AtomicReference<WsHandle?>(null)
    private val queued = mutableListOf<String>()

    fun attach(real: WsHandle) = synchronized(queued) {
        delegate.set(real)
        queued.forEach { real.send(it) }
        queued.clear()
    }

    override fun send(text: String): Boolean = synchronized(queued) {
        val real = delegate.get() ?: run { queued.add(text); return true }
        real.send(text)
    }

    override fun close(code: Int, reason: String) {
        delegate.get()?.close(code, reason)
    }
}

/**
 * One JSON-RPC 2.0 connection over a WebSocket, with `initialize`, request/response
 * correlation, notifications and reconnect with backoff.
 *
 * Every socket gets a generation number; callbacks from an older socket are ignored,
 * so a late `onClosed` from a replaced connection cannot tear down the current one.
 */
open class RpcClient(
    private val transport: WsTransport,
    private val url: String,
    private val token: () -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val identity: () -> ClientIdentity? = { null },
    private val onIdentity: (ClientIdentity) -> Unit = {},
    private val backoff: Backoff = Backoff(),
    private val authProvider: () -> String? = { null },
    /** Fires with the daemon's message whenever any response carries AUTH_REQUIRED, including `initialize`. */
    /** `atInitialize` tells a refused handshake apart from a refused request on an open connection. */
    private val onAuthRequired: (message: String, atInitialize: Boolean) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<Inbound.Notification>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications: SharedFlow<Inbound.Notification> = _notifications.asSharedFlow()

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val nextId = AtomicLong(1)
    private val generation = AtomicLong(0)
    @Volatile private var handle: WsHandle? = null
    @Volatile private var initId: Long = -1
    /** Ids of `initialize` requests sent on an already open socket (token renewal, revalidation). */
    private val reinitIds = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var running = false
    @Volatile private var failures = 0
    private var retryJob: Job? = null

    fun start() {
        if (running) return
        running = true
        failures = 0
        connect()
    }

    fun stop() {
        running = false
        retryJob?.cancel()
        generation.incrementAndGet()
        handle?.close(1000, "client stopped")
        handle = null
        failPending()
        _state.value = ConnState.Idle
    }

    /** Debug diagnostics: drop the socket as if the network failed. */
    fun simulateDrop() {
        val gen = generation.get()
        handle?.close(1001, "dropped by diagnostics")
        disconnected(gen, "Connection dropped (diagnostics)")
    }

    /** Skip the remaining backoff delay and reconnect now. */
    fun retryNow() {
        if (!running || _state.value !is ConnState.Backoff) return
        retryJob?.cancel()
        connect()
    }

    private fun failPending() {
        val all = pending.values.toList()
        pending.clear()
        all.forEach { it.completeExceptionally(NotConnectedException()) }
    }

    @Volatile private var connectStartNs = 0L

    private fun connect() {
        if (!running) return
        connectStartNs = System.nanoTime()
        val gen = generation.incrementAndGet()
        _state.value = ConnState.Connecting(failures + 1)
        val buffered = BufferedHandle()
        handle = buffered
        val real = transport.open(url, object : WsListener {
            override fun onOpen() {
                if (gen != generation.get()) return
                sendInitialize(buffered)
            }

            override fun onText(text: String) {
                if (gen != generation.get()) return
                text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { onFrame(gen, it) }
            }

            override fun onClosed(code: Int, reason: String) =
                disconnected(gen, "Connection closed${if (reason.isNotBlank()) ": $reason" else ""}")

            override fun onFailure(t: Throwable) = disconnected(gen, t.message ?: t.javaClass.simpleName)
        })
        buffered.attach(real)
    }

    private fun sendInitialize(h: WsHandle) {
        val id = nextId.getAndIncrement()
        initId = id
        val ident = identity()
        val params = InitializeParams(authToken = token().orEmpty(), tuiId = ident?.tuiId, tuiSig = ident?.tuiSig, authProvider = authProvider())
        h.send(encodeRequest(id, "initialize", ZdJson.encodeToJsonElement(InitializeParams.serializer(), params)))
    }

    private fun onFrame(gen: Long, line: String) {
        when (val inbound = decodeInbound(line) ?: return) {
            is Inbound.Notification -> _notifications.tryEmit(inbound)
            is Inbound.Response -> if (inbound.id == initId) {
                val info = runCatching {
                    ZdJson.decodeFromJsonElement(InitializeResult.serializer(), inbound.result)
                }.getOrNull()
                if (info == null) {
                    handle?.close(1002, "bad initialize result")
                    disconnected(gen, "The daemon sent an unreadable initialize result")
                    return
                }
                if (info.tuiId != null && info.tuiSig != null) onIdentity(ClientIdentity(info.tuiId, info.tuiSig))
                failures = 0
                backoff.reset()
                pt.tiago.zerodroid.Perf.record("rpc.handshake(open→ready)", (System.nanoTime() - connectStartNs) / 1e6)
                _state.value = ConnState.Ready(info)
            } else {
                pending.remove(inbound.id)?.complete(inbound.result)
            }
            is Inbound.Failure -> {
                val atInitialize = inbound.id == initId || inbound.id in reinitIds
                // Settle the request first: the callback may stop this client.
                onFailure(gen, inbound)
                if (inbound.error.code == RpcCodes.AUTH_REQUIRED) onAuthRequired(inbound.error.message, atInitialize)
            }
        }
    }

    private fun onFailure(gen: Long, inbound: Inbound.Failure) {
        when {
            inbound.id == initId -> {
                val code = inbound.error.code
                if (code == RpcCodes.AUTH_REQUIRED || code == RpcCodes.FORBIDDEN || code == RpcCodes.VERSION_MISMATCH) {
                    running = false
                    generation.incrementAndGet()
                    handle?.close(1000, "auth failed")
                    failPending()
                    _state.value = ConnState.AuthFailed(inbound.error.message)
                } else {
                    handle?.close(1000, "initialize failed")
                    disconnected(gen, inbound.error.message)
                }
            }
            else -> inbound.id?.let { pending.remove(it) }?.completeExceptionally(RpcException(inbound.error))
        }
    }

    private fun disconnected(gen: Long, cause: String) {
        if (!generation.compareAndSet(gen, gen + 1)) return
        handle = null
        failPending()
        if (!running) return
        failures++
        val retry = backoff.next(failures).coerceAtLeast(MIN_RETRY_MS)
        _state.value = ConnState.Backoff(retry, cause.take(200))
        retryJob = scope.launch {
            delay(retry)
            connect()
        }
    }

    /**
     * Sends a request and waits for its result. [timeoutMs] `<= 0` waits without a
     * deadline (used by `session/prompt`, which answers only when the turn ends).
     */
    open suspend fun <P, R> call(
        method: String,
        params: P?,
        pSer: KSerializer<P>,
        rSer: KSerializer<R>,
        timeoutMs: Long = 30_000,
    ): R {
        if (_state.value !is ConnState.Ready) throw NotConnectedException()
        val h = handle ?: throw NotConnectedException()
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val t0 = System.nanoTime()
        try {
            val p = params?.let { ZdJson.encodeToJsonElement(pSer, it) }
            if (!h.send(encodeRequest(id, method, p))) throw NotConnectedException()
            val result = if (timeoutMs > 0) {
                try {
                    withTimeout(timeoutMs) { deferred.await() }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    throw TimeoutException("The daemon did not answer $method in time")
                }
            } else {
                deferred.await()
            }
            val t1 = System.nanoTime()
            val decoded = ZdJson.decodeFromJsonElement(rSer, result)
            pt.tiago.zerodroid.Perf.record("rpc.$method", (t1 - t0) / 1e6)
            pt.tiago.zerodroid.Perf.record("decode.$method", (System.nanoTime() - t1) / 1e6)
            return decoded
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Sends `initialize` again on the open socket with the current [token]. The daemon re-verifies
     * the credential and keeps the connection, so running turns are not cancelled (closing the socket
     * cancels them). [state] is left as is: nothing needs reloading.
     */
    suspend fun reinitialize(timeoutMs: Long = 20_000): InitializeResult {
        if (_state.value !is ConnState.Ready) throw NotConnectedException()
        val h = handle ?: throw NotConnectedException()
        val id = nextId.getAndIncrement()
        val ident = identity()
        val params = InitializeParams(authToken = token().orEmpty(), tuiId = ident?.tuiId, tuiSig = ident?.tuiSig, authProvider = authProvider())
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        reinitIds.add(id)
        try {
            if (!h.send(encodeRequest(id, "initialize", ZdJson.encodeToJsonElement(InitializeParams.serializer(), params)))) {
                throw NotConnectedException()
            }
            val result = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw TimeoutException("The daemon did not answer initialize in time")
            }
            val info = ZdJson.decodeFromJsonElement(InitializeResult.serializer(), result)
            if (info.tuiId != null && info.tuiSig != null) onIdentity(ClientIdentity(info.tuiId, info.tuiSig))
            return info
        } finally {
            pending.remove(id)
            reinitIds.remove(id)
        }
    }

    companion object {
        private const val MIN_RETRY_MS = 750L
    }
}

/** Calls a method with no params and returns the raw JSON result. */
suspend fun RpcClient.callJson(method: String, params: JsonElement? = null, timeoutMs: Long = 30_000): JsonElement =
    call(method, params, JsonElement.serializer(), JsonElement.serializer(), timeoutMs)

/** Calls a method with typed params and returns the raw JSON result. */
suspend inline fun <reified P> RpcClient.callWith(method: String, params: P, timeoutMs: Long = 30_000): JsonElement =
    call(method, params, serializer<P>(), JsonElement.serializer(), timeoutMs)

/** Calls a method and decodes the result leniently into [R]. */
suspend inline fun <reified P, reified R> RpcClient.request(method: String, params: P?, timeoutMs: Long = 30_000): R =
    call(method, params, serializer<P>(), serializer<R>(), timeoutMs)
