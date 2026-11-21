package pt.tiago.zerodroid.data

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.proto.InitializeResult

class RestartWatchTest {
    private val ready = ConnState.Ready(InitializeResult(protocolVersion = 1, serverVersion = "t", serverPid = 1))

    @Test
    fun knocksQuicklyDuringRestartInsteadOfWaitingBackoff() = runTest {
        val state = MutableStateFlow<ConnState>(ready)
        var retries = 0
        val watch = async { followRestart(state, { retries++ }, pollMs = 400) }
        runCurrent()
        state.value = ConnState.Backoff(4_000, "Connection closed")
        advanceTimeBy(1_300)
        // Four knocks in 1.3 s, where the backoff alone would wait up to 4 s.
        assertEquals(4, retries)
        state.value = ready
        advanceTimeBy(500)
        assertTrue(watch.await())
    }

    @Test
    fun noDropMeansNothingToFollow() = runTest {
        val state = MutableStateFlow<ConnState>(ready)
        var retries = 0
        assertTrue(followRestart(state, { retries++ }, dropWaitMs = 1_000))
        assertEquals(0, retries)
    }

    @Test
    fun givesUpWhenTheDaemonNeverComesBack() = runTest {
        val state = MutableStateFlow<ConnState>(ConnState.Backoff(1_000, "Failed to connect"))
        assertFalse(followRestart(state, {}, totalMs = 5_000))
    }
}
