package pt.tiago.zerodroid.ui.sop

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons

// Entry points for the editor and live run screens. Replaced by the real screens in later tasks
// (editor: Task 7, run: Task 10); kept as one file so SopHost never changes shape.

@Composable
fun SopEditorEntry(name: String?, onBack: () -> Unit, onReplace: (SopRoute) -> Unit, onPush: (SopRoute) -> Unit, onEnableEngine: () -> Unit) {
    ZcTopBar(name ?: "New workflow", Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxSize(), navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) })
}

@Composable
fun SopRunEntry(name: String, runId: String, onBack: () -> Unit, onEnableEngine: () -> Unit) {
    ZcTopBar(name, Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxSize(), navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) })
}
