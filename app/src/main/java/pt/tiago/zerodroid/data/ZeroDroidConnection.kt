package pt.tiago.zerodroid.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import pt.tiago.zerodroid.enroll.OidcToken
import pt.tiago.zerodroid.CrashLog
import pt.tiago.zerodroid.Perf
import pt.tiago.zerodroid.enroll.EnrollResponse
import pt.tiago.zerodroid.rpc.ClientCaller
import pt.tiago.zerodroid.rpc.ClientIdentity
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.OkHttpWsTransport
import pt.tiago.zerodroid.rpc.RpcClient
import pt.tiago.zerodroid.rpc.WsHandle
import pt.tiago.zerodroid.rpc.WsListener
import pt.tiago.zerodroid.rpc.WsTransport
import pt.tiago.zerodroid.rpc.request
import pt.tiago.zerodroid.security.KeystoreIdentity
import pt.tiago.zerodroid.security.TlsFactory
import pt.tiago.zerodroid.security.TokenVault
import pt.tiago.zerodroid.security.TuiIdentity
import pt.tiago.zerodroid.security.buildCsrPem
import java.io.IOException

/** Where the app is in its life: deciding, needs pairing, or paired with a daemon. */
sealed interface AppPhase {
    data object Loading : AppPhase
    data class Unpaired(val notice: String? = null) : AppPhase
    data class Paired(val profile: DaemonProfile) : AppPhase
}

/**
 * The single connection to the paired daemon, owned by the process (not by a screen).
 * Screens observe [state]; the turn keep-alive service holds it open in the background.
 */
object ZeroDroidConnection {
    private const val BACKGROUND_GRACE_MS = 60_000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _phase = MutableStateFlow<AppPhase>(AppPhase.Loading)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _restarting = MutableStateFlow(false)
    /** True while a daemon restart this app asked for (config reload) is under way. */
    val restarting: StateFlow<Boolean> = _restarting.asStateFlow()
    private var restartJob: Job? = null
    private val _client = MutableStateFlow<RpcClient?>(null)
    val client: StateFlow<RpcClient?> = _client.asStateFlow()

    /** Last successful `initialize`, kept for display while reconnecting. */
    @Volatile var lastServerVersion: String? = null
        private set

    val rpc = ClientCaller { _client.value }
    val approvals = ApprovalCenter(rpc)
    val chats = ChatSessions(rpc, approvals, scope)
    val sessions = SessionsRepository(rpc)
    val sopGates = SopGates(rpc)
    val models = ModelInfo(rpc, pt.tiago.zerodroid.data.config.ConfigRepository(rpc))
    lateinit var config: pt.tiago.zerodroid.data.config.ConfigEditor
        private set
    lateinit var pins: PinStore
        private set
    lateinit var screen: ScreenPrefs
        private set
    lateinit var codeFolders: CodeFolders
        private set

    /** True while a phone-started turn runs or an approval waits: keep the socket up in the background. */
    val holdForeground: StateFlow<Boolean> =
        combine(chats.running, approvals.pendingCount) { running, pending -> running.isNotEmpty() || pending > 0 }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val _signIn = MutableStateFlow<SignInStatus>(SignInStatus.NotOidc)
    /** Where the OIDC access token stands; [SignInStatus.NotOidc] for link-paired daemons. */
    val signIn: StateFlow<SignInStatus> = _signIn.asStateFlow()
    private var expiryJob: Job? = null
    private val gate = SignInGate { System.currentTimeMillis() / 1000 }
    /** The bearer the live client presents; replaced in place when an OIDC sign-in is renewed. */
    @Volatile private var currentToken: String? = null

    private val _renewRequested = MutableStateFlow(false)
    /** The UI should open the OIDC renew sheet (banner, Config → Phone or a notification asked). */
    val renewRequested: StateFlow<Boolean> = _renewRequested.asStateFlow()
    // Unconditional: a cold start from the notification asks before the profile has loaded; PairedShell checks isOidc.
    fun requestRenew() { _renewRequested.value = true }
    fun renewHandled() { _renewRequested.value = false }

    private val _simulateUnreachable = MutableStateFlow(false)
    val simulateUnreachable: StateFlow<Boolean> = _simulateUnreachable.asStateFlow()

    private lateinit var appContext: Context
    private lateinit var store: DaemonProfileStore
    private lateinit var vault: TokenVault
    private val startLock = Mutex()
    private var stateJob: Job? = null
    private var notifyJob: Job? = null
    private var backgroundJob: Job? = null
    @Volatile private var foreground = false
    private val _foregroundState = MutableStateFlow(false)
    /** [isForeground] as a flow, for watchers that must react when the app leaves the screen. */
    val foregroundState: StateFlow<Boolean> = _foregroundState.asStateFlow()
    /** True while an activity of the app is started (visible). */
    val isForeground: Boolean get() = foreground
    @Volatile private var identity: ClientIdentity? = null
    private var lastLogged: String? = null

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = DaemonProfileStore(appContext)
        vault = TokenVault(appContext)
        config = pt.tiago.zerodroid.data.config.ConfigEditor(
            pt.tiago.zerodroid.data.config.ConfigRepository(rpc), chats, scope,
            pt.tiago.zerodroid.data.config.DataStoreDraftPersistence(appContext),
        )
        chats.imageCache = ImageCache(java.io.File(appContext.filesDir, "images"))
        pins = PinStore(DataStorePinPersistence(appContext))
        scope.launch { pins.load() }
        screen = ScreenPrefs(appContext)
        scope.launch { screen.load() }
        codeFolders = CodeFolders(DataStoreCodeFolderPersistence(appContext))
        scope.launch { codeFolders.load() }
        scope.launch { loadPhase() }
        scope.launch {
            while (isActive) {
                approvals.expireDue()
                delay(1_000)
            }
        }
    }

    private suspend fun loadPhase() {
        val profile = Perf.span("phase.profileStore") { runCatching { store.active.first() }.getOrNull() }
        if (profile == null) {
            Perf.mark("phase.unpaired")
            _phase.value = AppPhase.Unpaired()
            return
        }
        val usable = Perf.span("phase.keystoreUsable") { runCatching { KeystoreIdentity().isUsableForTls() }.getOrDefault(false) }
        if (!usable || profile.issuedAt > 10_000_000_000L) {
            // Pairings from builds before 0.2.0 used a key the TLS 1.3 handshake cannot sign with.
            forget()
            _phase.value = AppPhase.Unpaired("This phone's pairing came from an older build that could not connect. Pair it again.")
            return
        }
        identity = Perf.span("phase.loadIdentity") { vault.loadIdentity() }?.let { ClientIdentity(it.id, it.sig) }
        Perf.mark("phase.paired")
        _phase.value = AppPhase.Paired(profile)
        scheduleExpiry(profile)
        if (foreground) ensureStarted()
    }

    /** Called by onboarding once enrollment succeeded. */
    suspend fun onPaired(profile: DaemonProfile, token: String) {
        store.save(profile)
        vault.store(token)
        gate.onRenewed()
        identity = null
        approvals.clear()
        chats.clear()
        _phase.value = AppPhase.Paired(profile)
        scheduleExpiry(profile)
        ensureStarted()
    }

    /**
     * A renewed OIDC sign-in: store the token (and a new certificate if one was issued),
     * then reconnect so `initialize` carries it. [identity] is kept, so session ownership survives.
     */
    suspend fun onReauthenticated(token: OidcToken, newCert: EnrollResponse?, gatewayCertSha256: String?) {
        val current = (_phase.value as? AppPhase.Paired)?.profile ?: return
        val now = System.currentTimeMillis() / 1000
        var updated = current.copy(
            tokenObtainedAt = token.obtainedAt,
            tokenExpiresAt = token.expiresAt,
            gatewayCertSha256 = gatewayCertSha256 ?: current.gatewayCertSha256,
        )
        if (newCert != null) {
            updated = updated.copy(
                certPem = newCert.certPem, caPem = newCert.caChainPem, deviceId = newCert.deviceId,
                issuedAt = now, notAfter = newCert.notAfter,
            )
        }
        store.save(updated)
        vault.store(token.accessToken)
        gate.onRenewed()
        currentToken = token.accessToken
        _phase.value = AppPhase.Paired(updated)
        scheduleExpiry(updated)
        // A new certificate needs a new TLS handshake; otherwise renew on the open socket, because
        // closing it makes the daemon cancel running turns.
        if (newCert == null && revalidateInPlace()) return
        reconnectQuietly()
    }

    /** `initialize` again on the live socket with [currentToken]; false when that was not possible. */
    private suspend fun revalidateInPlace(): Boolean {
        val c = _client.value ?: return false
        if (_state.value !is ConnState.Ready) return false
        return runCatching { c.reinitialize() }
            .onFailure { log("conn", "in-place initialize failed: ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
    }

    /** Last resort: the daemon keeps the session (same tui_id), so nothing is marked lost here. */
    private fun reconnectQuietly() {
        stopClient(quiet = true)
        ensureStarted()
    }

    /** Spec §5.6: track the token clock; at expiry close the socket instead of letting it go quiet. */
    private fun scheduleExpiry(profile: DaemonProfile) {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis() / 1000
                val status = gate.status(profile)
                _signIn.value = status
                if (status == SignInStatus.Expired) {
                    expire()
                    return@launch
                }
                val next = nextSignInCheck(profile, now) ?: return@launch
                delay((next - now).coerceAtLeast(1) * 1000)
            }
        }
    }

    /** An AUTH_REQUIRED from [from]: only the current client of an OIDC profile can expire the sign-in. */
    private suspend fun onRefused(from: RpcClient?, atInitialize: Boolean) {
        val profile = (_phase.value as? AppPhase.Paired)?.profile ?: return
        when (gate.onAuthRequired(profile, fromCurrentClient = from != null && _client.value === from, atInitialize = atInitialize)) {
            AuthAction.Ignore -> Unit
            AuthAction.Expire -> expire()
            AuthAction.Reinitialize -> {
                log("conn", "daemon asked to revalidate the OIDC sign-in; re-initializing with the same token")
                if (!revalidateInPlace() && gate.canConnect(profile)) reconnectQuietly()
            }
        }
    }

    private fun expire() {
        val profile = (_phase.value as? AppPhase.Paired)?.profile ?: return
        if (!profile.isOidc) return
        // The clock would otherwise move the banner back to "expires in N min" while the connection is dead.
        expiryJob?.cancel()
        _signIn.value = SignInStatus.Expired
        stopClient()
        _state.value = ConnState.AuthFailed(EXPIRED_MESSAGE)
        log("conn", "OIDC sign-in expired; waiting for the user to sign in again")
    }

    /**
     * Opens a throwaway connection for [profile] and waits for the first `initialize` outcome.
     * Nothing is stored, including the TUI identity the daemon hands back.
     */
    suspend fun probe(profile: DaemonProfile, token: String, timeoutMs: Long = 20_000): Result<Unit> {
        val c = runCatching { buildClient(profile, token, probe = true) }.getOrElse { return Result.failure(it) }
        return try {
            c.start()
            val outcome = withTimeoutOrNull(timeoutMs) {
                c.state.first { it is ConnState.Ready || it is ConnState.AuthFailed || it is ConnState.Backoff }
            }
            when (outcome) {
                is ConnState.Ready -> Result.success(Unit)
                is ConnState.AuthFailed -> Result.failure(IllegalStateException(outcome.message))
                is ConnState.Backoff -> Result.failure(IOException(outcome.cause))
                else -> Result.failure(IOException("The daemon did not answer in time"))
            }
        } finally {
            c.stop()
        }
    }

    fun onForeground() {
        foreground = true
        _foregroundState.value = true
        backgroundJob?.cancel()
        ensureStarted()
    }

    fun onBackground() {
        foreground = false
        _foregroundState.value = false
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(BACKGROUND_GRACE_MS)
            while (holdForeground.value) holdForeground.first { !it }
            if (!foreground) stopClient()
        }
    }

    fun ensureStarted() {
        if (!::appContext.isInitialized) return
        scope.launch {
            startLock.withLock {
                val profile = (_phase.value as? AppPhase.Paired)?.profile ?: return@withLock
                if (_client.value != null) return@withLock
                if (!gate.canConnect(profile)) {
                    _signIn.value = SignInStatus.Expired
                    _state.value = ConnState.AuthFailed(EXPIRED_MESSAGE)
                    return@withLock
                }
                val token = Perf.span("conn.vaultLoad") { runCatching { vault.load() }.getOrNull() }
                if (token == null) {
                    log("conn", "no token in the vault; pairing must be redone")
                    forget()
                    _phase.value = AppPhase.Unpaired("The saved pairing is incomplete. Pair this phone again.")
                    return@withLock
                }
                currentToken = token
                val rpcClient = Perf.span("conn.buildClient") { runCatching { buildClient(profile, token) } }.getOrElse {
                    log("conn", "could not build TLS client: ${it.javaClass.simpleName}: ${it.message}")
                    _state.value = ConnState.AuthFailed("This phone's certificate could not be loaded. Unpair and pair again.")
                    return@withLock
                }
                _client.value = rpcClient
                observe(rpcClient, profile)
                Perf.mark("conn.start")
                rpcClient.start()
            }
        }
    }

    private fun buildClient(profile: DaemonProfile, token: String, probe: Boolean = false): RpcClient {
        var self: RpcClient? = null
        val km = TlsFactory.clientKeyManager(KeystoreIdentity.ALIAS, profile.certPem)
        val tm = TlsFactory.pinnedTrustManager(profile.caPem)
        val ssl = TlsFactory.tls13Context(km, tm)
        val real = OkHttpWsTransport(ssl, tm) { detail -> log("wss", detail) }
        val transport = object : WsTransport {
            override fun open(url: String, listener: WsListener): WsHandle {
                if (_simulateUnreachable.value) {
                    scope.launch { listener.onFailure(IOException("Daemon unreachable (simulated)")) }
                    return object : WsHandle {
                        override fun send(text: String) = false
                        override fun close(code: Int, reason: String) = Unit
                    }
                }
                return real.open(url, listener)
            }
        }
        return RpcClient(
            transport = transport,
            url = profile.connectUrl(),
            token = { if (probe) token else currentToken ?: token },
            scope = scope,
            identity = { if (probe) null else identity },
            onIdentity = { id ->
                if (!probe && id != identity) {
                    identity = id
                    scope.launch { runCatching { vault.storeIdentity(TuiIdentity(id.tuiId, id.tuiSig)) } }
                }
            },
            authProvider = { profile.authProvider.takeIf { profile.isOidc } },
            onAuthRequired = { _, atInitialize -> if (!probe && profile.isOidc) scope.launch { onRefused(self, atInitialize) } },
        ).also { self = it }
    }

    private fun observe(c: RpcClient, profile: DaemonProfile) {
        stateJob?.cancel()
        notifyJob?.cancel()
        notifyJob = scope.launch {
            c.notifications.collect { n -> if (n.method == "session/update") chats.dispatch(n.params) }
        }
        stateJob = scope.launch {
            var wasReady = false
            c.state.collect { s ->
                _state.value = s
                when (s) {
                    is ConnState.Ready -> {
                        pt.tiago.zerodroid.Perf.mark("conn.ready")
                        lastServerVersion = s.info.serverVersion
                        chats.onReconnected()
                        if (::config.isInitialized) config.refreshOutcomes()
                        wasReady = true
                        maybeRenew(c, profile)
                    }
                    is ConnState.Backoff, is ConnState.AuthFailed -> {
                        if (wasReady) {
                            chats.onConnectionLost()
                            approvals.onConnectionLost()
                        }
                        wasReady = false
                        val cause = (s as? ConnState.Backoff)?.cause ?: (s as ConnState.AuthFailed).message
                        log("conn", "${s::class.simpleName}: $cause")
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun maybeRenew(c: RpcClient, profile: DaemonProfile) {
        val now = System.currentTimeMillis() / 1000
        if (!renewalDue(profile.issuedAt, profile.notAfter, now)) return
        runCatching {
            val csr = buildCsrPem(KeystoreIdentity().getOrCreate())
            val r = c.request<Map<String, String>, EnrollResponse>("cert/renew", mapOf("csr_pem" to csr))
            val renewed = profile.copy(certPem = r.certPem, issuedAt = now, notAfter = r.notAfter)
            store.save(renewed)
            _phase.value = AppPhase.Paired(renewed)
        }.onFailure { log("renew", "certificate renewal skipped: ${it.message}") }
    }

    private fun stopClient(quiet: Boolean = false) {
        stateJob?.cancel()
        notifyJob?.cancel()
        _client.value?.stop()
        _client.value = null
        if (!quiet && _state.value is ConnState.Ready) {
            chats.onConnectionLost()
            approvals.onConnectionLost()
        }
        _state.value = ConnState.Idle
    }

    /** Call right before asking the daemon to restart: the drop that follows is expected, reconnect fast. */
    fun expectRestart() {
        restartJob?.cancel()
        _restarting.value = true
        restartJob = scope.launch {
            try {
                if (!followRestart(state, ::retryNow)) log("conn", "daemon did not come back within 30 s of a reload")
            } finally {
                _restarting.value = false
            }
        }
    }

    /** The restart request failed: the daemon is not restarting after all. */
    fun cancelRestart() {
        restartJob?.cancel()
        _restarting.value = false
    }

    /** Skip the backoff wait (pull-to-refresh, "Retry now"). */
    fun retryNow() {
        val c = _client.value
        if (c == null) ensureStarted() else c.retryNow()
    }

    // ── Debug diagnostics (Config → Diagnostics) ─────────────────────────
    fun dropConnection() {
        _client.value?.simulateDrop()
    }

    fun setSimulateUnreachable(on: Boolean) {
        _simulateUnreachable.value = on
        if (on) _client.value?.simulateDrop() else retryNow()
    }

    fun unpair() {
        scope.launch {
            stopClient()
            forget()
            _phase.value = AppPhase.Unpaired()
        }
    }

    private suspend fun forget() {
        expiryJob?.cancel()
        gate.onRenewed()
        _signIn.value = SignInStatus.NotOidc
        currentToken = null
        runCatching { store.profiles.first().forEach { store.remove(it.id) } }
        runCatching { vault.clear() }
        runCatching { KeystoreIdentity().delete() }
        identity = null
        approvals.clear()
        chats.clear()
        if (::config.isInitialized) config.clear()
    }

    /** Diagnostics for SSH reading; deduplicated so a reconnect loop does not flood the folder. */
    private fun log(tag: String, detail: String) {
        val key = "$tag:${detail.take(160)}"
        if (key == lastLogged) return
        lastLogged = key
        runCatching { CrashLog.record(appContext, tag, detail) }
    }
}
