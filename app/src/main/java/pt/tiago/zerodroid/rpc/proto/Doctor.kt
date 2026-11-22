package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Source: DiagResult — crates/zeroclaw-runtime/src/doctor/mod.rs (severity is "ok" | "warn" | "error")
@Serializable
data class DiagResult(
    val severity: String = "ok",
    val category: String = "",
    val message: String = "",
)

// Source: DoctorSummary — crates/zeroclaw-rpc-proto/src/types.rs
@Serializable
data class DoctorSummary(
    val ok: Int = 0,
    val warnings: Int = 0,
    val errors: Int = 0,
)

// Source: DoctorRunResult — crates/zeroclaw-runtime/src/rpc/types.rs (`doctor/run`)
@Serializable
data class DoctorRunResult(
    val results: List<DiagResult> = emptyList(),
    val summary: DoctorSummary = DoctorSummary(),
    /** Where the daemon writes its log, when log persistence is on. */
    @SerialName("log_path") val logPath: String? = null,
    /** Set when a phase (the model probe) hit the daemon's deadline; its findings are missing. */
    @SerialName("timed_out_phase") val timedOutPhase: String? = null,
)
