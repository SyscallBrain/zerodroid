package pt.tiago.zerodroid.data.doctor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.rpc.RpcCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.RpcException
import pt.tiago.zerodroid.rpc.callAs
import pt.tiago.zerodroid.rpc.proto.DoctorRunResult
import pt.tiago.zerodroid.rpc.userMessage

/** The last Doctor run, kept for the daemon it ran on so Home can show it without re-running. */
data class DoctorState(
    val daemon: String? = null,
    val result: DoctorRunResult? = null,
    /** Epoch millis of [result]. */
    val at: Long? = null,
    val running: Boolean = false,
    val error: String? = null,
) {
    /** [result] only if it came from [daemon]; a run on another daemon says nothing about this one. */
    fun resultFor(daemon: String?): DoctorRunResult? = result?.takeIf { this.daemon == daemon }
}

/**
 * Runs `doctor/run` on demand. Never on a timer: the daemon probes every configured model,
 * which takes seconds and calls the providers.
 */
class DoctorStore(
    private val rpc: RpcCaller,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(DoctorState())
    val state: StateFlow<DoctorState> = _state.asStateFlow()

    /** Runs in the app scope: leaving the Doctor screen does not cancel a run half-way. */
    fun start(daemon: String?) {
        if (!_state.value.running) scope.launch { run(daemon) }
    }

    suspend fun run(daemon: String?) {
        var claimed = false
        _state.update { s -> claimed = !s.running; if (s.running) s else s.copy(running = true, error = null) }
        if (!claimed) return
        val outcome = runCatching { rpc.callAs<DoctorRunResult>("doctor/run", null, RUN_TIMEOUT_MS) }
        _state.update { s ->
            outcome.fold(
                onSuccess = { DoctorState(daemon = daemon, result = it, at = now()) },
                onFailure = { e -> s.copy(running = false, error = e.doctorMessage()) },
            )
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    private fun Throwable.doctorMessage(): String = when {
        this is RpcException && error.code == RpcCodes.METHOD_NOT_FOUND -> "This daemon has no Doctor over RPC. Update ZeroClaw."
        this is RpcException && error.code == RpcCodes.FORBIDDEN -> "This device may not run Doctor. Grant it system execute on the daemon."
        else -> userMessage()
    }

    companion object {
        /** The daemon gives the model probe 20 s; the rest of the checks are quick. */
        const val RUN_TIMEOUT_MS = 60_000L
    }
}
