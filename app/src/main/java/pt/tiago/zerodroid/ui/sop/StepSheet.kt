package pt.tiago.zerodroid.ui.sop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pt.tiago.zerodroid.data.sop.SopDraft
import pt.tiago.zerodroid.ui.kit.NoSheetInsets
import pt.tiago.zerodroid.ui.kit.SheetBody
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcSegmented
import pt.tiago.zerodroid.ui.kit.ZcSwitch
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.kit.clickableNoRipple
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

private val KINDS = listOf("execute" to "Execute", "checkpoint" to "Checkpoint", "capability" to "Capability")
val EXEC_MODES = listOf("auto", "supervised", "step_by_step", "priority_based", "deterministic")

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
private fun JsonObject.b(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepSheet(vm: SopEditorViewModel, s: EditorState, n: Int) {
    val draft = s.draft ?: return
    val step = SopDraft.step(draft, n) ?: return
    val graph = s.graph
    val chips = graph?.let { portsFor(n, draft, it) }.orEmpty()
    val ops = s.registry?.operators?.map { it.token }.orEmpty()
    val set = { path: List<String>, v: JsonElement? -> vm.edit("step:$n:${path.joinToString(".")}") { SopDraft.setStep(it, n, path, v) } }
    var expand by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<PortRole?>(null) }
    val kind = step.s("kind") ?: "execute"

    ModalBottomSheet(
        { vm.openStep(null) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZcTokens.surface,
        contentWindowInsets = NoSheetInsets,
    ) {
        SheetBody {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = ZcTokens.gutter, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(ZcTokens.raised).border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        Text("$n", style = ZcType.label.copy(color = ZcTokens.text))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Step $n", style = ZcType.title, modifier = Modifier.weight(1f))
                    Box {
                        ZcIconButton(ZcIcons.More, "Step actions") { menu = true }
                        DropdownMenu(menu, { menu = false }, containerColor = ZcTokens.raised) {
                            DropdownMenuItem({ Text("Duplicate", style = ZcType.body) }, onClick = { menu = false; vm.duplicateStep(n) })
                            DropdownMenuItem({ Text("Delete step", style = ZcType.body.copy(color = ZcTokens.danger)) }, onClick = { menu = false; vm.deleteStep(n) })
                        }
                    }
                    ZcPrimaryButton("Done", compact = true) { vm.openStep(null) }
                }
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = ZcTokens.gutter)
                        .windowInsetsPadding(WindowInsets.navigationBars).imePadding().padding(bottom = 24.dp),
                ) {
                    ZcTextField(step.s("title").orEmpty(), { set(listOf("title"), JsonPrimitive(it)) }, "What this step does", modifier = Modifier.fillMaxWidth())

                    FieldLabel("Kind")
                    ZcSegmented(KINDS.map { it.second }, KINDS.indexOfFirst { it.first == kind }.coerceAtLeast(0), { i -> set(listOf("kind"), JsonPrimitive(KINDS[i].first)) })

                    FieldLabel("Instructions", trailing = {
                        Text("Expand", style = ZcType.caption.copy(color = ZcTokens.accentStart), modifier = Modifier.clickableNoRipple { expand = true })
                    })
                    ZcTextField(step.s("body").orEmpty(), { set(listOf("body"), JsonPrimitive(it)) }, "Tell the agent what to do in this step", singleLine = false, maxLines = 6, modifier = Modifier.fillMaxWidth())

                    FieldLabel("Suggested tools")
                    ToolField(
                        (step["suggested_tools"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                        s.toolSuggestions,
                    ) { list -> vm.edit { SopDraft.setStep(it, n, listOf("suggested_tools"), JsonArray(list.map(::JsonPrimitive))) } }

                    FieldLabel("Connections")
                    Connections(chips, onTap = { ask = it })

                    BranchesSection(vm, step, n, ops)

                    Row(
                        Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(12.dp))
                            .clickable { advanced = !advanced }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (advanced) "Advanced" else "Advanced · agent, condition, planned calls, confirmation", style = ZcType.small, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(if (advanced) ZcIcons.ChevronDown else ZcIcons.Chevron, null, tint = ZcTokens.muted, modifier = Modifier.size(16.dp))
                    }
                    if (advanced) Advanced(vm, s, step, n, kind, ops, set)
                }
            }
        }
    }

    if (expand) {
        InstructionsEditor(step.s("body").orEmpty(), SopDraft.steps(draft).mapNotNull { (it["number"] as? JsonPrimitive)?.content?.toIntOrNull() }.filter { it != n }, onDone = { expand = false }) {
            set(listOf("body"), JsonPrimitive(it))
        }
    }

    ask?.let { role ->
        val pick = { vm.startConnect(n, role, null) }
        when (role) {
            PortRole.Next -> ChoiceDialog(
                "After step $n", listOf(
                    "Continue to the next step" to { vm.edit { d -> SopDraft.setStep(SopDraft.setStep(d, n, listOf("routing", "next"), null), n, listOf("routing", "terminal"), null) } },
                    "End the run here" to { vm.edit { d -> SopDraft.setStep(SopDraft.setStep(d, n, listOf("routing", "next"), null), n, listOf("routing", "terminal"), JsonPrimitive(true)) } },
                    "Pick a step…" to pick,
                ), { ask = null },
            )
            PortRole.OnFailure -> ChoiceDialog(
                "If step $n fails", listOf(
                    "Fail the run" to { vm.edit { SopDraft.setFailure(it, n, SopDraft.Failure.Fail) } },
                    "Retry once" to { vm.edit { SopDraft.setFailure(it, n, SopDraft.Failure.Retry(1)) } },
                    "Retry 3 times" to { vm.edit { SopDraft.setFailure(it, n, SopDraft.Failure.Retry(3)) } },
                    "Go to a step…" to pick,
                ), { ask = null },
            )
            PortRole.WaitsFor -> {
                val deps = chips.firstOrNull { it.role == PortRole.WaitsFor }?.targets.orEmpty()
                ChoiceDialog(
                    "Step $n waits for",
                    deps.map { d -> "Stop waiting for step $d" to { vm.disconnect(d, n, PortRole.WaitsFor) } } + ("Pick a step…" to pick),
                    { ask = null },
                )
            }
            PortRole.Branch -> ask = null
        }
    }
}

@Composable
private fun Connections(chips: List<PortChip>, onTap: (PortRole) -> Unit) {
    val rows = chips.filter { it.role != PortRole.Branch }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ZcTokens.background).border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(12.dp))) {
        rows.forEachIndexed { i, c ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(ZcTokens.hairline))
            val (label, value) = when (c.role) {
                PortRole.Next -> "Next" to when {
                    c.connected -> "Step ${c.targets.joinToString(", ")}"
                    c.label == "ends here" -> "End the run here"
                    else -> "Continue to the next step"
                }
                PortRole.OnFailure -> "On failure" to when {
                    c.connected -> "Go to step ${c.targets.joinToString(", ")}"
                    c.label.startsWith("retries") -> c.label.replaceFirstChar { it.uppercase() }
                    else -> "Fail the run"
                }
                PortRole.WaitsFor -> "Waits for" to if (c.connected) "Steps ${c.targets.joinToString(", ")}" else "Nothing"
                PortRole.Branch -> "" to ""
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onTap(c.role) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(portColor(c.role), RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(10.dp))
                Text(label, style = ZcType.small, modifier = Modifier.width(84.dp))
                Text(value, style = ZcType.body.copy(color = if (c.connected) ZcTokens.text else ZcTokens.muted), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(ZcIcons.Chevron, null, tint = ZcTokens.faint, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun BranchesSection(vm: SopEditorViewModel, step: JsonObject, n: Int, ops: List<String>) {
    val rules = SopDraft.switchRules(step)
    FieldLabel("Branches", trailing = {
        Text("+ Add branch", style = ZcType.caption.copy(color = ZcTokens.accentStart), modifier = Modifier.clickableNoRipple {
            vm.edit { SopDraft.setSwitchRules(it, n, rules + buildJsonObject { put("name", if (rules.isEmpty()) "yes" else "branch ${rules.size + 1}") }) }
        })
    })
    if (rules.isEmpty()) {
        Text("No branches. Add one to send the run down different paths by a condition.", style = ZcType.small)
        return
    }
    rules.forEachIndexed { i, r ->
        val update = { nr: JsonObject -> vm.edit("rule:$n:$i") { SopDraft.setSwitchRules(it, n, rules.toMutableList().also { l -> l[i] = nr }) } }
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, ZcTokens.accentEnd.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZcTextField(r.s("name").orEmpty(), { update(JsonObject(r + ("name" to JsonPrimitive(it)))) }, "Branch name", modifier = Modifier.weight(1f))
                ZcIconButton(ZcIcons.Trash, "Remove branch", tint = ZcTokens.danger) {
                    vm.edit { SopDraft.setSwitchRules(it, n, rules.filterIndexed { j, _ -> j != i }) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("When", style = ZcType.caption)
            Spacer(Modifier.height(4.dp))
            ConditionField(r.s("when"), ops, { c -> update(JsonObject(r.toMutableMap().apply { if (c == null) remove("when") else put("when", JsonPrimitive(c)) })) })
            Spacer(Modifier.height(8.dp))
            val goto = (r["goto"] as? JsonPrimitive)?.content?.toIntOrNull()
            ZcGhostButton(if (goto != null) "Goes to step $goto · change" else "Pick the step it goes to", compact = true) { vm.startConnect(n, PortRole.Branch, i) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Advanced(vm: SopEditorViewModel, s: EditorState, step: JsonObject, n: Int, kind: String, ops: List<String>, set: (List<String>, JsonElement?) -> Unit) {
    FieldLabel("Agent")
    Picker(
        step.s("agent") ?: "Inherit workflow agent",
        listOf("" to "Inherit workflow agent") + s.agents.map { it to it },
        { set(listOf("agent"), it.ifEmpty { null }?.let(::JsonPrimitive)) },
    )
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ask before running", style = ZcType.body)
            Text("The run pauses for your approval first", style = ZcType.small)
        }
        ZcSwitch(step.b("requires_confirmation"), { set(listOf("requires_confirmation"), JsonPrimitive(it)) })
    }
    FieldLabel("Run only when")
    ConditionField(step.o("routing")?.s("when"), ops, { set(listOf("routing", "when"), it?.let(::JsonPrimitive)) })
    FieldLabel("Execution mode")
    Picker(step.s("mode") ?: "Inherit workflow mode", listOf("" to "Inherit workflow mode") + EXEC_MODES.map { it to it }, { set(listOf("mode"), it.ifEmpty { null }?.let(::JsonPrimitive)) })
    if (kind == "checkpoint") {
        FieldLabel("Approval prompt")
        ZcTextField(step.s("gate_prompt").orEmpty(), { set(listOf("gate_prompt"), it.ifBlank { null }?.let(::JsonPrimitive)) }, "What to show when asking for approval", singleLine = false, modifier = Modifier.fillMaxWidth())
    }
    if (kind == "capability") {
        FieldLabel("Capability")
        ZcTextField(step.s("capability").orEmpty(), { set(listOf("capability"), it.ifBlank { null }?.let(::JsonPrimitive)) }, "capability id", mono = true, modifier = Modifier.fillMaxWidth())
        FieldLabel("Capability input (JSON)")
        JsonField(step["capability_input"], "{ }") { set(listOf("capability_input"), it) }
    }
    FieldLabel("Planned tool calls")
    val calls = (step["calls"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    calls.forEachIndexed { i, c ->
        val put = { nc: JsonObject? -> vm.edit("call:$n:$i") { d -> SopDraft.setStep(d, n, listOf("calls"), JsonArray(calls.toMutableList().also { l -> if (nc == null) l.removeAt(i) else l[i] = nc }).takeIf { it.isNotEmpty() }) } }
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZcTextField(c.s("tool").orEmpty(), { put(JsonObject(c + ("tool" to JsonPrimitive(it.trim())))) }, "tool", mono = true, modifier = Modifier.weight(1f))
                ZcIconButton(ZcIcons.Trash, "Remove call", tint = ZcTokens.danger) { put(null) }
            }
            Spacer(Modifier.height(6.dp))
            JsonField(c["args"], """{"arg": "value"}""") { a -> put(JsonObject(c.toMutableMap().apply { if (a == null) remove("args") else put("args", a) })) }
        }
    }
    Text("+ Add planned call", style = ZcType.caption.copy(color = ZcTokens.accentStart), modifier = Modifier.padding(vertical = 6.dp).clickableNoRipple {
        vm.edit { d -> SopDraft.setStep(d, n, listOf("calls"), JsonArray(calls + buildJsonObject { put("tool", ""); put("args", JsonObject(emptyMap())) })) }
    })
    FieldLabel("Input/output schema (JSON)")
    JsonField(step["schema"], """{"output": {"type": "object"}}""") { set(listOf("schema"), it) }
}

/** Full-screen instructions editor with an "Insert step output" helper for `{{steps.N}}`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstructionsEditor(initial: String, otherSteps: List<Int>, onDone: () -> Unit, onChange: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(ZcTokens.background).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
            ZcTopBar("Instructions", navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onDone) }, actions = {
                ZcPrimaryButton("Done", compact = true, modifier = Modifier.padding(end = 8.dp), onClick = onDone)
            })
            if (otherSteps.isNotEmpty()) {
                FlowRow(Modifier.padding(horizontal = ZcTokens.gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Insert output of", style = ZcType.caption, modifier = Modifier.align(Alignment.CenterVertically))
                    otherSteps.forEach { k ->
                        Text(
                            "step $k",
                            style = ZcType.caption.copy(color = ZcTokens.accentEnd),
                            modifier = Modifier.clip(RoundedCornerShape(ZcTokens.chipRadius)).background(ZcTokens.accentEnd.copy(alpha = 0.12f)).clickable {
                                val tag = "{{steps.$k}}"
                                val t = value.text.replaceRange(value.selection.min, value.selection.max, tag)
                                value = TextFieldValue(t, TextRange(value.selection.min + tag.length))
                                onChange(t)
                            }.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            BasicTextField(
                value, { value = it; onChange(it.text) },
                textStyle = ZcType.body,
                cursorBrush = SolidColor(ZcTokens.accentStart),
                modifier = Modifier.fillMaxSize().padding(ZcTokens.gutter),
            )
        }
    }
}
