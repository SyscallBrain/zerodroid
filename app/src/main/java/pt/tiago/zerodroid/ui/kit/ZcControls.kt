package pt.tiago.zerodroid.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Segmented control: the selected segment is a raised chip with a cyan hairline. */
@Composable
fun ZcSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(ZcTokens.inputRadius)
    Row(
        modifier.fillMaxWidth().background(ZcTokens.background, shape).border(1.dp, ZcTokens.hairline, shape).padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) ZcTokens.accentStart.copy(alpha = 0.12f) else Color.Transparent, label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(ZcTokens.chipRadius))
                    .background(bg)
                    .border(1.dp, if (on) ZcTokens.accentStart.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(ZcTokens.chipRadius))
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = ZcType.caption.copy(color = if (on) ZcTokens.accentStart else ZcTokens.muted))
            }
        }
    }
}

/** Switch: gradient track when on, a white thumb that slides. */
@Composable
fun ZcSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val x by animateDpAsState(if (checked) 20.dp else 2.dp, label = "thumb")
    val track = RoundedCornerShape(50)
    Box(
        modifier
            .width(44.dp)
            .height(26.dp)
            .clip(track)
            .then(if (checked) Modifier.background(ZcTokens.accentHorizontal) else Modifier.background(ZcTokens.raised))
            .border(1.dp, ZcTokens.hairline, track)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = x)
                .align(Alignment.CenterStart)
                .size(22.dp)
                .background(if (checked) ZcTokens.text else ZcTokens.muted, CircleShape),
        )
    }
}
