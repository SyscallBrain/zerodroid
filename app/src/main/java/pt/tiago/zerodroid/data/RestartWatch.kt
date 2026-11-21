package pt.tiago.zerodroid.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import pt.tiago.zerodroid.rpc.ConnState

/**
 * Follows a daemon restart the app asked for (`config/reload`): waits for the socket to drop,
 * then knocks every [pollMs] instead of waiting out the jittered backoff, until the connection is
 * back. Gives up after [totalMs]. Returns true when the connection came back in time.
 */
suspend fun followRestart(
    state: StateFlow<ConnState>,
    retryNow: () -> Unit,
    dropWaitMs: Long = 5_000,
    pollMs: Long = 400,
    totalMs: Long = 30_000,
): Boolean = withTimeoutOrNull(totalMs) {
    // The reload reply can arrive before the socket closes; if it never closes there is nothing to follow.
    withTimeoutOrNull(dropWaitMs) { state.first { it !is ConnState.Ready } } ?: return@withTimeoutOrNull true
    while (state.value !is ConnState.Ready) {
        if (state.value is ConnState.Backoff) retryNow()
        delay(pollMs)
    }
    true
} ?: false
