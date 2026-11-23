package pt.tiago.zerodroid.ui.cron

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.rpc.proto.SchedulerSettings
import pt.tiago.zerodroid.ui.kit.NoSheetInsets
import pt.tiago.zerodroid.ui.kit.rememberFullSheetState
import pt.tiago.zerodroid.ui.kit.SheetBody
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcSwitch
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

const val SETTINGS_RESTART_NOTE = "Applies after the daemon restarts."

/** The daemon's global scheduler switches (`[scheduler]` in config). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerSheet(settings: SchedulerSettings?, forbidden: Boolean, saving: Boolean, onChange: (SchedulerSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFullSheetState(),
        containerColor = ZcTokens.surface,
        scrimColor = ZcTokens.scrim,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).background(ZcTokens.hairlineStrong, RoundedCornerShape(2.dp))) },
        contentWindowInsets = NoSheetInsets,
    ) {
        SheetBody {
            Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = ZcTokens.gutter, vertical = 12.dp)) {
                Text("Scheduler", style = ZcType.title)
                Spacer(Modifier.height(12.dp))
                when {
                    forbidden -> Text("This device can't read the scheduler settings.", style = ZcType.body.copy(color = ZcTokens.muted))
                    settings == null -> SkeletonLines(3)
                    else -> {
                        val edit = !saving
                        SwitchRow("Run jobs on schedule", "Off: jobs only run when you start them.", settings.enabled, edit) { onChange(settings.copy(enabled = it)) }
                        SwitchRow("Catch up on startup", "Run jobs that were due while the daemon was down.", settings.catchUpOnStartup, edit) {
                            onChange(settings.copy(catchUpOnStartup = it))
                        }
                        var history by remember { mutableStateOf(settings.maxRunHistory.toString()) }
                        LaunchedEffect(settings.maxRunHistory) { if (history.toIntOrNull() != settings.maxRunHistory) history = settings.maxRunHistory.toString() }
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Runs kept per job", style = ZcType.body)
                                Text("Older runs are dropped from the history.", style = ZcType.caption)
                            }
                            Spacer(Modifier.width(12.dp))
                            ZcTextField(
                                history,
                                { v -> history = v.filter(Char::isDigit).take(5) },
                                "50",
                                mono = true,
                                enabled = edit,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(88.dp),
                            )
                            history.toIntOrNull()?.takeIf { it > 0 && it != settings.maxRunHistory }?.let { n ->
                                Spacer(Modifier.width(8.dp))
                                ZcGhostButton("Save", compact = true, enabled = edit) { onChange(settings.copy(maxRunHistory = n)) }
                            }
                        }
                        Text(SETTINGS_RESTART_NOTE, style = ZcType.caption.copy(color = ZcTokens.faint), modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, caption: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ZcType.body)
            Text(caption, style = ZcType.caption)
        }
        ZcSwitch(checked, onChange, enabled = enabled)
    }
}
