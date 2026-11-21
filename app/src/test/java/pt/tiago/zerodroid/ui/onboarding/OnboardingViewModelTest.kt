package pt.tiago.zerodroid.ui.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.data.DaemonProfile
import pt.tiago.zerodroid.enroll.EnrollClient
import pt.tiago.zerodroid.enroll.EnrollHttp
import pt.tiago.zerodroid.enroll.EnrollResponse
import pt.tiago.zerodroid.security.caFingerprint

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val caPem = javaClass.getResourceAsStream("/fixtures/test-ca.pem")!!
        .bufferedReader().use { it.readText() }
    private val fp = caFingerprint(caPem)

    private fun link(host: String = "100.64.0.10", wss: Int = 9782) =
        "zerodroid://pair?v=1&code=AbCdEf0123456789AbCdEf0123456789" +
            "&token=zc_TESTTOKEN&host=$host&wss=$wss&enroll=9782&ca_fp=$fp&name=phone"

    private class FakeHttp(private val caPem: String) : EnrollHttp {
        var enrollCalls = 0
        override suspend fun fetchCa(host: String, port: Int) = caPem
        override suspend fun postEnroll(
            host: String, port: Int, pinnedCaPem: String, pairingCode: String, csrPem: String,
        ): EnrollResponse {
            enrollCalls++
            return EnrollResponse("cert", caPem, "dev-1", 9_999_999_999L)
        }
    }

    private fun vm(
        scope: CoroutineScope,
        http: FakeHttp = FakeHttp(caPem),
        persist: suspend (DaemonProfile, String) -> Unit = { _, _ -> },
        active: () -> DaemonProfile? = { null },
    ): Pair<OnboardingViewModel, FakeHttp> {
        val enroll = EnrollClient(http, csr = { "csr" })
        return OnboardingViewModel(enroll, persist, active, diagnose = {}, scope = scope) to http
    }

    @Test
    fun deepLinkGoesToConfirmWithSas() = runTest(UnconfinedTestDispatcher()) {
        val (vm, _) = vm(this)
        vm.onLink(link())
        val s = vm.state.value
        assertTrue("state=${s}", s is OnbState.Confirm)
        assertEquals("phone", (s as OnbState.Confirm).name)
        assertEquals("100.64.0.10", s.host)
        assertTrue(s.sas.matches(Regex("[A-F0-9]{4}-[A-F0-9]{4}")))
    }

    @Test
    fun confirmEnrollsAndPersistsOnce() = runTest(UnconfinedTestDispatcher()) {
        var persisted: Pair<DaemonProfile, String>? = null
        var calls = 0
        val (vm, http) = vm(this, persist = { p, t -> calls++; persisted = p to t })
        vm.onLink(link())
        vm.onConfirm()
        assertEquals(1, calls)
        assertEquals(1, http.enrollCalls)
        assertEquals("zc_TESTTOKEN", persisted?.second)
        assertEquals("dev-1", persisted?.first?.id)
        assertEquals("phone", persisted?.first?.name)
        assertEquals(9782, persisted?.first?.wssPort)
        assertTrue(vm.state.value is OnbState.Done)
    }

    @Test
    fun cancelPersistsNothing() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val (vm, http) = vm(this, persist = { _, _ -> calls++ })
        vm.onLink(link())
        vm.onCancel()
        vm.onConfirm()
        assertEquals(0, calls)
        assertEquals(0, http.enrollCalls)
        assertTrue(vm.state.value is OnbState.Welcome)
    }

    @Test
    fun secondLinkWhileEnrollingIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val (vm, http) = vm(this, persist = { _, _ -> calls++ })
        vm.onLink(link())
        vm.onLink(link(host = "other"))
        vm.onConfirm()
        assertEquals(1, calls)
        assertEquals(1, http.enrollCalls)
        assertTrue(vm.state.value is OnbState.Done)
    }

    @Test
    fun linkForAlreadyPairedDaemonSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val existing = DaemonProfile("dev-0", "old", "100.64.0.10", 9782, caPem, "cert", "dev-0", 0L, 1L)
        var calls = 0
        val (vm, http) = vm(this, persist = { _, _ -> calls++ }, active = { existing })
        vm.onLink(link())
        val s = vm.state.value
        assertTrue("state=${s}", s is OnbState.Failed)
        assertEquals("Already paired with old. Unpair first to pair again.", (s as OnbState.Failed).message)
        assertEquals(0, calls)
        assertEquals(0, http.enrollCalls)
    }

    @Test
    fun failureAllowsRetry() = runTest(UnconfinedTestDispatcher()) {
        val (vm, _) = vm(this)
        vm.onLink("garbage")
        assertTrue(vm.state.value is OnbState.Failed)
        vm.onLink(link())
        assertTrue(vm.state.value is OnbState.Confirm)
    }
}
