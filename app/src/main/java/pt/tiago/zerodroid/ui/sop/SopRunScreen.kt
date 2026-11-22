package pt.tiago.zerodroid.ui.sop

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.sop.SopDraft
import pt.tiago.zerodroid.data.sop.SopResult
import pt.tiago.zerodroid.rpc.proto.SopRunDto
import pt.tiago.zerodroid.ui.chats.relativeTime
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.NoSheetInsets
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.kit.clickableNoRipple
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

@Composable
fun SopRunEntry(name: String, runId: String, onBack: () -> Unit, onEnableEngine: () -> Unit) {
    val vm: SopRunViewModel = viewModel(key = "sop-run-$runId") { SopRunViewModel(name, runId) }
    SopRunScreen(vm, name, runId, onBack, onEnableEngine)
}

private fun statusText(status: String?): Pair<String, Tone> = when (status) {
    null -> "Loading…" to Tone.Neutral
    "waiting_approval", "paused_checkpoint" -> "Waiting for your approval" to Tone.Attention
    "running", "pending" -> "Running" to Tone.Live
    "completed" -> "Finished" to Tone.Success
    "failed" -> "Failed" to Tone.Danger
    "cancelled", "cancel_requested" -> "Cancelled" to Tone.Neutral
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() } to Tone.Neutral
}

@Composable
fun SopRunScreen(vm: SopRunViewModel, name: String, runId: String, onBack: () -> Unit, onEnableEngine: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.start()
        onPauseOrDispose { vm.stop() }
    }
    var denyAsk by remember { mutableStateOf(false) }
    val o = s.overlay
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ZcTopBar(
            name.trim(),
            navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) },
            subtitle = { Text("Run ${runId.take(8)}", style = ZcType.caption) },
        )
        ConnectionBanner(s.engineOff, ENGINE_OFF_TEXT, Tone.Attention, action = "Enable", onAction = onEnableEngine)
        ConnectionBanner(s.gone, "The daemon no longer has this run. It may have been cleared after a restart.", Tone.Neutral)
        ConnectionBanner(s.error != null, s.error ?: "", Tone.Danger)
        ZcCard(Modifier.fillMaxWidth().padding(horizontal = ZcTokens.gutter, vertical = 6.dp)) {
            val (text, tone) = statusText(o?.status)
            Row {
                Text(text, style = ZcType.heading, modifier = Modifier.weight(1f))
                if (o != null) Text("Step ${o.currentStep.coerceAtLeast(0)} of ${o.totalSteps}", style = ZcType.small)
            }
            Spacer(Modifier.height(10.dp))
            val frac = if (o == null || o.totalSteps == 0) 0f else (o.nodes.count { it.state == "completed" || it.state == "skipped" }.toFloat() / o.totalSteps).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).background(ZcTokens.raised, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(frac).height(6.dp).background(if (tone == Tone.Danger) androidx.compose.ui.graphics.SolidColor(ZcTokens.danger) else ZcTokens.accentHorizontal, RoundedCornerShape(3.dp)))
            }
        }
        val g = s.graph
        if (g == null) {
            Column(Modifier.padding(ZcTokens.gutter)) { SkeletonLines(5) }
        } else {
            val calls = o?.nodes?.associate { it.step to it.toolCalls }.orEmpty()
            FlowGraph(
                g, s.draft, EditorMode.Browse,
                modifier = Modifier.weight(1f),
                run = s.runStates,
                bottomPadding = 40.dp,
                cardExtra = { step ->
                    val list = calls[step].orEmpty()
                    if (list.isNotEmpty()) ToolCallsLine(list)
                    if (s.runStates[step] == "waiting") {
                        val prompt = SopDraft.step(s.draft, step)?.get("gate_prompt")?.let { (it as? JsonPrimitive)?.content }
                        if (!prompt.isNullOrBlank()) Text(prompt, style = ZcType.small.copy(color = ZcTokens.text), modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ZcGhostButton("Deny", modifier = Modifier.weight(1f), enabled = !s.deciding) { denyAsk = true }
                            ZcPrimaryButton("Approve", modifier = Modifier.weight(1f), enabled = !s.deciding, onClick = vm::approve)
                        }
                    }
                },
            )
        }
    }
    if (denyAsk) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { denyAsk = false },
            containerColor = ZcTokens.raised,
            title = { Text("Deny this step?", style = ZcType.heading) },
            text = { ZcTextField(reason, { reason = it }, "Reason (optional)", modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { denyAsk = false; vm.deny(reason) }) { Text("Deny", style = ZcType.button.copy(color = ZcTokens.danger)) } },
            dismissButton = { TextButton(onClick = { denyAsk = false }) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
        )
    }
}

@Composable
private fun ToolCallsLine(calls: List<pt.tiago.zerodroid.rpc.proto.StepToolCallDto>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 6.dp).animateContentSize().clickableNoRipple { open = !open }) {
        if (!open) {
            Text(
                calls.joinToString("  ·  ") { (if (it.success) "" else "✕ ") + it.tool },
                style = ZcType.monoSmall.copy(color = ZcTokens.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        } else {
            calls.forEach { c ->
                Text((if (c.success) "✓ " else "✕ ") + c.tool + (c.args?.let { " $it" } ?: ""), style = ZcType.monoSmall.copy(color = if (c.success) ZcTokens.text else ZcTokens.danger), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (c.output.isNotBlank()) Text(c.output.take(400), style = ZcType.monoSmall.copy(color = ZcTokens.muted), maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}

/** The workflow's runs, newest first. */
@Composable
fun RunsTab(name: String, isNew: Boolean, onOpenRun: (String) -> Unit, onEnableEngine: () -> Unit) {
    if (isNew) {
        EmptyState(ZcIcons.Clock, "No runs yet", "Save the workflow first, then run it.")
        return
    }
    val result by produceState<SopResult<List<SopRunDto>>?>(null, name) { value = ZeroDroidConnection.sops.runs(name) }
    when (val r = result) {
        null -> Column(Modifier.padding(ZcTokens.gutter)) { SkeletonLines(4) }
        SopResult.Unavailable -> ConnectionBanner(true, ENGINE_OFF_TEXT, Tone.Attention, action = "Enable", onAction = onEnableEngine)
        is SopResult.Ok -> if (r.value.isEmpty()) {
            EmptyState(ZcIcons.Clock, "No runs yet", "Runs appear here when this workflow is triggered.")
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ZcTokens.gutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(ZcTokens.gap),
            ) {
                items(r.value.sortedByDescending { it.startedAt ?: "" }, key = { it.runId }) { run ->
                    ZcCard(Modifier.fillMaxWidth(), onClick = { onOpenRun(run.runId) }, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(run.startedAt?.let { relativeTime(it) }?.ifEmpty { null }?.let { "Started $it" } ?: "Run ${run.runId.take(8)}", style = ZcType.heading)
                                Text("${triggerTypeLabel(run.triggerSource)} · step ${run.currentStep} of ${run.totalSteps}", style = ZcType.small)
                            }
                            val (t, tone) = runPill(run)
                            StatusPill(t, tone)
                        }
                    }
                }
            }
        }
        is SopResult.Error -> ConnectionBanner(true, r.message, Tone.Danger)
        SopResult.Forbidden -> ConnectionBanner(true, "Your sign-in can't see runs", Tone.Danger)
    }
}

/** Starts a manual run with optional JSON input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunSheet(name: String, onDismiss: () -> Unit, onStarted: (String) -> Unit, onEngineOff: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var showInput by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismiss, containerColor = ZcTokens.surface, contentWindowInsets = NoSheetInsets) {
        Column(Modifier.padding(horizontal = ZcTokens.gutter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 20.dp)) {
            Text("Run ${name.trim()}", style = ZcType.title)
            Spacer(Modifier.height(12.dp))
            if (showInput) {
                ZcTextField(input, { input = it; error = null }, """{"city": "Lisboa"}""", singleLine = false, maxLines = 8, mono = true, modifier = Modifier.fillMaxWidth())
                Text("Passed to step 1 as its input.", style = ZcType.caption, modifier = Modifier.padding(top = 4.dp))
            } else {
                Text("+ Add input (optional JSON)", style = ZcType.caption.copy(color = ZcTokens.accentStart), modifier = Modifier.clickable { showInput = true }.padding(vertical = 8.dp))
            }
            error?.let { Text(it, style = ZcType.caption.copy(color = ZcTokens.danger), modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(14.dp))
            ZcPrimaryButton(if (busy) "Starting…" else "Run now", icon = ZcIcons.Play, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                val payload = runCatching { validPayload(input) }.getOrElse { error = it.message; return@ZcPrimaryButton }
                busy = true
                scope.launch {
                    when (val r = ZeroDroidConnection.sops.run(name, payload)) {
                        is SopResult.Ok -> onStarted(r.value)
                        SopResult.Unavailable -> { onEngineOff(); onDismiss() }
                        SopResult.Forbidden -> error = "Your sign-in can't run workflows"
                        is SopResult.Error -> error = r.message
                    }
                    busy = false
                }
            }
        }
    }
}
