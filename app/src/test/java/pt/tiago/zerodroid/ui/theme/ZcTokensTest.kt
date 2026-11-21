package pt.tiago.zerodroid.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import org.junit.Test

class ZcTokensTest {

    @Test
    fun tokens_have_spec_values() {
        // Colors — spec §5 Midnight palette
        assertEquals(Color(0xFF07080B), ZcTokens.background)
        assertEquals(Color(0xFF0F1116), ZcTokens.surface)
        assertEquals(Color(0xFF151821), ZcTokens.raised)
        assertEquals(Color(0xFFE7E9EE), ZcTokens.text)
        assertEquals(Color(0xFF8A8F98), ZcTokens.muted)
        assertEquals(Color(0xFF6B7280), ZcTokens.faint)
        assertEquals(Color(0xFF22D3EE), ZcTokens.accentStart)
        assertEquals(Color(0xFF3B82F6), ZcTokens.accentEnd)
        assertEquals(Color(0xFFF5A524), ZcTokens.attention)
        assertEquals(Color(0xFF34D399), ZcTokens.success)
        assertEquals(Color(0xFFFB7185), ZcTokens.danger)

        // Hairline: White at 6% alpha
        val expectedHairline = Color.White.copy(alpha = 0.06f)
        assertEquals(expectedHairline, ZcTokens.hairline)

        // Radii
        assertEquals(16.dp, ZcTokens.cardRadius)
        assertEquals(12.dp, ZcTokens.inputRadius)
        assertEquals(10.dp, ZcTokens.chipRadius)
    }
}