package pt.tiago.zerodroid.ui.sop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import pt.tiago.zerodroid.data.sop.SopDraft
import pt.tiago.zerodroid.ui.kit.ZcSwitch
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.clickableNoRipple
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

private val PRIORITIES = listOf("low", "normal", "high", "critical")
private val ADMISSION = listOf("parallel" to "Run in parallel", "hold" to "Hold new runs until the current ends", "coalesce" to "Merge new triggers into the running one")

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.content?.toLongOrNull()

/** Workflow-level settings: identity, how it runs, triggers, and the rarely needed knobs. */
@Composable
fun SopSettingsTab(vm: SopEditorViewModel, s: EditorState) {
    val d = s.draft ?: return
    val set = { k: String, v: JsonElement? -> vm.edit("sop:$k") { SopDraft.set(it, k, v) } }
    val ops = s.registry?.operators?.map { it.token }.orEmpty()
    val triggers = SopDraft.triggers(d)
    var advanced by rememberSaveable { mutableStateOf(false) }
    var addTrigger by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ZcTokens.gutter)
            .windowInsetsPadding(WindowInsets.navigationBars).imePadding().padding(bottom = 32.dp),
    ) {
        FieldLabel("Name")
        if (s.isNew) {
            ZcTextField(d.str("name").orEmpty(), { set("name", JsonPrimitive(it)) }, "e.g. Morning weather", modifier = Modifier.fillMaxWidth())
        } else {
            Text(SopDraft.name(d), style = ZcType.body)
            Text("To rename, duplicate with a new name and delete the old one.", style = ZcType.caption, modifier = Modifier.padding(top = 4.dp))
        }
        FieldLabel("Description")
        ZcTextField(d.str("description").orEmpty(), { set("description", JsonPrimitive(it)) }, "What this workflow is for", singleLine = false, maxLines = 3, modifier = Modifier.fillMaxWidth())

        FieldLabel("How it runs")
        Picker(
            when (d.str("execution_mode")) {
                "auto" -> "Auto · runs every step on its own"
                "step_by_step" -> "Step by step · asks before each step"
                "priority_based" -> "Priority based"
                "deterministic" -> "Deterministic · planned calls only"
                else -> "Supervised · asks at checkpoints"
            },
            listOf(
                "auto" to "Auto · runs every step on its own",
                "supervised" to "Supervised · asks at checkpoints",
                "step_by_step" to "Step by step · asks before each step",
                "priority_based" to "Priority based",
                "deterministic" to "Deterministic · planned calls only",
            ),
            { set("execution_mode", JsonPrimitive(it)) },
        )
        FieldLabel("Agent")
        Picker(d.str("agent") ?: "Daemon default", listOf("" to "Daemon default") + s.agents.map { it to it }, { set("agent", it.ifEmpty { null }?.let(::JsonPrimitive)) })
        FieldLabel("Priority")
        Picker((d.str("priority") ?: "normal").replaceFirstChar { it.uppercase() }, PRIORITIES.map { it to it.replaceFirstChar { c -> c.uppercase() } }, { set("priority", JsonPrimitive(it)) })

        FieldLabel("Triggers", trailing = {
            Text("+ Add trigger", style = ZcType.caption.copy(color = ZcTokens.accentStart), modifier = Modifier.clickableNoRipple { addTrigger = true })
        })
        if (triggers.isEmpty()) Text("No triggers: nothing will start this workflow.", style = ZcType.small.copy(color = ZcTokens.attention))
        triggers.forEachIndexed { i, t -> androidx.compose.runtime.key(i, triggers.size) {
            TriggerCard(
                t, s.registry, ops,
                onChange = { nt -> vm.edit("trigger:$i") { SopDraft.setTriggers(it, triggers.toMutableList().also { l -> l[i] = nt }) } },
                onRemove = { vm.edit { SopDraft.setTriggers(it, triggers.filterIndexed { j, _ -> j != i }) } },
            )
        } }

        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(12.dp))
                .clickable { advanced = !advanced }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (advanced) "Advanced" else "Advanced · cooldown, concurrency, approvals, decision model", style = ZcType.small, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(if (advanced) ZcIcons.ChevronDown else ZcIcons.Chevron, null, tint = ZcTokens.muted, modifier = Modifier.size(16.dp))
        }
        if (advanced) {
            NumberField("Cooldown between runs (seconds)", d.num("cooldown_secs")) { set("cooldown_secs", JsonPrimitive(it)) }
            NumberField("Max runs at once", d.num("max_concurrent")) { set("max_concurrent", JsonPrimitive(it)) }
            FieldLabel("When triggered while running")
            Picker(ADMISSION.firstOrNull { it.first == d.str("admission_policy") }?.second ?: "Run in parallel", ADMISSION, { set("admission_policy", JsonPrimitive(it)) })
            NumberField("Max approvals waiting (0 = no limit)", d.num("max_pending_approvals")) { set("max_pending_approvals", JsonPrimitive(it)) }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Deterministic", style = ZcType.body)
                    Text("Run planned tool calls only, no model", style = ZcType.small)
                }
                ZcSwitch((d["deterministic"] as? JsonPrimitive)?.booleanOrNull == true, { set("deterministic", JsonPrimitive(it)) })
            }
            FieldLabel("Decision model (JSON)")
            JsonField(d["decision"], """{"model": "alias", "modes": ["auto", "supervised"]}""") { set("decision", it) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (addTrigger) {
        val reg = s.registry
        ChoiceDialog(
            "Start this workflow when…",
            (reg?.let(::triggerSourceOptions) ?: listOf("manual" to "Manual")).map { (src, label) ->
                label to { vm.edit { SopDraft.setTriggers(it, triggers + (reg?.let { r -> blankTrigger(src, r) } ?: kotlinx.serialization.json.buildJsonObject { put("type", JsonPrimitive(src)) })) } }
            },
            { addTrigger = false },
        )
    }
}

@Composable
private fun NumberField(label: String, value: Long?, onChange: (Long) -> Unit) {
    FieldLabel(label)
    // Local text so the field can be emptied while typing; only whole numbers reach the draft.
    var text by androidx.compose.runtime.remember { mutableStateOf(value?.toString().orEmpty()) }
    androidx.compose.runtime.LaunchedEffect(value) { if (text.toLongOrNull() != value && !(text.isEmpty() && value == null)) text = value?.toString().orEmpty() }
    ZcTextField(
        text, { t -> text = t.filter(Char::isDigit).take(9); text.toLongOrNull()?.let(onChange) }, "0",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(140.dp),
    )
}
