package pt.tiago.zerodroid.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SasTest {
    @Test
    fun sasMatchesDaemonVector() {
        assertEquals("ECDA-46C8", enrollmentSas("482913", "AB".repeat(32)))
    }

    @Test
    fun sasTrimsAndLowercases() {
        assertEquals("ECDA-46C8", enrollmentSas(" 482913 ", "ab".repeat(32)))
    }

    @Test
    fun sha256HexVector() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            sha256Hex("hello".toByteArray()),
        )
    }
}
