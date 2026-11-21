package pt.tiago.zerodroid.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.ui.theme.ZcTokens

/** Shimmer block over `raised`. Use instead of spinners. */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ZcTokens.raised)
            .drawWithContent {
                val w = size.width
                drawRect(
                    Brush.linearGradient(
                        listOf(ZcTokens.raised, ZcTokens.hairlineStrong, ZcTokens.raised),
                        start = Offset(w * x - w * 0.6f, 0f),
                        end = Offset(w * x, size.height),
                    ),
                )
            },
    )
}

/** A few text-line skeletons, the last one shorter. */
@Composable
fun SkeletonLines(lines: Int = 3, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(lines) { i ->
            Skeleton(Modifier.fillMaxWidth(if (i == lines - 1) 0.55f else 1f).height(14.dp))
        }
    }
}
