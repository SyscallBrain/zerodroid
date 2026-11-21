package pt.tiago.zerodroid.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

enum class ButtonKind { Primary, Ghost, Danger, Attention }

@Composable
private fun ZcButtonBase(
    text: String,
    kind: ButtonKind,
    modifier: Modifier,
    enabled: Boolean,
    icon: ImageVector?,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(ZcTokens.inputRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg: Brush = when (kind) {
        ButtonKind.Primary -> ZcTokens.accent
        ButtonKind.Ghost -> SolidColor(ZcTokens.surface)
        ButtonKind.Danger -> SolidColor(ZcTokens.danger.copy(alpha = 0.10f))
        ButtonKind.Attention -> SolidColor(ZcTokens.attention.copy(alpha = 0.12f))
    }
    val fg = when (kind) {
        ButtonKind.Primary -> ZcTokens.onAccent
        ButtonKind.Ghost -> ZcTokens.text
        ButtonKind.Danger -> ZcTokens.danger
        ButtonKind.Attention -> ZcTokens.attention
    }
    val border by animateColorAsState(
        when (kind) {
            ButtonKind.Primary -> Color.Transparent
            ButtonKind.Ghost -> if (pressed) ZcTokens.hairlineStrong else ZcTokens.hairline.copy(alpha = 0.12f)
            ButtonKind.Danger -> ZcTokens.danger.copy(alpha = 0.25f)
            ButtonKind.Attention -> ZcTokens.attention.copy(alpha = 0.3f)
        },
        label = "border",
    )
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = if (compact) 36.dp else 46.dp)
            .scale(if (pressed && enabled) 0.98f else 1f)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 8.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(if (compact) 16.dp else 18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = ZcType.button.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Accent-gradient button with dark text. */
@Composable
fun ZcPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) = ZcButtonBase(text, ButtonKind.Primary, modifier, enabled, icon, compact, onClick)

/** Quiet button: surface fill, hairline border. */
@Composable
fun ZcGhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) = ZcButtonBase(text, ButtonKind.Ghost, modifier, enabled, icon, compact, onClick)

@Composable
fun ZcDangerButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) = ZcButtonBase(text, ButtonKind.Danger, modifier, enabled, icon, compact, onClick)

/** Round icon button used in top bars. */
@Composable
fun ZcIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = ZcTokens.text,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else ZcTokens.faint, modifier = Modifier.size(22.dp))
    }
}
