package pt.tiago.zerodroid.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.data.ModelLabel
import pt.tiago.zerodroid.ui.kit.StatusDot
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.clickableNoRipple
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Routed ids like `deepseek/deepseek-v3.2` show only the last segment; the provider is in the session sheet. */
internal fun shortModelName(model: String): String = model.substringAfterLast('/').ifBlank { model }

/** How full the context window is, 0..1, or null when the daemon hasn't reported both numbers. */
internal fun contextFraction(used: Long?, max: Long?): Float? =
    if (used != null && max != null && max > 0) (used.toFloat() / max).coerceIn(0f, 1f) else null

/** From here the ring turns to the attention colour. */
internal const val CONTEXT_WARN = 0.85f

/**
 * The model answering and its reasoning level, just above the composer. It stays put while a turn
 * runs (the header shows what the agent is doing). Tapping it opens the session sheet.
 */
@Composable
internal fun ModelStrip(model: ModelLabel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickableNoRipple(onClick).padding(start = 28.dp, end = 28.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model.overridden) {
            StatusDot(Tone.Live, size = 6.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            shortModelName(model.model),
            style = ZcType.monoSmall.copy(color = ZcTokens.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        model.reasoning.label?.let {
            Text(" · $it", style = ZcType.monoSmall.copy(color = ZcTokens.faint), maxLines = 1)
        }
    }
}

/** A thin ring drawn around [content] (the send button) showing how full the context is. */
@Composable
internal fun ContextRing(fraction: Float?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val target = fraction ?: 0f
    val sweep by animateFloatAsState(target, tween(400), label = "context")
    val pct = (target * 100).toInt()
    Box(
        modifier.then(if (fraction != null) Modifier.semantics { contentDescription = "Context $pct% used" } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (fraction != null) {
            val track = ZcTokens.hairlineStrong
            val arc = if (target >= CONTEXT_WARN) ZcTokens.attention else ZcTokens.accentStart
            Canvas(Modifier.matchParentSize()) {
                val w = 2.dp.toPx()
                val inset = w / 2
                val size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(track, 0f, 360f, false, topLeft, size, style = Stroke(w))
                // A sliver is still visible at 1% so the ring reads as "almost empty", not "missing".
                if (sweep > 0f) drawArc(arc, -90f, (sweep * 360f).coerceAtLeast(4f), false, topLeft, size, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        content()
    }
}
