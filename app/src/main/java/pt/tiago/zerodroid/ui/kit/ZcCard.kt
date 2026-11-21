package pt.tiago.zerodroid.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.ui.theme.ZcTokens

/** Surface card: 16 dp radius, hairline border, a faint top-lit gradient. */
@Composable
fun ZcCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    border: Color = ZcTokens.hairline,
    background: Brush = Brush.verticalGradient(listOf(ZcTokens.raised, ZcTokens.surface)),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(ZcTokens.cardRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}
