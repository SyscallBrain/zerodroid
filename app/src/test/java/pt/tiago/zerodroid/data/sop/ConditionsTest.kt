package pt.tiago.zerodroid.data.sop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConditionsTest {
    private val ops = listOf(">", ">=", "<", "<=", "==", "!=", "contains")

    @Test
    fun jsonPathFormLongestOperatorWins() {
        val p = parseCondition("$.value >= 85", ops)
        assertEquals("value", p.path)
        assertEquals(">=", p.op)
        assertEquals("85", p.value)
    }

    @Test
    fun directScalarHasNoPath() {
        val p = parseCondition("> 3", ops)
        assertNull(p.path)
        assertEquals(">", p.op)
        assertEquals("3", p.value)
    }

    @Test
    fun unparseableKeepsRaw() {
        val p = parseCondition("whatever", ops)
        assertEquals("", p.op)
        assertEquals("whatever", p.raw)
        assertEquals("", parseCondition(null, ops).raw)
    }

    @Test
    fun buildRoundTrips() {
        assertNull(buildCondition(null, "", "x"))
        assertEquals("$.chuva_mm > 1", buildCondition("chuva_mm", ">", "1"))
        assertEquals("> 3", buildCondition(null, ">", "3"))
        assertEquals("> 3", buildCondition("  ", ">", "3"))
    }
}
