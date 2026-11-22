package pt.tiago.zerodroid.ui.onboarding

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.DaemonProfile
import pt.tiago.zerodroid.enroll.EnrollHttpException
import pt.tiago.zerodroid.enroll.EnrollResponse
import pt.tiago.zerodroid.enroll.GatewayProbe
import pt.tiago.zerodroid.enroll.GatewaySpec
import pt.tiago.zerodroid.enroll.OidcDenied
import pt.tiago.zerodroid.enroll.OidcGrantExpired
import pt.tiago.zerodroid.enroll.OidcHttp
import pt.tiago.zerodroid.enroll.OidcLoginClient
import pt.tiago.zerodroid.enroll.OidcTarget
import pt.tiago.zerodroid.enroll.OidcTargetException
import pt.tiago.zerodroid.enroll.OidcToken
import pt.tiago.zerodroid.enroll.parseOidcTarget
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

sealed interface OidcState {
    data class Form(val fieldError: Pair<OidcTargetException.Field, String>? = null) : OidcState
    data object CheckingGateway : OidcState
    data class TrustPrompt(val host: String, val sha256: String) : OidcState
    data class Approve(val userCode: String, val verificationHost: String, val openUri: String, val expiresAt: Long) : OidcState
    data object Issuing : OidcState
    data object Connecting : OidcState
    data object Done : OidcState
    data class Failed(val message: String) : OidcState
}

sealed interface OidcMode {
    data object Onboard : OidcMode
    data class Renew(val profile: DaemonProfile) : OidcMode
}

private const val CERT_REUSE_MIN_S = 3600L

/** The identity provider's page may only be opened over https, or plain http on this phone's loopback. */
internal fun safeSignInLink(uri: String): Boolean {
    val u = runCatching { URI(uri) }.getOrNull() ?: return false
    val host = u.host?.lowercase() ?: return false
    return when (u.scheme?.lowercase()) {
        "https" -> true
        "http" -> host == "127.0.0.1" || host == "localhost" || host == "[::1]" || host == "::1"
        else -> false
    }
}

/** Operator hints for a WSS handshake that fails after the gateway accepted the sign-in (spec §5.4). */
internal fun wssFailureHint(cause: String, host: String): String = when {
    "Hostname" in cause || "not verified" in cause ->
        "The daemon's WSS certificate does not cover $host. Add it to [wss].sans on the daemon."
    "Trust anchor" in cause || "CertPath" in cause -> "The WSS certificate is not signed by the daemon CA."
    else -> cause
}

/**
 * OIDC sign-in (spec §5.2–5.6): gateway trust → device grant in the browser → certificate →
 * one probe `initialize` → persist. In [OidcMode.Renew] it only refreshes the token,
 * reusing the device certificate while it has more than an hour left.
 */
class OidcSignInViewModel(
    private val mode: OidcMode,
    private val login: OidcLoginClient,
    private val http: OidcHttp,
    private val probe: suspend (DaemonProfile, String) -> Result<Unit>,
    private val persist: suspend (DaemonProfile, String) -> Unit,
    private val renewed: suspend (OidcToken, EnrollResponse?, String?) -> Unit,
    private val activeProfile: () -> DaemonProfile?,
    private val deleteKey: () -> Unit,
    private val diagnose: (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {
    private val _state = MutableStateFlow<OidcState>(OidcState.Form())
    val state: StateFlow<OidcState> = _state.asStateFlow()

    private var target: OidcTarget? = null
    private var gateway: GatewaySpec? = null
    private var job: Job? = null
    @Volatile private var closed = false

    private val idle get() = _state.value.let { it is OidcState.Form || it is OidcState.Failed }

    fun submit(host: String, alias: String, wssUrl: String?, gatewayUrl: String?) {
        if (mode != OidcMode.Onboard || !idle) return
        activeProfile()?.let {
            _state.value = OidcState.Failed("Already paired with ${it.name}. Unpair first to sign in to another daemon.")
            return
        }
        val t = parseOidcTarget(host, alias, wssUrl, gatewayUrl).getOrElse { e ->
            val f = e as? OidcTargetException ?: throw e
            _state.value = OidcState.Form(f.field to (f.message ?: "Check this field."))
            return
        }
        target = t
        gateway = GatewaySpec(t.gatewayUrl, null)
        checkGateway()
    }

    fun renew() {
        val p = (mode as? OidcMode.Renew)?.profile ?: return
        if (!idle) return
        val alias = p.oidcAlias ?: p.authProvider.removePrefix("oidc.")
        target = OidcTarget(alias, p.authProvider, p.connectUrl(), p.host, p.wssPort, p.gatewayUrl ?: "https://${p.host}:42617")
        gateway = GatewaySpec(target!!.gatewayUrl, p.gatewayCertSha256)
        checkGateway()
    }

    fun trust() {
        val s = _state.value as? OidcState.TrustPrompt ?: return
        gateway = gateway?.copy(pinnedLeafSha256 = s.sha256)
        checkGateway()
    }

    /** Stops any grant in flight; called when the view model is cleared. */
    fun close() {
        closed = true
        job?.cancel()
    }

    override fun onCleared() = close()

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = OidcState.Form()
    }

    private fun checkGateway() {
        val t = target ?: return
        val gw = gateway ?: return
        _state.value = OidcState.CheckingGateway
        if (closed) return
        job = scope.launch {
            var keyUsed = false
            try {
                when (val p = http.probe(gw)) {
                    is GatewayProbe.Untrusted -> {
                        _state.value = OidcState.TrustPrompt(p.host, p.leafSha256)
                        return@launch
                    }
                    is GatewayProbe.Trusted -> if (t.alias !in p.providers) {
                        val available = p.providers.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none"
                        _state.value = OidcState.Failed("This daemon has no OIDC provider named ${t.alias}. Available: $available.")
                        return@launch
                    }
                }
                val grant = login.start(gw, t.alias)
                val link = grant.start.verificationUriComplete ?: grant.start.verificationUri
                if (!safeSignInLink(link)) {
                    _state.value = OidcState.Failed("The gateway sent an unsafe sign-in link. Only https:// pages are opened.")
                    return@launch
                }
                _state.value = OidcState.Approve(
                    userCode = grant.start.userCode,
                    verificationHost = runCatching { URI(grant.start.verificationUri).host }.getOrNull() ?: grant.start.verificationUri,
                    openUri = grant.start.verificationUriComplete ?: grant.start.verificationUri,
                    expiresAt = grant.startedAt + grant.start.expiresIn,
                )
                val token = login.awaitToken(gw, t.alias, grant)
                when (mode) {
                    is OidcMode.Renew -> {
                        val cert = if (mode.profile.notAfter - nowSeconds() > CERT_REUSE_MIN_S) null else {
                            _state.value = OidcState.Issuing
                            login.issueCertificate(gw, t.alias, token)
                        }
                        renewed(token, cert, gw.pinnedLeafSha256)
                    }
                    OidcMode.Onboard -> {
                        _state.value = OidcState.Issuing
                        keyUsed = true
                        val cert = login.issueCertificate(gw, t.alias, token)
                        val profile = DaemonProfile(
                            id = cert.deviceId, name = t.host, host = t.host, wssPort = t.wssPort,
                            caPem = cert.caChainPem, certPem = cert.certPem, deviceId = cert.deviceId,
                            issuedAt = nowSeconds(), notAfter = cert.notAfter,
                            authProvider = t.provider, wssUrl = t.wssUrl, gatewayUrl = t.gatewayUrl, oidcAlias = t.alias,
                            gatewayCertSha256 = gw.pinnedLeafSha256,
                            tokenObtainedAt = token.obtainedAt, tokenExpiresAt = token.expiresAt,
                        )
                        _state.value = OidcState.Connecting
                        probe(profile, token.accessToken).getOrElse { throw WssProbeFailed(it.message ?: "Could not connect") }
                        // From here the key belongs to the saved profile: a cancel must neither interrupt
                        // the save halfway nor delete the key it now refers to.
                        keyUsed = false
                        withContext(NonCancellable) { persist(profile, token.accessToken) }
                    }
                }
                _state.value = OidcState.Done
            } catch (e: CancellationException) {
                if (keyUsed) deleteKey()
                throw e
            } catch (e: Exception) {
                if (keyUsed) deleteKey()
                // Decoding errors quote the response body, which may hold a device code or access token.
                val detail = if (e is kotlinx.serialization.SerializationException) "" else ": ${e.message}"
                diagnose("oidc sign-in failed (gateway ${gw.url}): ${e.javaClass.simpleName}$detail")
                _state.value = OidcState.Failed(friendly(e, t))
            }
        }
    }

    private class WssProbeFailed(message: String) : Exception(message)

    private fun friendly(e: Exception, t: OidcTarget): String = when (e) {
        is OidcGrantExpired, is OidcDenied -> e.message.orEmpty()
        is WssProbeFailed -> wssFailureHint(e.message.orEmpty(), t.host).sentence()
        is EnrollHttpException -> when (e.status) {
            401 -> "The daemon refused the sign-in token."
            403 -> "Your account is not allowed to create sessions on this daemon."
            404 -> "This gateway has no OIDC sign-in. Check the gateway URL."
            409 -> "Remote WSS is not enabled on the daemon."
            429 -> "Too many attempts. Wait a minute and try again."
            else -> (e.message ?: "The gateway answered HTTP ${e.status}").sentence()
        }
        is javax.net.ssl.SSLException if "TLS packet header" in e.message.orEmpty() || "plaintext" in e.message.orEmpty() ->
            "The gateway at ${gateway?.url ?: t.gatewayUrl} answered in plain HTTP, not HTTPS. " +
                "Check the gateway URL in Advanced; plain http:// works only for a gateway on this phone (127.0.0.1)."
        is kotlinx.serialization.SerializationException -> "The gateway sent a response ZeroDroid could not read."
        is UnknownHostException, is ConnectException -> "Could not reach the gateway at ${t.gatewayUrl}."
        is SocketTimeoutException -> "The gateway did not answer in time. Check the connection and try again."
        else -> (e.message ?: "Sign-in failed").sentence()
    }

    private fun String.sentence() = trim().replaceFirstChar { it.uppercase() }.let { if (it.endsWith('.')) it else "$it." }
}
