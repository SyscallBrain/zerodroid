package pt.tiago.zerodroid.ui.canvas

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

@Composable
internal fun RenameCanvasDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZcTokens.raised,
        title = { Text("Rename canvas", style = ZcType.heading) },
        text = { ZcTextField(name, { name = it }, "Name") },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text("Rename", style = ZcType.button.copy(color = ZcTokens.accentStart))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
    )
}

@Composable
internal fun DeleteCanvasDialog(name: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZcTokens.raised,
        title = { Text("Delete this canvas?", style = ZcType.heading) },
        text = { Text("\"$name\" and all its frames are removed from this phone.", style = ZcType.body.copy(color = ZcTokens.muted)) },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", style = ZcType.button.copy(color = ZcTokens.danger)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
    )
}

internal fun isoOf(epochMs: Long): String = java.time.Instant.ofEpochMilli(epochMs).toString()

internal fun framesLabel(n: Int) = if (n == 1) "1 frame" else "$n frames"
