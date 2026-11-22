package pt.tiago.zerodroid.enroll

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class OidcLoginClientTest {
    private val gw = GatewaySpec("https://gw", null)

    private class FakeHttp(
        val polls: ArrayDeque<PollOutcome> = ArrayDeque(),
        var expiresIn: Long = 600,
        var interval: Long = 5,
    ) : OidcHttp {
        var challenge: String? = null
        val verifiers = mutableListOf<String>()
        var pollCount = 0
        var certToken: String? = null
        var certCsr: String? = null
        override suspend fun probe(gw: GatewaySpec) = GatewayProbe.Trusted(listOf("corp"))
        override suspend fun deviceStart(gw: GatewaySpec, alias: String, codeChallenge: String): DeviceStart {
            challenge = codeChallenge
            return DeviceStart("dc", "ABCD", "https://idp/device", null, expiresIn, interval)
        }
        override suspend fun devicePoll(gw: GatewaySpec, alias: String, deviceCode: String, codeVerifier: String): PollOutcome {
            pollCount++
            verifiers.add(codeVerifier)
            return polls.removeFirstOrNull() ?: PollOutcome.Pending
        }
        override suspend fun certificate(gw: GatewaySpec, alias: String, accessToken: String, csrPem: String): EnrollResponse {
            certToken = accessToken
            certCsr = csrPem
            return EnrollResponse("cert", "ca", "dev-1", 9_999)
        }
    }

    private class Clock(var now: Long = 1_000) {
        val sleeps = mutableListOf<Long>()
        suspend fun sleep(s: Long) { sleeps.add(s); now += s }
    }

    private fun client(http: FakeHttp, clock: Clock) =
        OidcLoginClient(http, csr = { "csr" }, nowSeconds = { clock.now }, sleep = clock::sleep, newVerifier = { "verifier-1" })

    @Test
    fun startSendsS256ChallengeOfVerifier() = runBlocking {
        val http = FakeHttp()
        val grant = client(http, Clock()).start(gw, "corp")
        assertEquals(Pkce.challenge("verifier-1"), http.challenge)
        assertEquals("verifier-1", grant.verifier)
        assertEquals(1_000L, grant.startedAt)
    }

    @Test
    fun pollsUntilGranted() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(PollOutcome.Pending, PollOutcome.Pending, PollOutcome.Granted("at", 600))))
        val clock = Clock()
        val c = client(http, clock)
        val token = c.awaitToken(gw, "corp", c.start(gw, "corp"))
        assertEquals("at", token.accessToken)
        assertEquals(1_015L, token.obtainedAt)
        assertEquals(1_615L, token.expiresAt)
        assertEquals(listOf(5L, 5L, 5L), clock.sleeps)
    }

    @Test
    fun slowDownAddsFiveSeconds() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(PollOutcome.SlowDown, PollOutcome.Granted("at", 600))))
        val clock = Clock()
        val c = client(http, clock)
        c.awaitToken(gw, "corp", c.start(gw, "corp"))
        assertEquals(listOf(5L, 10L), clock.sleeps)
    }

    @Test
    fun badGatewayRetriesAtSameInterval() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(PollOutcome.Retry("x"), PollOutcome.Granted("at", 600))))
        val clock = Clock()
        val c = client(http, clock)
        c.awaitToken(gw, "corp", c.start(gw, "corp"))
        assertEquals(listOf(5L, 5L), clock.sleeps)
    }

    @Test
    fun rateLimitedWaitsRetryAfter() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(PollOutcome.RateLimited(30), PollOutcome.Granted("at", 600))))
        val clock = Clock()
        val c = client(http, clock)
        c.awaitToken(gw, "corp", c.start(gw, "corp"))
        assertEquals(listOf(5L, 30L), clock.sleeps)
    }

    @Test
    fun deniedStops() {
        val http = FakeHttp(ArrayDeque(listOf(PollOutcome.Denied("no"))))
        val c = client(http, Clock())
        val e = assertThrows(OidcDenied::class.java) { runBlocking { c.awaitToken(gw, "corp", c.start(gw, "corp")) } }
        assertEquals("no", e.message)
        assertEquals(1, http.pollCount)
    }

    @Test
    fun grantExpiresWhenUserTakesTooLong() {
        val http = FakeHttp(expiresIn = 12)
        val c = client(http, Clock())
        assertThrows(OidcGrantExpired::class.java) { runBlocking { c.awaitToken(gw, "corp", c.start(gw, "corp")) } }
        assert(http.pollCount <= 3) { "polled ${http.pollCount} times" }
    }

    @Test
    fun grantedWithoutExpiryHasUnknownExpiry() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(PollOutcome.Granted("at", null))))
        val c = client(http, Clock())
        assertEquals(null, c.awaitToken(gw, "corp", c.start(gw, "corp")).expiresAt)
    }

    @Test
    fun pollSendsVerifierNotChallenge() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(PollOutcome.Granted("at", 600))))
        val c = client(http, Clock())
        c.awaitToken(gw, "corp", c.start(gw, "corp"))
        assertEquals(listOf("verifier-1"), http.verifiers)
    }

    @Test
    fun certificateUsesBearerAndCsr() = runBlocking {
        val http = FakeHttp()
        val r = client(http, Clock()).issueCertificate(gw, "corp", OidcToken("at", 1, 2))
        assertEquals("at", http.certToken)
        assertEquals("csr", http.certCsr)
        assertEquals("dev-1", r.deviceId)
    }

    @Test
    fun secretsAreRedacted() {
        assertFalse(OidcToken("secret-at", 1, 2).toString().contains("secret-at"))
        assertFalse(OidcGrant(DeviceStart("dc", "U", "https://i", null, 1), "secret-v", 1).toString().contains("secret-v"))
    }

    @Test
    fun expiredTokenDenialMeansGrantExpired() {
        val http = FakeHttp(ArrayDeque(listOf(PollOutcome.Denied("device grant failed: expired_token"))))
        val c = client(http, Clock())
        assertThrows(OidcGrantExpired::class.java) { runBlocking { c.awaitToken(gw, "corp", c.start(gw, "corp")) } }
    }

    @Test
    fun networkErrorDuringPollRetries() = runBlocking {
        var failures = 1
        val http = object : OidcHttp by FakeHttp(ArrayDeque(listOf(PollOutcome.Granted("at", 600)))) {
            val inner = FakeHttp(ArrayDeque(listOf(PollOutcome.Granted("at", 600))))
            override suspend fun devicePoll(gw: GatewaySpec, alias: String, deviceCode: String, codeVerifier: String): PollOutcome {
                if (failures-- > 0) throw java.io.IOException("network down")
                return inner.devicePoll(gw, alias, deviceCode, codeVerifier)
            }
        }
        val clock = Clock()
        val c = OidcLoginClient(http, csr = { "csr" }, nowSeconds = { clock.now }, sleep = clock::sleep, newVerifier = { "v" })
        assertEquals("at", c.awaitToken(gw, "corp", c.start(gw, "corp")).accessToken)
        assertEquals(listOf(5L, 5L), clock.sleeps)
    }
}
