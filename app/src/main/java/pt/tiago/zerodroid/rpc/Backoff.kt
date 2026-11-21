package pt.tiago.zerodroid.rpc

import kotlin.random.Random

class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 30_000,
    private val random: Random = Random.Default,
) {
    fun next(attempt: Int): Long {
        val cap = minOf(maxMs, baseMs shl attempt.coerceIn(0, 30))
        return random.nextLong(0, cap + 1)
    }
    fun reset() = Unit
}
