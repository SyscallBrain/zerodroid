package pt.tiago.zerodroid.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Uppercase section label with an optional trailing action. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = ZcType.label, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = ZcType.caption.copy(color = ZcTokens.accentStart),
                modifier = Modifier.padding(start = 12.dp).clickableNoRipple(onAction),
            )
        }
    }
}

/** Top bar used by pushed screens and tab roots. */
@Composable
fun ZcTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    navigation: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigation != null) navigation() else Spacer(Modifier.width(12.dp))
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (subtitle == null && navigation == null) ZcType.display.copy(fontSize = ZcType.title.fontSize.times(1.2f)) else ZcType.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) subtitle()
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Single-line or multiline input on `raised`, hairline border that turns cyan when focused. */
@Composable
fun ZcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 6,
    mono: Boolean = false,
    leadingIcon: ImageVector? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** Runs on the keyboard's action key (Done, Go…) in place of its default; null keeps the default. */
    onImeAction: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ZcTokens.inputRadius)
    val style: TextStyle = (if (mono) ZcType.mono else ZcType.body).copy(color = if (enabled) ZcTokens.text else ZcTokens.faint)
    Row(
        modifier
            .background(ZcTokens.surface, shape)
            .border(1.dp, if (focused) ZcTokens.accentStart.copy(alpha = 0.45f) else ZcTokens.hairlineStrong, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null, tint = ZcTokens.faint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            val state = rememberSyncedTextFieldState(value, onValueChange)
            if (state.text.isEmpty()) Text(placeholder, style = style.copy(color = ZcTokens.faint), maxLines = 1)
            BasicTextField(
                state = state,
                enabled = enabled,
                lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(maxHeightInLines = maxLines),
                textStyle = style,
                cursorBrush = SolidColor(ZcTokens.accentStart),
                keyboardOptions = keyboardOptions,
                onKeyboardAction = onImeAction?.let { action -> KeyboardActionHandler { action() } },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Slim banner for a degraded connection; never a blocking modal. */
@Composable
fun ConnectionBanner(visible: Boolean, text: String, tone: Tone, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val c = tone.color()
        Row(
            modifier
                .fillMaxWidth()
                .padding(horizontal = ZcTokens.gutter, vertical = 6.dp)
                .background(c.copy(alpha = 0.08f), RoundedCornerShape(ZcTokens.chipRadius))
                .border(1.dp, c.copy(alpha = 0.2f), RoundedCornerShape(ZcTokens.chipRadius))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(tone, pulse = tone == Tone.Live || tone == Tone.Attention)
            Text(text, style = ZcType.caption.copy(color = ZcTokens.text), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (action != null && onAction != null) {
                Text(action, style = ZcType.caption.copy(color = ZcTokens.accentStart), modifier = Modifier.clickableNoRipple(onAction))
            }
        }
    }
}

/** Centered empty state with an icon, a title and a hint. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).background(ZcTokens.surface, RoundedCornerShape(16.dp)).border(1.dp, ZcTokens.hairline, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = ZcTokens.muted, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.heightIn(min = 16.dp))
        Text(title, style = ZcType.heading)
        Spacer(Modifier.heightIn(min = 6.dp))
        Text(hint, style = ZcType.small, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.heightIn(min = 20.dp))
            action()
        }
    }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = null, indication = null, onClick = onClick)
