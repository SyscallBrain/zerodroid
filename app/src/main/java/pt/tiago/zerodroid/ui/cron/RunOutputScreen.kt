package pt.tiago.zerodroid.ui.cron

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** A run's full output: monospace, selectable, copyable. */
@Suppress("DEPRECATION")
@Composable
fun RunOutputScreen(title: String, text: String, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ZcTopBar(
            title,
            navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) },
            actions = { ZcIconButton(ZcIcons.Copy, "Copy output", onClick = { clipboard.setText(AnnotatedString(text)) }) },
        )
        SelectionContainer(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = ZcTokens.gutter).windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text(text.ifEmpty { "(no output)" }, style = ZcType.mono.copy(color = ZcTokens.text))
        }
    }
}
