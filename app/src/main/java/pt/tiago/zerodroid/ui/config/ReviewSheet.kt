package pt.tiago.zerodroid.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.config.ApplyResult
import pt.tiago.zerodroid.data.config.ReloadGate
import pt.tiago.zerodroid.data.config.diffLine
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.proto.ApplyOutcome
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.color
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

private fun outcomeText(o: ApplyOutcome) = when (o) {
    ApplyOutcome.AppliedLive -> "Applied live" to Tone.Success
    ApplyOutcome.QueuedForReload -> "Applies after reload" to Tone.Attention
    ApplyOutcome.Rejected -> "Rejected" to Tone.Danger
    ApplyOutcome.Pending, ApplyOutcome.Unknown -> "Pending" to Tone.Neutral
}

/** Review the draft, apply it in one batch, then show what the daemon did with each change. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(onDismiss: () -> Unit) {
    val editor = ZeroDroidConnection.config
    val entries by editor.draft.entries.collectAsStateWithLifecycle()
    val applying by editor.applying.collectAsStateWithLifecycle()
    val report by editor.lastApply.collectAsStateWithLifecycle()
    val readOnly by editor.readOnly.collectAsStateWithLifecycle()
    val conn by ZeroDroidConnection.state.collectAsStateWithLifecycle()
    val sections by editor.sections.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmed by remember { mutableStateOf(emptySet<String>()) }
    var armed by remember { mutableStateOf(false) }
    var discardAll by remember { mutableStateOf(false) }
    var gate by remember { mutableStateOf<ReloadGate>(ReloadGate.NotNeeded) }
    var confirmReload by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(600); armed = true }
    // Results from an earlier apply are not shown when a new review starts.
    LaunchedEffect(Unit) { if (entries.isNotEmpty()) editor.dismissReport() }
    val showResults = entries.isEmpty() && report?.result is ApplyResult.Ok
    LaunchedEffect(showResults, report) { if (showResults) gate = editor.reloadGate() }

    var reloadError by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = { if (!applying) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !applying }),
        containerColor = ZcTokens.surface,
        scrimColor = ZcTokens.scrim,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).background(ZcTokens.hairlineStrong, RoundedCornerShape(2.dp))) },
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = ZcTokens.gutter).padding(bottom = 16.dp)) {
            Text(if (showResults) "Applied" else "Review changes", style = ZcType.title, modifier = Modifier.padding(vertical = 12.dp))
            if (showResults) {
                val outcomes = report!!.outcomes
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(outcomes.entries.toList(), key = { it.key }) { (path, o) ->
                        val (label, tone) = outcomeText(o)
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (tone == Tone.Danger) ZcIcons.Close else ZcIcons.Check, null, tint = tone.color(), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(path, style = ZcType.monoSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(label, style = ZcType.caption.copy(color = tone.color()))
                        }
                    }
                    if (outcomes.isEmpty()) item("saved") { Text("Saved. The daemon reported no per-setting status.", style = ZcType.small) }
                }
                when (val g = gate) {
                    ReloadGate.NotNeeded -> Unit
                    is ReloadGate.Blocked -> {
                        Spacer(Modifier.height(12.dp))
                        ZcGhostButton("Reload now", enabled = false, modifier = Modifier.fillMaxWidth()) {}
                        Text("${g.running} turn${if (g.running == 1) " is" else "s are"} running. Reload when they finish.", style = ZcType.caption.copy(color = ZcTokens.attention), modifier = Modifier.padding(top = 6.dp))
                    }
                    ReloadGate.Allowed -> {
                        Spacer(Modifier.height(12.dp))
                        ZcPrimaryButton("Reload now", icon = ZcIcons.Refresh, modifier = Modifier.fillMaxWidth()) { confirmReload = true }
                    }
                }
                reloadError?.let { Text("Reload failed: $it", style = ZcType.caption.copy(color = ZcTokens.danger), modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(8.dp))
                ZcGhostButton("Done", modifier = Modifier.fillMaxWidth()) { editor.dismissReport(); onDismiss() }
                return@Column
            }

            if (entries.isEmpty()) {
                Text("Nothing to apply.", style = ZcType.small)
                return@Column
            }
            val result = report?.result
            ConnectionBanner(result is ApplyResult.Failed && report?.conflicts.isNullOrEmpty(), (result as? ApplyResult.Failed)?.message.orEmpty(), Tone.Danger)
            val banner = pt.tiago.zerodroid.data.config.ConfigEditor.rejectionBanner(result, entries.map { it.path }.toSet())
            ConnectionBanner(banner != null, "The daemon refused the changes: ${banner.orEmpty()}", Tone.Danger)
            ConnectionBanner(readOnly, "This device can view settings but not change them.", Tone.Attention)
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                entries.groupBy { it.sectionKey }.forEach { (key, list) ->
                    item("h-$key") { SectionHeader(sections.firstOrNull { it.key == key }?.label ?: key) }
                    items(list, key = { it.path }) { e ->
                        val (old, new) = diffLine(e)
                        val conflict = e.path in report?.conflicts.orEmpty() && e.path !in confirmed
                        val rejected = (result as? ApplyResult.Rejected)?.takeIf { it.prop == e.path }
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.path, style = ZcType.monoSmall.copy(color = ZcTokens.muted), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(ZcIcons.Close, "Discard ${e.label}", tint = ZcTokens.faint, modifier = Modifier.padding(start = 8.dp).size(18.dp).clickable { editor.draft.unstage(e.path) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(old, style = ZcType.mono.copy(color = ZcTokens.faint), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Text("  →  ", style = ZcType.mono.copy(color = ZcTokens.faint))
                                Text(new, style = ZcType.mono.copy(color = ZcTokens.accentStart), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            }
                            if (conflict) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                Text("Changed on the daemon since you edited it", style = ZcType.caption.copy(color = ZcTokens.attention), modifier = Modifier.weight(1f))
                                ZcGhostButton("Keep mine", compact = true) { confirmed = confirmed + e.path }
                            }
                            if (rejected != null) Text("Refused: ${rejected.reason}", style = ZcType.caption.copy(color = ZcTokens.danger), modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            if (editor.draft.lostSecrets) {
                Text("Secret values typed before the app was closed were not kept. Enter them again.", style = ZcType.caption.copy(color = ZcTokens.attention), modifier = Modifier.padding(top = 8.dp))
            }
            Text(
                "The daemon checks these when you apply; if it refuses one, nothing is changed.",
                style = ZcType.caption.copy(color = ZcTokens.faint), modifier = Modifier.padding(top = 10.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZcGhostButton("Discard all", modifier = Modifier.weight(1f)) { discardAll = true }
                ZcPrimaryButton(
                    if (applying) "Applying…" else "Apply ${entries.size} change${if (entries.size == 1) "" else "s"}",
                    enabled = armed && !applying && !readOnly && conn is ConnState.Ready,
                    modifier = Modifier.weight(1.4f),
                ) { editor.applyInBackground(confirmed) }
            }
        }
    }

    if (discardAll) {
        AlertDialog(
            onDismissRequest = { discardAll = false },
            containerColor = ZcTokens.raised,
            title = { Text("Discard all changes?", style = ZcType.heading) },
            text = { Text("Nothing has been sent to the daemon yet.", style = ZcType.body.copy(color = ZcTokens.muted)) },
            confirmButton = { TextButton(onClick = { discardAll = false; editor.draft.clear(); onDismiss() }) { Text("Discard", style = ZcType.button.copy(color = ZcTokens.danger)) } },
            dismissButton = { TextButton(onClick = { discardAll = false }) { Text("Keep", style = ZcType.button.copy(color = ZcTokens.muted)) } },
        )
    }
    if (confirmReload) {
        AlertDialog(
            onDismissRequest = { confirmReload = false },
            containerColor = ZcTokens.raised,
            title = { Text("Reload ZeroClaw?", style = ZcType.heading) },
            text = { Text("Reloading restarts ZeroClaw. Turns running in Discord, Telegram or the desktop will be interrupted.", style = ZcType.body.copy(color = ZcTokens.muted)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReload = false
                    scope.launch {
                        // Re-check right before sending: a turn may have started since the button appeared.
                        val g = editor.reloadGate()
                        gate = g
                        if (g == ReloadGate.Allowed) {
                            pt.tiago.zerodroid.data.ZeroDroidConnection.expectRestart()
                            val err = editor.reload()
                            if (err == null) onDismiss() else { pt.tiago.zerodroid.data.ZeroDroidConnection.cancelRestart(); reloadError = err }
                        }
                    }
                }) { Text("Reload", style = ZcType.button.copy(color = ZcTokens.danger)) }
            },
            dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
        )
    }
}
