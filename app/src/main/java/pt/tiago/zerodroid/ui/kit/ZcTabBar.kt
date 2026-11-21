package pt.tiago.zerodroid.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

data class ZcTab(val label: String, val icon: ImageVector, val badge: Int = 0)

/** 66 dp bottom bar with a top hairline; the selected icon glows cyan, badges are amber pills. */
@Composable
fun ZcTabBar(tabs: List<ZcTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(ZcTokens.background)
            .drawBehind { drawLine(ZcTokens.hairlineStrong, Offset(0f, 0f), Offset(size.width, 0f), 1f) }
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(Modifier.fillMaxWidth().height(66.dp)) {
            tabs.forEachIndexed { i, tab ->
                val on = i == selected
                val tint by animateColorAsState(if (on) ZcTokens.accentStart else ZcTokens.faint, label = "tab")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            if (on) {
                                Box(
                                    Modifier.size(36.dp).align(Alignment.Center).background(
                                        Brush.radialGradient(listOf(ZcTokens.accentStart.copy(alpha = 0.28f), ZcTokens.accentStart.copy(alpha = 0f))),
                                    ),
                                )
                            }
                            Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(24.dp).align(Alignment.Center))
                            if (tab.badge > 0) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 10.dp, y = (-4).dp)
                                        .widthIn(min = 17.dp)
                                        .height(17.dp)
                                        .background(ZcTokens.attention, RoundedCornerShape(50))
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (tab.badge > 99) "99+" else tab.badge.toString(),
                                        style = ZcType.label.copy(color = ZcTokens.onAccent, fontSize = 10.sp, letterSpacing = 0.sp),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(tab.label, style = ZcType.caption.copy(color = if (on) ZcTokens.text else ZcTokens.faint, fontSize = 11.sp))
                    }
                }
            }
        }
    }
}
