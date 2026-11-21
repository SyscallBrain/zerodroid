package pt.tiago.zerodroid.ui.onboarding

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.DaemonProfile
import pt.tiago.zerodroid.enroll.EnrollClient
import pt.tiago.zerodroid.enroll.VerifiedCa
import pt.tiago.zerodroid.security.PairingPayload

sealed interface OnbState {
    data object Welcome : OnbState
    data class Input(val error: String? = null) : OnbState
    data object Verifying : OnbState
    data class Confirm(val name: String, val host: String, val sas: String) : OnbState
    data object Enrolling : OnbState
    data object Done : OnbState
    data class Failed(val message: String) : OnbState
}

/**
 * Pairing flow: link → CA fingerprint check → SAS confirmation → enrollment → persist.
 * Nothing is persisted before the user confirms the SAS, and only after enrollment succeeds.
 */
class OnboardingViewModel(
    private val enroll: EnrollClient,
    private val persist: suspend (DaemonProfile, String) -> Unit,
    private val activeProfile: () -> DaemonProfile? = { null },
    private val diagnose: (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    private val _state = MutableStateFlow<OnbState>(OnbState.Welcome)
    val state: StateFlow<OnbState> = _state.asStateFlow()

    private var pending: PairingPayload? = null
    private var verified: VerifiedCa? = null

    fun onLink(raw: String) {
        val s = _state.value
        if (s is OnbState.Verifying || s is OnbState.Enrolling || s is OnbState.Done) return
        val parsed = PairingPayload.parse(raw.trim()).getOrElse {
            _state.value = OnbState.Failed(friendly(it.message ?: "Invalid pairing link"))
            return
        }
        val active = activeProfile()
        if (active != null && active.host == parsed.host && active.wssPort == parsed.wssPort) {
            _state.value = OnbState.Failed("Already paired with ${active.name}. Unpair first to pair again.")
            return
        }
        pending = parsed
        verified = null
        _state.value = OnbState.Verifying
        scope.launch {
            val ca = enroll.fetchAndVerify(parsed).getOrElse {
                diagnose("verify failed: ${it.javaClass.simpleName}: ${it.message}")
                pending = null
                _state.value = OnbState.Failed(friendly(it.message ?: "Could not reach the daemon"))
                return@launch
            }
            verified = ca
            _state.value = OnbState.Confirm(parsed.name, parsed.host, ca.sas)
        }
    }

    fun onConfirm() {
        val p = pending ?: return
        val ca = verified ?: return
        if (_state.value !is OnbState.Confirm) return
        _state.value = OnbState.Enrolling
        scope.launch {
            val response = enroll.enroll(p, ca).getOrElse {
                diagnose("enroll failed: ${it.javaClass.simpleName}: ${it.message}")
                _state.value = OnbState.Failed(friendly(it.message ?: "Enrollment failed"))
                return@launch
            }
            val profile = DaemonProfile(
                id = response.deviceId,
                name = p.name,
                host = p.host,
                wssPort = p.wssPort,
                caPem = ca.caPem,
                certPem = response.certPem,
                deviceId = response.deviceId,
                issuedAt = nowSeconds(),
                notAfter = response.notAfter,
            )
            runCatching { persist(profile, p.token) }.onFailure {
                diagnose("persist failed: ${it.javaClass.simpleName}: ${it.message}")
                _state.value = OnbState.Failed("Could not save the pairing on this phone. Try again.")
                return@launch
            }
            pending = null
            verified = null
            _state.value = OnbState.Done
        }
    }

    /** A failure outside the link itself, e.g. the QR scanner could not start. */
    fun onError(message: String) {
        val s = _state.value
        if (s is OnbState.Verifying || s is OnbState.Enrolling) return
        _state.value = OnbState.Failed(message)
    }

    fun onCancel() {
        pending = null
        verified = null
        _state.value = OnbState.Welcome
    }

    private fun friendly(raw: String): String {
        val m = raw.trim()
        val lower = m.lowercase()
        return when {
            "failed to connect" in lower || "connection refused" in lower || "unable to resolve" in lower ->
                "Could not reach the daemon's enrollment port. Is ZeroClaw running with [enroll] enabled?"
            "timeout" in lower || "timed out" in lower -> "The daemon did not answer in time. Check the connection and try again."
            else -> m.replaceFirstChar { it.uppercase() }.let { if (it.endsWith('.')) it else "$it." }
        }
    }
}
