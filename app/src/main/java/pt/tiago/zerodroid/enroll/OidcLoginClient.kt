package pt.tiago.zerodroid.enroll

import kotlinx.coroutines.delay

/** One device authorization grant in progress. The verifier never leaves memory. */
data class OidcGrant(val start: DeviceStart, val verifier: String, val startedAt: Long) {
    override fun toString() = "OidcGrant(start=$start, verifier=***, startedAt=$startedAt)"
}

/** An OIDC access token; [expiresAt] is null when the provider did not say. */
data class OidcToken(val accessToken: String, val obtainedAt: Long, val expiresAt: Long?) {
    override fun toString() = "OidcToken(accessToken=***, obtainedAt=$obtainedAt, expiresAt=$expiresAt)"
}

class OidcGrantExpired : Exception("The sign-in code expired. Start again.")
class OidcDenied(message: String) : Exception(message)

/**
 * The gateway-relayed device grant zerocode login uses (RFC 8628 + S256 PKCE),
 * then the CSR signing call. Pure logic over [OidcHttp]; clock and sleep are injectable.
 */
class OidcLoginClient(
    private val http: OidcHttp,
    private val csr: () -> String,
    private val nowSeconds: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it * 1000) },
    private val newVerifier: () -> String = { Pkce.newVerifier() },
) {
    suspend fun start(gw: GatewaySpec, alias: String): OidcGrant {
        val verifier = newVerifier()
        val start = http.deviceStart(gw, alias, Pkce.challenge(verifier))
        return OidcGrant(start, verifier, nowSeconds())
    }

    suspend fun awaitToken(gw: GatewaySpec, alias: String, grant: OidcGrant): OidcToken {
        val deadline = grant.startedAt + grant.start.expiresIn
        var interval = grant.start.interval.coerceAtLeast(1)
        var wait = interval
        while (true) {
            sleep(wait)
            if (nowSeconds() >= deadline) throw OidcGrantExpired()
            wait = interval
            when (val outcome = http.devicePoll(gw, alias, grant.start.deviceCode, grant.verifier)) {
                PollOutcome.Pending, is PollOutcome.Retry -> Unit
                PollOutcome.SlowDown -> { interval += 5; wait = interval }
                is PollOutcome.RateLimited -> wait = outcome.retryAfter ?: (interval * 2)
                is PollOutcome.Denied -> throw OidcDenied(outcome.message)
                is PollOutcome.Granted -> {
                    val now = nowSeconds()
                    return OidcToken(outcome.accessToken, now, outcome.expiresIn?.let { now + it })
                }
            }
        }
    }

    suspend fun issueCertificate(gw: GatewaySpec, alias: String, token: OidcToken): EnrollResponse {
        val r = http.certificate(gw, alias, token.accessToken, csr())
        require(r.certPem.isNotBlank() && r.caChainPem.isNotBlank()) { "The daemon returned an empty certificate." }
        return r
    }
}
