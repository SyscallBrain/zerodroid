package pt.tiago.zerodroid.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.data.ChatItem
import pt.tiago.zerodroid.data.ToolStatus
import pt.tiago.zerodroid.ui.kit.CodeBlock
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

@Composable
private fun Spinner(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "r")
    Icon(ZcIcons.Refresh, null, tint = ZcTokens.accentStart, modifier = modifier.rotate(r))
}

/** Compact tool call: status, mono name, first argument, duration. Tap to expand args and output. */
@Composable
fun ToolChip(item: ChatItem.Tool, modifier: Modifier = Modifier) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val shape = RoundedCornerShape(ZcTokens.chipRadius)
    Column(
        modifier
            .animateContentSize()
            .then(if (open) Modifier.fillMaxWidth() else Modifier.widthIn(max = 340.dp))
            .clip(shape)
            .background(ZcTokens.surface)
            .border(1.dp, ZcTokens.hairline, shape)
            .clickable { open = !open },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            when (item.status) {
                ToolStatus.Running -> Spinner(Modifier.size(14.dp))
                ToolStatus.Ok -> Icon(ZcIcons.Check, null, tint = ZcTokens.success, modifier = Modifier.size(14.dp))
                ToolStatus.Failed -> Icon(ZcIcons.Close, null, tint = ZcTokens.danger, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(item.name, style = ZcType.monoSmall.copy(color = ZcTokens.text))
            val firstLine = item.args.lineSequence().firstOrNull().orEmpty()
            if (firstLine.isNotBlank() && !open) {
                Spacer(Modifier.width(6.dp))
                Text(firstLine, style = ZcType.monoSmall.copy(color = ZcTokens.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            if (open) Spacer(Modifier.weight(1f))
            item.durationMs?.let {
                Spacer(Modifier.width(8.dp))
                Text(formatDuration(it), style = ZcType.monoSmall.copy(color = ZcTokens.faint))
            }
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                if (item.args.isNotBlank()) {
                    Text("ARGUMENTS", style = ZcType.label)
                    Spacer(Modifier.height(4.dp))
                    CodeBlock(item.args, maxLines = 30)
                    Spacer(Modifier.height(8.dp))
                }
                Text("OUTPUT", style = ZcType.label)
                Spacer(Modifier.height(4.dp))
                when {
                    item.output != null -> CodeBlock(item.output.ifBlank { "(empty)" }, maxLines = 80)
                    item.status == ToolStatus.Running -> Text("Running…", style = ZcType.caption)
                    else -> Text("No output recorded", style = ZcType.caption)
                }
            }
        }
    }
}

@Composable
fun ThoughtRow(item: ChatItem.Thought, modifier: Modifier = Modifier) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(modifier.animateContentSize().clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (item.streaming) ZcTokens.accentStart else ZcTokens.faint, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(8.dp))
            Text(if (item.streaming) "Thinking…" else "Thought", style = ZcType.caption.copy(color = if (item.streaming) ZcTokens.accentStart else ZcTokens.faint))
            Icon(ZcIcons.ChevronDown, null, tint = ZcTokens.faint, modifier = Modifier.size(14.dp).rotate(if (open) 180f else 0f))
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text(item.text.trim(), style = ZcType.small.copy(color = ZcTokens.faint), modifier = Modifier.padding(start = 14.dp))
        }
    }
}
