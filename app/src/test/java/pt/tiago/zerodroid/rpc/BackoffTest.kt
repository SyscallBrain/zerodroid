package pt.tiago.zerodroid.rpc

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun capsAndJitters() {
        val backoff = Backoff(baseMs = 1_000, maxMs = 30_000, random = Random(42))
        for (n in 0..20) {
            val cap = minOf(30_000L, 1_000L shl n.coerceAtMost(20))
            repeat(20) {
                val value = backoff.next(n)
                assertTrue("next($n)=$value outside [0,$cap]", value in 0..cap)
            }
        }
        assertTrue(backoff.next(20) <= 30_000)
        backoff.reset()
        assertTrue(backoff.next(0) <= 1_000)
    }
}
