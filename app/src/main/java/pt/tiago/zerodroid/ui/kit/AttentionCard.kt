package pt.tiago.zerodroid.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Amber "needs you" card that links to the Inbox. */
@Composable
fun AttentionCard(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ZcCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        padding = PaddingValues(14.dp),
        border = ZcTokens.attention.copy(alpha = 0.28f),
        background = Brush.linearGradient(
            listOf(ZcTokens.attention.copy(alpha = 0.16f), ZcTokens.attention.copy(alpha = 0.04f)),
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).background(ZcTokens.attention.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(ZcIcons.Alert, null, tint = ZcTokens.attention, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = ZcType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = ZcType.caption.copy(color = ZcTokens.attention.copy(alpha = 0.85f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(ZcIcons.Chevron, null, tint = ZcTokens.attention, modifier = Modifier.size(18.dp))
        }
    }
}
