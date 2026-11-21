package pt.tiago.zerodroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAlivePolicyTest {
    @Test fun holdsWhileTurnRunning() = assertTrue(shouldHoldForeground(setOf("s1"), 0))
    @Test fun holdsWhileApprovalPending() = assertTrue(shouldHoldForeground(emptySet(), 1))
    @Test fun releasesWhenIdle() = assertFalse(shouldHoldForeground(emptySet(), 0))

    @Test
    fun ongoingTextCases() {
        assertEquals("researcher is working…", ongoingText(listOf("researcher")))
        assertEquals("researcher and coder are working…", ongoingText(listOf("researcher", "coder")))
        assertEquals("3 agents are working…", ongoingText(listOf("a", "b", "c")))
    }
}
