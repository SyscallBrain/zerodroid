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
import pt.tiago.zerodroid.rpc.RpcCaller
import pt.tiago.zerodroid.rpc.callWith
import pt.tiago.zerodroid.rpc.proto.ApprovalDecision
import pt.tiago.zerodroid.rpc.proto.SessionApproveParams
import pt.tiago.zerodroid.rpc.proto.SessionUpdate

data class PendingApproval(
    val sessionId: String,
    val agentAlias: String,
    val requestId: String,
    val toolName: String,
    val summary: String,
    val deadlineAt: Long,
    val status: ApprovalStatus,
    val answeredAt: Long? = null,
)

data class ApprovalResolution(val sessionId: String, val requestId: String, val status: ApprovalStatus)

class ApprovalExpiredException : IllegalStateException("Expired, denied by the daemon")
class ApprovalAnsweredException : IllegalStateException("Already answered")

/**
 * Every tool approval the daemon sent to this phone, across sessions. Approvals are
 * answered only by an explicit [decide]; expiry and connection loss resolve them
 * locally without sending anything.
 */
class ApprovalCenter(
    private val rpc: RpcCaller,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _pending = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pending: StateFlow<List<PendingApproval>> = _pending.asStateFlow()
    private val _recent = MutableStateFlow<List<PendingApproval>>(emptyList())
    val recent: StateFlow<List<PendingApproval>> = _recent.asStateFlow()
    private val _count = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _count.asStateFlow()
    private val _resolutions = MutableSharedFlow<ApprovalResolution>(extraBufferCapacity = 64)
    val resolutions: SharedFlow<ApprovalResolution> = _resolutions.asSharedFlow()
    private val lock = Mutex()

    fun onEvent(sessionId: String, agentAlias: String, e: SessionUpdate.ApprovalRequest) {
        val a = PendingApproval(
            sessionId, agentAlias, e.requestId, e.toolName, e.argumentsSummary,
            clock() + e.timeoutSecs * 1_000L, ApprovalStatus.Pending,
        )
        _pending.update { list -> list.filterNot { it.requestId == a.requestId } + a }
        _count.value = _pending.value.size
    }

    private fun resolve(a: PendingApproval, status: ApprovalStatus) {
        val done = a.copy(status = status, answeredAt = clock())
        _pending.update { list -> list.filterNot { it.requestId == a.requestId } }
        _recent.update { list -> (listOf(done) + list.filterNot { it.requestId == a.requestId }).take(20) }
        _count.value = _pending.value.size
        _resolutions.tryEmit(ApprovalResolution(a.sessionId, a.requestId, status))
    }

    fun find(requestId: String): PendingApproval? =
        _pending.value.firstOrNull { it.requestId == requestId } ?: _recent.value.firstOrNull { it.requestId == requestId }

    suspend fun decide(requestId: String, decision: String): Result<Unit> = lock.withLock {
        val a = _pending.value.firstOrNull { it.requestId == requestId }
            ?: return@withLock Result.failure(ApprovalAnsweredException())
        if (clock() >= a.deadlineAt) {
            resolve(a, ApprovalStatus.Expired)
            return@withLock Result.failure(ApprovalExpiredException())
        }
        val status = when (decision) {
            ApprovalDecision.APPROVE -> ApprovalStatus.Approved
            ApprovalDecision.ALWAYS -> ApprovalStatus.AlwaysApproved
            ApprovalDecision.DENY -> ApprovalStatus.Denied
            else -> return@withLock Result.failure(IllegalArgumentException("Unknown decision"))
        }
        runCatching {
            rpc.callWith("session/approve", SessionApproveParams(a.sessionId, a.requestId, decision))
            resolve(a, status)
        }
    }

    /** Resolves every pending approval whose countdown has run out. */
    fun expireDue() {
        val now = clock()
        _pending.value.filter { now >= it.deadlineAt }.forEach { resolve(it, ApprovalStatus.Expired) }
    }

    /** The daemon auto-denies a disconnected client's approvals ("client went away"). */
    fun onConnectionLost() {
        _pending.value.forEach { resolve(it, ApprovalStatus.ConnectionLost) }
    }

    fun clear() {
        _pending.value = emptyList()
        _recent.value = emptyList()
        _count.value = 0
    }
}
