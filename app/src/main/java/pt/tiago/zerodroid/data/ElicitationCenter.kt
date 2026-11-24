package pt.tiago.zerodroid.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import pt.tiago.zerodroid.rpc.Inbound
import pt.tiago.zerodroid.rpc.NotConnectedException
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.proto.ElicitationOption
import pt.tiago.zerodroid.rpc.proto.ElicitationRequestParams
import pt.tiago.zerodroid.rpc.proto.ElicitationResult
import pt.tiago.zerodroid.rpc.proto.parseElicitationForm

/** Sends answers to requests the daemon sent us. Faked in tests. */
interface ElicitationTransport {
    fun respond(id: JsonElement, result: JsonElement): Boolean
    fun respondError(id: JsonElement, code: Int, message: String): Boolean
}

enum class QuestionStatus { Pending, Answered, Declined, Cancelled, Expired, ConnectionLost }

/**
 * A question the daemon sent via `elicitation/create` (what `ask_user` with choices uses on
 * the RPC channel). [id] is the daemon's request id and is echoed verbatim in the response;
 * [requestId] is its string form, used as the UI key.
 */
data class PendingQuestion(
    val requestId: String,
    val id: JsonElement,
    val sessionId: String,
    val message: String,
    /** "choice" for single-select, "choices" for multi-select. */
    val property: String,
    val multi: Boolean,
    val options: List<ElicitationOption>,
    val deadlineAt: Long,
    val status: QuestionStatus,
    /** Titles of the chosen options, once answered. */
    val answer: List<String> = emptyList(),
    val answeredAt: Long? = null,
)

data class QuestionResolution(val sessionId: String, val requestId: String, val status: QuestionStatus, val answer: List<String>)

class ElicitationAnsweredException : IllegalStateException("Already answered")
class ElicitationExpiredException : IllegalStateException("Expired, cancelled locally")

/**
 * Every `elicitation/create` the daemon sent to this phone. Questions are answered only by
 * an explicit [answer]/[decline]/[cancel]; expiry and connection loss resolve them locally.
 */
class ElicitationCenter(
    private val transport: ElicitationTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** Local countdown; the daemon applies its own (ask_user's timeout_secs, default 300 s). */
        const val DEFAULT_TIMEOUT_SECS = 300L

        /** Drops every answer; used where no connection exists (tests, pre-pairing). */
        val NoopTransport = object : ElicitationTransport {
            override fun respond(id: JsonElement, result: JsonElement) = false
            override fun respondError(id: JsonElement, code: Int, message: String) = false
        }
    }

    private val _pending = MutableStateFlow<List<PendingQuestion>>(emptyList())
    val pending: StateFlow<List<PendingQuestion>> = _pending.asStateFlow()
    private val _count = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _count.asStateFlow()
    private val _resolutions = MutableSharedFlow<QuestionResolution>(extraBufferCapacity = 64)
    val resolutions: SharedFlow<QuestionResolution> = _resolutions.asSharedFlow()
    private val _recent = MutableStateFlow<List<PendingQuestion>>(emptyList())
    /** Newest first; for screens that show questions outside a chat. */
    val recent: StateFlow<List<PendingQuestion>> = _recent.asStateFlow()
    private val lock = Mutex()

    /**
     * Registers a daemon→client request. Returns the question to show, or null when the frame
     * was not a usable form elicitation — in that case an error response was already sent so
     * the daemon fails fast instead of waiting out its timeout.
     */
    fun onRequest(req: Inbound.Request): PendingQuestion? {
        val params = runCatching { ZdJson.decodeFromJsonElement(ElicitationRequestParams.serializer(), req.params) }.getOrNull()
        if (params == null) {
            transport.respondError(req.id, RpcCodes.INVALID_PARAMS, "Malformed elicitation/create params")
            return null
        }
        if (params.mode != "form") {
            transport.respondError(req.id, RpcCodes.INVALID_REQUEST, "Unsupported elicitation mode: ${params.mode}")
            return null
        }
        val form = parseElicitationForm(params.requestedSchema)
        if (form == null || form.options.isEmpty()) {
            transport.respondError(req.id, RpcCodes.INVALID_PARAMS, "elicitation/create without form options")
            return null
        }
        val q = PendingQuestion(
            requestId = (req.id as? JsonPrimitive)?.content ?: req.id.toString(),
            id = req.id,
            sessionId = params.sessionId,
            message = params.message,
            property = form.property,
            multi = form.multi,
            options = form.options,
            deadlineAt = clock() + DEFAULT_TIMEOUT_SECS * 1_000L,
            status = QuestionStatus.Pending,
        )
        _pending.update { list -> list.filterNot { it.requestId == q.requestId } + q }
        _count.value = _pending.value.size
        return q
    }

    fun find(requestId: String): PendingQuestion? = _pending.value.firstOrNull { it.requestId == requestId }

    /** Accepts with the chosen option consts ([selectedConsts]); single-select takes exactly one. */
    suspend fun answer(requestId: String, selectedConsts: List<String>): Result<Unit> = lock.withLock {
        val q = _pending.value.firstOrNull { it.requestId == requestId }
            ?: return@withLock Result.failure(ElicitationAnsweredException())
        if (clock() >= q.deadlineAt) {
            resolve(q, QuestionStatus.Expired, emptyList())
            return@withLock Result.failure(ElicitationExpiredException())
        }
        val valid = selectedConsts.distinct().filter { c -> q.options.any { it.const == c } }
        if (valid.isEmpty() || (!q.multi && valid.size != 1)) {
            return@withLock Result.failure(IllegalArgumentException("Invalid choice"))
        }
        val result = if (q.multi) ElicitationResult.acceptMulti(q.property, valid) else ElicitationResult.acceptSingle(q.property, valid.single())
        if (!transport.respond(q.id, result)) return@withLock Result.failure(NotConnectedException())
        resolve(q, QuestionStatus.Answered, valid.mapNotNull { c -> q.options.firstOrNull { it.const == c }?.title })
        Result.success(Unit)
    }

    suspend fun decline(requestId: String): Result<Unit> = decide(requestId, ElicitationResult.decline(), QuestionStatus.Declined)

    suspend fun cancel(requestId: String): Result<Unit> = decide(requestId, ElicitationResult.cancel(), QuestionStatus.Cancelled)

    private suspend fun decide(requestId: String, result: JsonElement, status: QuestionStatus): Result<Unit> = lock.withLock {
        val q = _pending.value.firstOrNull { it.requestId == requestId }
            ?: return@withLock Result.failure(ElicitationAnsweredException())
        if (clock() >= q.deadlineAt) {
            resolve(q, QuestionStatus.Expired, emptyList())
            return@withLock Result.failure(ElicitationExpiredException())
        }
        if (!transport.respond(q.id, result)) return@withLock Result.failure(NotConnectedException())
        resolve(q, status, emptyList())
        Result.success(Unit)
    }

    private fun resolve(q: PendingQuestion, status: QuestionStatus, answer: List<String>) {
        val done = q.copy(status = status, answer = answer, answeredAt = clock())
        _pending.update { list -> list.filterNot { it.requestId == q.requestId } }
        _count.value = _pending.value.size
        _resolutions.tryEmit(QuestionResolution(q.sessionId, q.requestId, status, answer))
        _recent.update { list -> (listOf(done) + list.filterNot { it.requestId == q.requestId }).take(20) }
    }

    /** Resolves every pending question whose local countdown has run out (cancel sent best-effort). */
    fun expireDue() {
        val now = clock()
        _pending.value.filter { now >= it.deadlineAt }.forEach { q ->
            runCatching { transport.respond(q.id, ElicitationResult.cancel()) }
            resolve(q, QuestionStatus.Expired, emptyList())
        }
    }

    /** The daemon times out a disconnected client's questions itself; resolve them locally. */
    fun onConnectionLost() {
        _pending.value.forEach { resolve(it, QuestionStatus.ConnectionLost, emptyList()) }
    }

    fun clear() {
        _pending.value = emptyList()
        _recent.value = emptyList()
        _count.value = 0
    }
}
