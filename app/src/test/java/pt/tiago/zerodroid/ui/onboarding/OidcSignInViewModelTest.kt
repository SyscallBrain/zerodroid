package pt.tiago.zerodroid.ui.onboarding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.data.DaemonProfile
import pt.tiago.zerodroid.enroll.DeviceStart
import pt.tiago.zerodroid.enroll.EnrollResponse
import pt.tiago.zerodroid.enroll.GatewayProbe
import pt.tiago.zerodroid.enroll.GatewaySpec
import pt.tiago.zerodroid.enroll.OidcHttp
import pt.tiago.zerodroid.enroll.OidcLoginClient
import pt.tiago.zerodroid.enroll.OidcTargetException
import pt.tiago.zerodroid.enroll.OidcToken
import pt.tiago.zerodroid.enroll.PollOutcome
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OidcSignInViewModelTest {
    private val now = 10_000L

    private class FakeHttp : OidcHttp {
        var probes = ArrayDeque<GatewayProbe>(listOf(GatewayProbe.Trusted(listOf("corp"))))
        var startGate: CompletableDeferred<Unit>? = null
        var pollGate: CompletableDeferred<Unit>? = null
        val specs = mutableListOf<GatewaySpec>()
        var starts = 0
        var certs = 0
        override suspend fun probe(gw: GatewaySpec): GatewayProbe { specs.add(gw); return probes.removeFirstOrNull() ?: GatewayProbe.Trusted(listOf("corp")) }
        override suspend fun deviceStart(gw: GatewaySpec, alias: String, codeChallenge: String): DeviceStart {
            starts++; specs.add(gw)
            startGate?.await()
            return DeviceStart("dc", "ABCD-EFGH", "https://idp.example.com/device", "https://idp.example.com/device?code=ABCD", 600, 5)
        }
        override suspend fun devicePoll(gw: GatewaySpec, alias: String, deviceCode: String, codeVerifier: String): PollOutcome {
            pollGate?.await()
            return PollOutcome.Granted("at", 3600)
        }
        override suspend fun certificate(gw: GatewaySpec, alias: String, accessToken: String, csrPem: String): EnrollResponse {
            certs++; specs.add(gw)
            return EnrollResponse("cert", "ca", "dev-1", 9_999_999)
        }
    }

    private class Recorder {
        val events = mutableListOf<String>()
        var persisted: Pair<DaemonProfile, String>? = null
        var renewed: Triple<OidcToken, EnrollResponse?, String?>? = null
        var keyDeleted = 0
        var probeResult: Result<Unit> = Result.success(Unit)
    }

    private val renewProfile = DaemonProfile(
        "dev-0", "h", "h", 9781, "ca0", "cert0", "dev-0", 1, now + 7200,
        authProvider = "oidc.corp", gatewayUrl = "https://h:42617", oidcAlias = "corp",
        tokenObtainedAt = now - 3300, tokenExpiresAt = now + 300,
    )

    private fun vm(
        scope: CoroutineScope,
        http: FakeHttp = FakeHttp(),
        rec: Recorder = Recorder(),
        mode: OidcMode = OidcMode.Onboard,
        active: DaemonProfile? = null,
    ) = OidcSignInViewModel(
        mode = mode,
        login = OidcLoginClient(http, csr = { "csr" }, nowSeconds = { now }, sleep = {}, newVerifier = { "v" }),
        http = http,
        probe = { _, _ -> rec.events.add("probe"); rec.probeResult },
        persist = { p, t -> rec.events.add("persist"); rec.persisted = p to t },
        renewed = { t, c, pin -> rec.events.add("renewed"); rec.renewed = Triple(t, c, pin) },
        activeProfile = { active },
        deleteKey = { rec.keyDeleted++ },
        scope = scope,
        nowSeconds = { now },
    )

    @Test
    fun happyPathPersistsOnlyAfterProbe() = runTest(UnconfinedTestDispatcher()) {
        val rec = Recorder()
        val vm = vm(this, rec = rec)
        vm.submit("h", "corp", null, null)
        assertEquals(OidcState.Done, vm.state.value)
        assertEquals(listOf("probe", "persist"), rec.events)
        val (p, token) = rec.persisted!!
        assertEquals("at", token)
        assertEquals("oidc.corp", p.authProvider)
        assertEquals("corp", p.oidcAlias)
        assertEquals("https://h:42617", p.gatewayUrl)
        assertEquals("wss://h:9781/", p.connectUrl())
        assertEquals(now + 3600, p.tokenExpiresAt)
        assertEquals("ca", p.caPem)
        assertEquals("dev-1", p.deviceId)
        assertEquals(0, rec.keyDeleted)
    }

    @Test
    fun probeFailureDeletesKeyAndPersistsNothing() = runTest(UnconfinedTestDispatcher()) {
        val rec = Recorder().apply { probeResult = Result.failure(IllegalStateException("refused")) }
        val vm = vm(this, rec = rec)
        vm.submit("h", "corp", null, null)
        assertTrue(vm.state.value is OidcState.Failed)
        assertNull(rec.persisted)
        assertEquals(1, rec.keyDeleted)
    }

    @Test
    fun untrustedGatewayAsksThenPins() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttp().apply { probes = ArrayDeque(listOf(GatewayProbe.Untrusted("h", "ab12"), GatewayProbe.Trusted(listOf("corp")))) }
        val rec = Recorder()
        val vm = vm(this, http, rec)
        vm.submit("h", "corp", null, null)
        assertEquals(OidcState.TrustPrompt("h", "ab12"), vm.state.value)
        assertEquals(0, http.starts)
        vm.trust()
        assertEquals(OidcState.Done, vm.state.value)
        assertTrue(http.specs.drop(1).all { it.pinnedLeafSha256 == "ab12" })
        assertEquals("ab12", rec.persisted!!.first.gatewayCertSha256)
    }

    @Test
    fun unknownAliasListsAvailable() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttp().apply { probes = ArrayDeque(listOf(GatewayProbe.Trusted(listOf("dev", "ops")))) }
        val vm = vm(this, http)
        vm.submit("h", "corp", null, null)
        val s = vm.state.value as OidcState.Failed
        assertTrue(s.message, s.message.contains("dev, ops"))
        assertEquals(0, http.starts)
    }

    @Test
    fun doubleSubmitStartsOneGrant() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttp().apply { startGate = CompletableDeferred() }
        val vm = vm(this, http)
        vm.submit("h", "corp", null, null)
        vm.submit("h", "corp", null, null)
        http.startGate!!.complete(Unit)
        assertEquals(1, http.starts)
        assertEquals(OidcState.Done, vm.state.value)
    }

    @Test
    fun refusesWhenAlreadyPaired() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttp()
        val vm = vm(this, http, active = renewProfile)
        vm.submit("h", "corp", null, null)
        val s = vm.state.value as OidcState.Failed
        assertTrue(s.message, s.message.contains("Unpair first"))
        assertTrue(http.specs.isEmpty())
    }

    @Test
    fun badAliasStaysOnFormWithFieldError() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttp()
        val vm = vm(this, http)
        vm.submit("h", "co rp", null, null)
        val s = vm.state.value as OidcState.Form
        assertEquals(OidcTargetException.Field.Alias, s.fieldError!!.first)
        assertTrue(http.specs.isEmpty())
    }

    @Test
    fun approveShowsCodeAndCompleteUri() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttp().apply { pollGate = CompletableDeferred() }
        val vm = vm(this, http)
        vm.submit("h", "corp", null, null)
        val approve = vm.state.value as OidcState.Approve
        assertEquals("ABCD-EFGH", approve.userCode)
        assertEquals("idp.example.com", approve.verificationHost)
        assertEquals("https://idp.example.com/device?code=ABCD", approve.openUri)
        assertEquals(now + 600, approve.expiresAt)
        http.pollGate!!.complete(Unit)
        assertEquals(OidcState.Done, vm.state.value)
    }

    @Test
    fun cancelDuringApproveReturnsToFormAndPersistsNothing() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttp().apply { pollGate = CompletableDeferred() }
        val rec = Recorder()
        val vm = vm(this, http, rec)
        vm.submit("h", "corp", null, null)
        vm.cancel()
        assertEquals(OidcState.Form(), vm.state.value)
        assertNull(rec.persisted)
    }

    @Test
    fun renewReusesValidCertificate() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttp()
        val rec = Recorder()
        val vm = vm(this, http, rec, OidcMode.Renew(renewProfile))
        vm.renew()
        assertEquals(OidcState.Done, vm.state.value)
        assertEquals(0, http.certs)
        assertNull(rec.renewed!!.second)
        assertEquals("at", rec.renewed!!.first.accessToken)
        assertFalse(rec.events.contains("persist"))
    }

    @Test
    fun renewIssuesCertNearExpiry() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttp()
        val rec = Recorder()
        val vm = vm(this, http, rec, OidcMode.Renew(renewProfile.copy(notAfter = now + 1800)))
        vm.renew()
        assertEquals(1, http.certs)
        assertEquals("dev-1", rec.renewed!!.second!!.deviceId)
    }

    @Test
    fun renewWithRotatedGatewayCertPrompts() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttp().apply { probes = ArrayDeque(listOf(GatewayProbe.Untrusted("h", "bb"))) }
        val vm = vm(this, http, mode = OidcMode.Renew(renewProfile.copy(gatewayCertSha256 = "aa")))
        vm.renew()
        assertEquals(OidcState.TrustPrompt("h", "bb"), vm.state.value)
        assertEquals("aa", http.specs.first().pinnedLeafSha256)
        assertEquals(0, http.starts)
    }

    @Test
    fun wssHostnameFailureShowsSansHint() = runTest(UnconfinedTestDispatcher()) {
        val rec = Recorder().apply { probeResult = Result.failure(IOException("Hostname h not verified")) }
        val vm = vm(this, rec = rec)
        vm.submit("h", "corp", null, null)
        val s = vm.state.value as OidcState.Failed
        assertTrue(s.message, s.message.contains("[wss].sans"))
    }

    @Test
    fun hintMapping() {
        assertTrue(wssFailureHint("Trust anchor for certification path not found.", "h").contains("not signed by the daemon CA"))
        assertEquals("boom", wssFailureHint("boom", "h"))
    }

    @Test
    fun malformedResponseNeverReachesDiagnosticsOrScreen() = runTest(UnconfinedTestDispatcher()) {
        val logged = mutableListOf<String>()
        val http = object : OidcHttp by FakeHttp() {
            override suspend fun probe(gw: GatewaySpec) = GatewayProbe.Trusted(listOf("corp"))
            override suspend fun deviceStart(gw: GatewaySpec, alias: String, codeChallenge: String): DeviceStart =
                throw kotlinx.serialization.SerializationException("Unexpected JSON at input: {\"device_code\":\"secret-dc\"}")
        }
        val vm = OidcSignInViewModel(
            OidcMode.Onboard, OidcLoginClient(http, { "csr" }, { now }, {}, { "v" }), http,
            { _, _ -> Result.success(Unit) }, { _, _ -> }, { _, _, _ -> }, { null }, {}, diagnose = { logged.add(it) },
            scope = this, nowSeconds = { now },
        )
        vm.submit("h", "corp", null, null)
        val s = vm.state.value as OidcState.Failed
        assertFalse(s.message, s.message.contains("secret-dc"))
        assertTrue(logged.none { it.contains("secret-dc") })
    }
}
