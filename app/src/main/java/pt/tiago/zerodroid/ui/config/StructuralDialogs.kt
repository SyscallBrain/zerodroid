package pt.tiago.zerodroid.ui.config

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.config.ConfigEditor
import pt.tiago.zerodroid.data.config.ConfigForbiddenException
import pt.tiago.zerodroid.rpc.proto.AgentDeletePreview
import pt.tiago.zerodroid.rpc.userMessage
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

private fun haptic(view: android.view.View, reject: Boolean) {
    val c = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (reject) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONFIRM
    } else HapticFeedbackConstants.VIRTUAL_KEY
    view.performHapticFeedback(c)
}

/**
 * Confirmed structural edits. Each one writes immediately on confirm, so each has its own
 * confirmation; deletes require typing the entry's name.
 */
@Composable
fun StructuralDialog(
    request: StructuralRequest,
    onDismiss: () -> Unit,
    onCreated: (ConfigRoute) -> Unit,
    onDeleted: () -> Unit,
) {
    val editor = ZeroDroidConnection.config
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var name by remember(request) {
        mutableStateOf(if (request is StructuralRequest.Rename) request.from else "")
    }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var busy by remember(request) { mutableStateOf(false) }
    var preview by remember(request) { mutableStateOf<AgentDeletePreview?>(null) }
    var warnings by remember(request) { mutableStateOf<List<String>?>(null) }

    if (request is StructuralRequest.Delete && request.isAgent) {
        LaunchedEffect(request) {
            runCatching { editor.previewAgentDelete(request.key) }
                .onSuccess { preview = it }
                .onFailure { error = it.userMessage() }
        }
    }

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: ConfigForbiddenException) {
                onDismiss()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }

    val title: String
    val confirmLabel: String
    val danger: Boolean
    val canConfirm: Boolean
    val onConfirm: () -> Unit
    when (request) {
        is StructuralRequest.Add -> {
            val noun = request.mapPath.substringAfterLast('.').removeSuffix("s")
            title = "New $noun"
            confirmLabel = "Create"
            danger = false
            val invalid = ConfigEditor.validateAlias(name.trim(), request.existing)
            canConfirm = invalid == null
            onConfirm = {
                run {
                    editor.addEntry(request.mapPath, name.trim())
                    haptic(view, reject = false)
                    onCreated(ConfigRoute.Entry(request.sectionKey, "${request.mapPath}.${name.trim()}", name.trim()))
                }
            }
        }
        is StructuralRequest.Rename -> {
            title = "Rename ${request.from}"
            confirmLabel = "Rename"
            danger = false
            canConfirm = name.trim() != request.from && ConfigEditor.validateAlias(name.trim(), request.existing) == null
            onConfirm = { run { editor.renameEntry(request.mapPath, request.from, name.trim()); onDeleted() } }
        }
        is StructuralRequest.Delete -> {
            title = "Delete ${request.key}?"
            confirmLabel = "Delete"
            danger = true
            canConfirm = name.trim() == request.key && (!request.isAgent || preview?.allowed == true) && warnings == null
            onConfirm = {
                run {
                    if (request.isAgent) {
                        val r = editor.deleteAgent(request.key)
                        if (!r.deleted) error(r.error ?: "The daemon did not delete ${request.key}")
                        haptic(view, reject = true)
                        if (r.warnings.isEmpty()) onDeleted() else warnings = r.warnings
                    } else {
                        editor.deleteEntry(request.mapPath, request.key)
                        haptic(view, reject = true)
                        onDeleted()
                    }
                }
            }
        }
        is StructuralRequest.ClearSecret -> {
            title = "Clear ${request.label}?"
            confirmLabel = "Clear"
            danger = true
            canConfirm = true
            onConfirm = { run { editor.clearSecret(request.path); haptic(view, reject = true); onDeleted() } }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = ZcTokens.raised,
        title = { Text(title, style = ZcType.heading) },
        text = {
            Column {
                when (request) {
                    is StructuralRequest.Add, is StructuralRequest.Rename -> {
                        ZcTextField(name, { name = it }, "name", mono = true, modifier = Modifier.fillMaxWidth())
                        val existing = (request as? StructuralRequest.Add)?.existing ?: (request as StructuralRequest.Rename).existing
                        val hint = if (name.isNotEmpty()) ConfigEditor.validateAlias(name.trim(), existing) else null
                        if (hint != null && !(request is StructuralRequest.Rename && name.trim() == request.from)) {
                            Text(hint, style = ZcType.caption.copy(color = ZcTokens.attention), modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    is StructuralRequest.Delete -> {
                        if (request.isAgent) {
                            val p = preview
                            when {
                                warnings != null -> {
                                    Text("${request.key} was deleted with warnings:", style = ZcType.body)
                                    warnings!!.forEach { Text("• $it", style = ZcType.small.copy(color = ZcTokens.attention)) }
                                }
                                p == null && error == null -> SkeletonLines(3)
                                p != null && !p.allowed -> {
                                    Text("The daemon will not delete this agent yet:", style = ZcType.body)
                                    p.blockers.forEach { Text("• $it", style = ZcType.small.copy(color = ZcTokens.danger)) }
                                }
                                p != null -> {
                                    Text("This removes the agent from the configuration.", style = ZcType.body.copy(color = ZcTokens.muted))
                                    if (p.scrubs.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text("References that will be removed:", style = ZcType.caption)
                                        p.scrubs.forEach { Text("• $it", style = ZcType.monoSmall.copy(color = ZcTokens.muted)) }
                                    }
                                    if (p.ownedState.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text("Its own state goes with it:", style = ZcType.caption)
                                        p.ownedState.forEach { Text("• $it", style = ZcType.monoSmall.copy(color = ZcTokens.muted)) }
                                    }
                                }
                            }
                        } else {
                            Text(
                                "This removes ${request.key} from ${request.mapPath} on the daemon, with all its settings.",
                                style = ZcType.body.copy(color = ZcTokens.muted),
                            )
                        }
                        if (warnings == null && (!request.isAgent || preview?.allowed == true)) {
                            Spacer(Modifier.height(12.dp))
                            Text("Type ${request.key} to confirm", style = ZcType.caption)
                            Spacer(Modifier.height(6.dp))
                            ZcTextField(name, { name = it }, request.key, mono = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is StructuralRequest.ClearSecret -> Text(
                        "The daemon will no longer have this secret. This is written immediately.",
                        style = ZcType.body.copy(color = ZcTokens.muted),
                    )
                }
                error?.let { Text(it, style = ZcType.caption.copy(color = ZcTokens.danger), modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (warnings != null) {
                TextButton(onClick = onDeleted) { Text("Done", style = ZcType.button.copy(color = ZcTokens.accentStart)) }
            } else {
                TextButton(enabled = canConfirm && !busy, onClick = onConfirm) {
                    Text(if (busy) "…" else confirmLabel, style = ZcType.button.copy(color = if (!canConfirm || busy) ZcTokens.faint else if (danger) ZcTokens.danger else ZcTokens.accentStart))
                }
            }
        },
        dismissButton = {
            if (warnings == null) TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) }
        },
    )
}
