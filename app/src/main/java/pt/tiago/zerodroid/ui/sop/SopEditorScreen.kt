package pt.tiago.zerodroid.ui.sop

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.tiago.zerodroid.ui.kit.clickableNoRipple
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.sop.SopDraft
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.NoSheetInsets
import pt.tiago.zerodroid.ui.kit.SheetBody
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcSegmented
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Graph scroll offsets per workflow, kept for the app session. */
private object FlowScrollMemory { val offsets = HashMap<String, Int>() }

@Composable
fun SopEditorEntry(name: String?, onBack: () -> Unit, onReplace: (SopRoute) -> Unit, onPush: (SopRoute) -> Unit, onEnableEngine: () -> Unit) {
    val vm: SopEditorViewModel = viewModel(key = "sop-edit-${name ?: "new"}") { SopEditorViewModel(name) }
    SopEditorScreen(vm, onBack, onReplace, onPush, onEnableEngine)
}

private sealed interface Menu {
    data class Port(val step: Int, val chip: PortChip) : Menu
    data class Card(val step: Int) : Menu
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SopEditorScreen(
    vm: SopEditorViewModel,
    onBack: () -> Unit,
    onReplace: (SopRoute) -> Unit,
    onPush: (SopRoute) -> Unit,
    onEnableEngine: () -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val conn by ZeroDroidConnection.state.collectAsStateWithLifecycle()
    val online = conn is ConnState.Ready
    val haptics = LocalHapticFeedback.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var discardAsk by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf<Int?>(null) } // step number, or 0 for the workflow
    var duplicateAsk by remember { mutableStateOf(false) }
    var legend by remember { mutableStateOf(false) }
    var runAsk by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Menu?>(null) }
    val draft = s.draft
    val name = draft?.let(SopDraft::name)?.trim().orEmpty()
    val connect = s.mode as? EditorMode.Connect

    val leave = { if (s.dirty) discardAsk = true else onBack() }
    BackHandler(enabled = connect != null) { vm.cancelConnect() }
    BackHandler(enabled = connect == null) { leave() }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Column(Modifier.fillMaxSize()) {
            ZcTopBar(
                name.ifEmpty { "New workflow" },
                navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = leave) },
                subtitle = {
                    Text(
                        when {
                            s.saving -> "Saving…"
                            s.isNew -> "Draft · not saved yet"
                            s.dirty -> "Draft · unsaved changes"
                            else -> "Saved"
                        },
                        style = ZcType.caption.copy(color = if (s.dirty || s.isNew) ZcTokens.attention else ZcTokens.muted),
                    )
                },
                actions = {
                    if (s.canUndo) ZcIconButton(ZcIcons.Undo, "Undo", onClick = vm::undo)
                    if (!s.isNew && !s.dirty && draft != null && SopDraft.hasManualTrigger(draft)) {
                        ZcIconButton(ZcIcons.Play, "Run", tint = ZcTokens.success) { runAsk = true }
                    }
                    ZcPrimaryButton(
                        "Save", compact = true, enabled = (s.dirty || s.isNew) && !s.saving && online,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) { vm.save() }
                    Box {
                        ZcIconButton(ZcIcons.More, "More") { overflow = true }
                        DropdownMenu(overflow, { overflow = false }, containerColor = ZcTokens.raised) {
                            DropdownMenuItem({ Text("Legend", style = ZcType.body) }, onClick = { overflow = false; legend = true })
                            if (!s.isNew) {
                                DropdownMenuItem({ Text("Duplicate…", style = ZcType.body) }, onClick = { overflow = false; duplicateAsk = true })
                                DropdownMenuItem({ Text("Delete workflow", style = ZcType.body.copy(color = ZcTokens.danger)) }, onClick = { overflow = false; deleteAsk = 0 })
                            }
                        }
                    }
                },
            )
            ConnectionBanner(!online, "Reconnecting… your changes stay here until you can save.", Tone.Attention)
            ConnectionBanner(s.error != null, s.error ?: "", Tone.Danger, action = "Dismiss", onAction = vm::consumeError)
            ZcSegmented(listOf("Flow", "Settings", "Runs"), tab, { tab = it }, Modifier.padding(horizontal = ZcTokens.gutter, vertical = 6.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    s.loading || draft == null -> Column(Modifier.padding(ZcTokens.gutter)) { SkeletonLines(5) }
                    tab == 0 -> {
                        val key = name.ifEmpty { "\u0000new" }
                        val scroll = rememberScrollState(FlowScrollMemory.offsets[key] ?: 0)
                        DisposableEffect(key) { onDispose { FlowScrollMemory.offsets[key] = scroll.value } }
                        val graph = s.graph
                        if (graph == null) {
                            Column(Modifier.padding(ZcTokens.gutter)) { SkeletonLines(5) }
                        } else {
                            FlowGraph(
                                graph, draft, s.mode,
                                modifier = Modifier.fillMaxSize(),
                                scroll = scroll,
                                onCard = vm::openStep,
                                onCardLongPress = { menu = Menu.Card(it) },
                                onPort = { step, chip ->
                                    if (!online) return@FlowGraph
                                    if (chip.connected) menu = Menu.Port(step, chip) else vm.startConnect(step, chip.role, chip.port)
                                },
                                onTarget = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.connectTo(it) },
                                onNewStepTarget = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.connectToNewStep() },
                                onDiagnostic = vm::showToast,
                            )
                        }
                        if (connect == null) {
                            ZcGhostButton(
                                "Step", icon = ZcIcons.Plus,
                                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp),
                                onClick = vm::addStep,
                            )
                        }
                    }
                    tab == 1 -> SopSettingsTab(vm, s)
                    else -> RunsTab(name, s.isNew, onOpenRun = { id -> onPush(SopRoute.Run(name, id)) }, onEnableEngine = onEnableEngine)
                }

                androidx.compose.animation.AnimatedVisibility(
                    connect != null && draft != null,
                    enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    val m = connect ?: return@AnimatedVisibility
                    ConnectBar(connectSentence(m, draft!!), portColor(m.role), onCancel = vm::cancelConnect)
                }
                Toast(s.toast, vm::consumeToast, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp))
            }
        }
    }

    s.openStep?.let { n -> if (draft != null) StepSheet(vm, s, n) }

    when (val m = menu) {
        is Menu.Port -> PortMenu(
            m.chip,
            onDismiss = { menu = null },
            onChange = { menu = null; vm.startConnect(m.step, m.chip.role, m.chip.port) },
            onDisconnect = { target ->
                menu = null
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (m.chip.role == PortRole.WaitsFor) vm.disconnect(target, m.step, PortRole.WaitsFor)
                else vm.disconnect(m.step, target, m.chip.role, m.chip.port)
            },
        )
        is Menu.Card -> CardMenu(
            m.step,
            onDismiss = { menu = null },
            onMove = { d -> menu = null; vm.moveStep(m.step, d) },
            onDuplicate = { menu = null; vm.duplicateStep(m.step) },
            onDelete = { menu = null; deleteAsk = m.step },
        )
        null -> Unit
    }

    s.review?.let { r -> ReviewSheet(r, onDismiss = vm::dismissReview, onGoTo = { n -> vm.dismissReview(); tab = 0; vm.openStep(n) }, onSaveAnyway = { vm.save(force = true) }) }

    if (discardAsk) {
        ConfirmDialog(
            "Discard changes?", "Your edits to this workflow haven't been saved.", "Discard", danger = true,
            onConfirm = { discardAsk = false; onBack() }, onDismiss = { discardAsk = false },
        )
    }
    deleteAsk?.let { n ->
        if (n == 0) {
            ConfirmDialog(
                "Delete \"$name\"?", "The workflow is removed from the daemon. Runs in progress keep going.", "Delete", danger = true,
                onConfirm = { deleteAsk = null; vm.delete(onBack) }, onDismiss = { deleteAsk = null },
            )
        } else {
            ConfirmDialog(
                "Delete step $n?", "Connections to it are removed too. You can undo this.", "Delete", danger = true,
                onConfirm = { deleteAsk = null; vm.deleteStep(n) }, onDismiss = { deleteAsk = null },
            )
        }
    }
    if (duplicateAsk) {
        NameDialog("Duplicate workflow", "$name copy", onDismiss = { duplicateAsk = false }) { newName ->
            duplicateAsk = false
            vm.duplicateAs(newName) { created -> onReplace(SopRoute.Edit(created)) }
        }
    }
    if (legend) LegendSheet { legend = false }
    if (runAsk) RunSheet(name, onDismiss = { runAsk = false }, onStarted = { id -> runAsk = false; onPush(SopRoute.Run(name, id)) }, onEngineOff = { runAsk = false; onEnableEngine() })
}

@Composable
private fun ConnectBar(text: String, color: androidx.compose.ui.graphics.Color, onCancel: () -> Unit) {
    Row(
        Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp).fillMaxWidth()
            .background(ZcTokens.raised, RoundedCornerShape(16.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = ZcType.heading.copy(fontSize = 14.14.sp))
            Text("Tap the step to connect", style = ZcType.small)
        }
        ZcGhostButton("Cancel", compact = true, onClick = onCancel)
    }
}

@Composable
fun Toast(text: String?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(text) { if (text != null) { delay(2600); onDone() } }
    androidx.compose.animation.AnimatedVisibility(text != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            text ?: "",
            style = ZcType.small.copy(color = ZcTokens.text),
            modifier = Modifier.padding(horizontal = 24.dp)
                .background(ZcTokens.raised, RoundedCornerShape(12.dp))
                .border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun PortMenu(chip: PortChip, onDismiss: () -> Unit, onChange: () -> Unit, onDisconnect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZcTokens.raised,
        title = { Text(chip.label.replaceFirstChar { it.uppercase() }, style = ZcType.heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZcGhostButton(if (chip.role == PortRole.WaitsFor) "Wait for another step" else "Change target", modifier = Modifier.fillMaxWidth(), onClick = onChange)
                chip.targets.forEach { t ->
                    ZcGhostButton("Disconnect step $t", icon = ZcIcons.Unlink, modifier = Modifier.fillMaxWidth()) { onDisconnect(t) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", style = ZcType.button.copy(color = ZcTokens.muted)) } },
    )
}

@Composable
private fun CardMenu(step: Int, onDismiss: () -> Unit, onMove: (Int) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZcTokens.raised,
        title = { Text("Step $step", style = ZcType.heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZcGhostButton("Move up", modifier = Modifier.weight(1f)) { onMove(-1) }
                    ZcGhostButton("Move down", modifier = Modifier.weight(1f)) { onMove(1) }
                }
                ZcGhostButton("Duplicate", icon = ZcIcons.Copy, modifier = Modifier.fillMaxWidth(), onClick = onDuplicate)
                ZcGhostButton("Delete step", icon = ZcIcons.Trash, modifier = Modifier.fillMaxWidth(), onClick = onDelete)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", style = ZcType.button.copy(color = ZcTokens.muted)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZcTokens.raised,
        title = { Text(title, style = ZcType.heading) },
        text = { Text(text, style = ZcType.body.copy(color = ZcTokens.muted)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, style = ZcType.button.copy(color = if (danger) ZcTokens.danger else ZcTokens.accentStart)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZcTokens.raised,
        title = { Text(title, style = ZcType.heading) },
        text = {
            Column {
                Text("The copy is saved right away. To rename, duplicate and then delete the old one.", style = ZcType.small)
                Spacer(Modifier.height(12.dp))
                ZcTextField(text, { text = it }, "Name", modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onOk(text) }, enabled = text.isNotBlank()) { Text("Duplicate", style = ZcType.button.copy(color = ZcTokens.accentStart)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
    )
}

private val STEP_REF = Regex("""(?i)\bstep\s+(\d+)""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSheet(r: SaveReview, onDismiss: () -> Unit, onGoTo: (Int) -> Unit, onSaveAnyway: () -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ZcTokens.surface, contentWindowInsets = NoSheetInsets) {
        SheetBody {
            Column(Modifier.padding(horizontal = ZcTokens.gutter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
                Text(if (r.blocking.isNotEmpty()) "Fix these before saving" else "Save with warnings?", style = ZcType.title)
                Spacer(Modifier.height(12.dp))
                (r.blocking.map { it to true } + r.warnings.map { it to false }).forEach { (msg, blocking) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (blocking) ZcTokens.danger else ZcTokens.attention, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(msg, style = ZcType.body, modifier = Modifier.weight(1f))
                        STEP_REF.find(msg)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
                            Text("Go to step $n", style = ZcType.caption.copy(color = ZcTokens.accentStart), modifier = Modifier.padding(start = 8.dp).clickableNoRipple { onGoTo(n) })
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (r.blocking.isEmpty()) ZcPrimaryButton("Save anyway", modifier = Modifier.fillMaxWidth(), onClick = onSaveAnyway)
                ZcGhostButton("Back to editing", modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegendSheet(onDismiss: () -> Unit) {
    val rows = listOf(
        WireRole.Sequence to ("Next step" to "Runs after this one: the next step in order, or the one you pick."),
        WireRole.Dependency to ("Waits for" to "The step starts only after these steps finished."),
        WireRole.Failure to ("On failure" to "Where to go when this step fails."),
        WireRole.Switch to ("Branch" to "A named path taken when its condition matches."),
        WireRole.Trigger to ("Trigger" to "What starts the workflow. Set in Settings."),
        WireRole.Data to ("Data" to "A step uses another step's output ({{steps.N}})."),
    )
    ModalBottomSheet(onDismiss, containerColor = ZcTokens.surface, contentWindowInsets = NoSheetInsets) {
        Column(Modifier.padding(horizontal = ZcTokens.gutter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 20.dp)) {
            Text("Legend", style = ZcType.title)
            Spacer(Modifier.height(12.dp))
            rows.forEach { (role, t) ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(28.dp).height(3.dp).background(roleColor(role), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(t.first, style = ZcType.heading.copy(fontSize = 14.sp))
                        Text(t.second, style = ZcType.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text("Tap a port on a card, then the step it should lead to. Tap a wired port to change or disconnect it.", style = ZcType.small, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
