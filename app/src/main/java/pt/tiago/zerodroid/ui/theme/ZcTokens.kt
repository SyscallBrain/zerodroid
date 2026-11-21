package pt.tiago.zerodroid.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Midnight design tokens (spec §5). Every color, radius and spacing in the app
 * comes from here; screens never use raw color literals.
 */
object ZcTokens {
    // ── Palette ──────────────────────────────────────────────────────────
    val background: Color = Color(0xFF07080B)
    val surface: Color = Color(0xFF0F1116)
    val raised: Color = Color(0xFF151821)
    val text: Color = Color(0xFFE7E9EE)
    val muted: Color = Color(0xFF8A8F98)
    val faint: Color = Color(0xFF6B7280)
    val accentStart: Color = Color(0xFF22D3EE)
    val accentEnd: Color = Color(0xFF3B82F6)
    val attention: Color = Color(0xFFF5A524)
    val success: Color = Color(0xFF34D399)
    val danger: Color = Color(0xFFFB7185)
    val hairline: Color = Color.White.copy(alpha = 0.06f)
    val hairlineStrong: Color = Color.White.copy(alpha = 0.10f)
    /** Text on the accent gradient. */
    val onAccent: Color = Color(0xFF04121A)
    val scrim: Color = Color.Black.copy(alpha = 0.6f)

    val accent: Brush get() = Brush.linearGradient(listOf(accentStart, accentEnd))
    val accentHorizontal: Brush get() = Brush.horizontalGradient(listOf(accentStart, accentEnd))

    // ── Radii ────────────────────────────────────────────────────────────
    val cardRadius: Dp = 16.dp
    val inputRadius: Dp = 12.dp
    val chipRadius: Dp = 10.dp

    // ── Spacing ──────────────────────────────────────────────────────────
    val gutter: Dp = 20.dp
    val gap: Dp = 10.dp
}
