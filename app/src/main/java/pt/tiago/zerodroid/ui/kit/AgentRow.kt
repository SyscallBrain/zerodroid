package pt.tiago.zerodroid.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Agent activity state. */
enum class AgentState { Working, NeedsYou, Idle, Offline }

private val avatarHues = listOf(
    ZcTokens.accentStart, ZcTokens.accentEnd, ZcTokens.success, ZcTokens.attention, ZcTokens.danger,
)

/** Rounded-square monogram, tinted by a stable hash of the name. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 38.dp) {
    val hue: Color = avatarHues[(name.hashCode() and 0x7fffffff) % avatarHues.size]
    val shape = RoundedCornerShape(size * 0.3f)
    Box(
        modifier.size(size).background(hue.copy(alpha = 0.14f), shape).border(1.dp, hue.copy(alpha = 0.22f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = ZcType.bodyStrong.copy(color = hue, fontSize = (size.value * 0.4f).sp),
        )
    }
}

fun AgentState.pill(): Pair<String, Tone> = when (this) {
    AgentState.Working -> "working" to Tone.Live
    AgentState.NeedsYou -> "needs you" to Tone.Attention
    AgentState.Idle -> "idle" to Tone.Neutral
    AgentState.Offline -> "disabled" to Tone.Neutral
}

@Composable
fun AgentRow(
    alias: String,
    subtitle: String,
    state: AgentState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    pt.tiago.zerodroid.RecomposeProbe("AgentRow")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(alias)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(alias, style = ZcType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = ZcType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        val (label, tone) = state.pill()
        StatusPill(label, tone, pulse = state == AgentState.Working)
        if (trailing != null) trailing()
    }
}
