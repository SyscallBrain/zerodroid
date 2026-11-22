package pt.tiago.zerodroid.ui.sop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

// Temporary stand-ins replaced by Tasks 8 (StepSheet), 9 (SopSettingsTab) and 10 (RunsTab, SopRunEntry).

@Composable
fun StepSheet(vm: SopEditorViewModel, s: EditorState, n: Int) = Unit

@Composable
fun SopSettingsTab(vm: SopEditorViewModel, s: EditorState) = Column(Modifier.padding(ZcTokens.gutter)) { Text("Settings", style = ZcType.small) }

@Composable
fun RunsTab(name: String, isNew: Boolean, onOpenRun: (String) -> Unit, onEnableEngine: () -> Unit) =
    Column(Modifier.padding(ZcTokens.gutter)) { Text("Runs", style = ZcType.small) }

@Composable
fun SopRunEntry(name: String, runId: String, onBack: () -> Unit, onEnableEngine: () -> Unit) {
    ZcTopBar(name, Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxSize(), navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) })
}
