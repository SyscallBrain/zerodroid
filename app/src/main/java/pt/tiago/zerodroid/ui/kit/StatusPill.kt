package pt.tiago.zerodroid.ui.kit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Semantic tone for status indicators. */
enum class Tone { Live, Attention, Success, Danger, Neutral }

fun Tone.color(): Color = when (this) {
    Tone.Live -> ZcTokens.accentStart
    Tone.Attention -> ZcTokens.attention
    Tone.Success -> ZcTokens.success
    Tone.Danger -> ZcTokens.danger
    Tone.Neutral -> ZcTokens.muted
}

/** A small dot; pulses when [pulse] (live work, connecting). */
@Composable
fun StatusDot(tone: Tone, modifier: Modifier = Modifier, pulse: Boolean = false, size: androidx.compose.ui.unit.Dp = 7.dp) {
    val alpha = if (pulse) {
        val t = rememberInfiniteTransition(label = "dot")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dotA")
        a
    } else 1f
    pt.tiago.zerodroid.RecomposeProbe(if (pulse) "StatusDot(pulse)" else "StatusDot")
    Box(modifier.size(size).alpha(alpha).background(tone.color(), CircleShape))
}

/** Tinted capsule with a status dot. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier, dot: Boolean = true, pulse: Boolean = false) {
    val c = tone.color()
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .background(c.copy(alpha = if (tone == Tone.Neutral) 0.08f else 0.12f), shape)
            .border(1.dp, c.copy(alpha = 0.18f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            StatusDot(tone, pulse = pulse, size = 6.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = ZcType.caption.copy(color = if (tone == Tone.Neutral) ZcTokens.muted else c), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
