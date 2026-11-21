package pt.tiago.zerodroid.rpc

import org.junit.Assert.assertEquals
import org.junit.Test

class CloseCodeTest {
    @Test
    fun reservedCodesAreAnsweredWithNormalClosure() {
        assertEquals(1000, replyCloseCode(1005))
        assertEquals(1000, replyCloseCode(1006))
        assertEquals(1000, replyCloseCode(1015))
        assertEquals(1001, replyCloseCode(1001))
        assertEquals(4000, replyCloseCode(4000))
    }
}
