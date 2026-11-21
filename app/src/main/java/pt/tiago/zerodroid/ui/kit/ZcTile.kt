package pt.tiago.zerodroid.ui.kit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Metric tile: caps label, big value with an optional muted suffix, optional footer. */
@Composable
fun ZcTile(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    /** A second line under the value, for labels too long to sit beside it. */
    caption: String? = null,
    stale: Boolean = false,
    loading: Boolean = false,
    onClick: (() -> Unit)? = null,
    footer: @Composable (() -> Unit)? = null,
) {
    pt.tiago.zerodroid.RecomposeProbe("ZcTile")
    ZcCard(modifier = modifier.heightIn(min = 104.dp), onClick = onClick) {
        Text(label.uppercase(), style = ZcType.label)
        Spacer(Modifier.height(10.dp))
        if (loading && value == null) {
            Skeleton(Modifier.width(72.dp).height(26.dp))
        } else {
            AnimatedContent(
                targetState = value ?: "—",
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "tile",
            ) { v ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        v,
                        style = ZcType.metric.copy(color = if (stale || value == null) ZcTokens.faint else ZcTokens.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (suffix != null && value != null) {
                        Spacer(Modifier.width(5.dp))
                        Text(suffix, style = ZcType.small.copy(color = ZcTokens.faint), modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
                    }
                }
            }
        }
        if (caption != null && value != null) {
            Text(caption, style = ZcType.caption.copy(color = ZcTokens.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (footer != null) {
            Spacer(Modifier.height(10.dp))
            footer()
        }
    }
}
