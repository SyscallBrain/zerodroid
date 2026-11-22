package pt.tiago.zerodroid.ui.cron

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tiago.zerodroid.data.ZeroDroidConnection

import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcSegmented
import pt.tiago.zerodroid.ui.kit.ZcSwitch
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.sop.ConfirmDialog
import pt.tiago.zerodroid.ui.sop.FieldLabel
import pt.tiago.zerodroid.ui.sop.Picker
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Schedule shortcuts; the field stays free text for anything else. */
val SCHEDULE_PRESETS = listOf(
    "Every hour" to "0 * * * *",
    "Daily 09:00" to "0 9 * * *",
    "Weekdays 09:00" to "0 9 * * 1-5",
    "Weekly Mon 09:00" to "0 9 * * 1",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationEditorScreen(vm: AutomationEditorViewModel, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val conn by ZeroDroidConnection.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(s.savedId) { s.savedId?.let(onSaved) }
    var discardAsk by remember { mutableStateOf(false) }
    val back = { if (s.dirty) discardAsk = true else onBack() }
    BackHandler(onBack = back)
    val d = s.draft
    val isNew = s.isNew
    val set = vm::update
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.ime)) {
        ZcTopBar(
            if (isNew) "New job" else "Edit job",
            navigation = { ZcIconButton(ZcIcons.Close, "Close", onClick = back) },
            actions = {
                ZcPrimaryButton(
                    if (s.saving) "Saving…" else "Save", compact = true,
                    enabled = s.canSave && conn is ConnState.Ready,
                    onClick = vm::save,
                )
            },
        )
        ConnectionBanner(s.error != null, s.error ?: "", Tone.Danger, action = "OK", onAction = vm::consumeError)
        ConnectionBanner(s.problems.isNotEmpty(), s.problems.joinToString(" · "), Tone.Attention)
        if (s.loading) {
            SkeletonLines(5, Modifier.padding(ZcTokens.gutter))
            return@Column
        }
        // The job didn't load: the banner above says why; there is nothing to edit.
        if (!isNew && s.job == null) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ZcTokens.gutter).padding(bottom = 48.dp)) {
            SectionHeader("Basics")
            FieldLabel("Name")
            ZcTextField(d.name, { v -> set { it.copy(name = v) } }, "Optional", modifier = Modifier.fillMaxWidth())
            FieldLabel("Agent")
            if (isNew) {
                Picker(d.agent.ifEmpty { "Pick an agent" }, s.agents.map { it.alias to (if (it.enabled) it.alias else "${it.alias} (off)") }, { a ->
                    set { it.copy(agent = a, channel = "") }
                }, Modifier.fillMaxWidth())
            } else {
                Text(d.agent, style = ZcType.body)
            }
            FieldLabel("Type")
            if (isNew) {
                ZcSegmented(listOf("Shell command", "Ask the agent"), if (d.agentJob) 1 else 0, { i -> set { it.copy(agentJob = i == 1) } })
            } else {
                Text(if (d.agentJob) "Ask the agent" else "Shell command", style = ZcType.body)
            }

            SectionHeader("Schedule", Modifier.padding(top = 16.dp))
            if (s.scheduleEditable) {
                FieldLabel("Cron expression")
                ZcTextField(d.expr, { v -> set { it.copy(expr = v) } }, "min hour day month weekday", mono = true, modifier = Modifier.fillMaxWidth())
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SCHEDULE_PRESETS.forEach { (label, expr) ->
                        androidx.compose.foundation.layout.Box(Modifier.clickableChip { set { it.copy(expr = expr) } }) {
                            StatusPill(label, if (d.expr.trim() == expr) Tone.Live else Tone.Neutral, dot = false)
                        }
                    }
                }
                FieldLabel("Timezone")
                ZcTextField(d.tz, { v -> set { it.copy(tz = v) } }, java.time.ZoneId.systemDefault().id + " (daemon's if empty)", mono = true, modifier = Modifier.fillMaxWidth())
            } else {
                Text(s.job?.let(::scheduleText).orEmpty(), style = ZcType.mono)
                Note("This schedule kind can't be edited here.")
            }

            SectionHeader("Task", Modifier.padding(top = 16.dp))
            if (d.agentJob) {
                FieldLabel("Prompt")
                ZcTextField(d.prompt, { v -> set { it.copy(prompt = v) } }, "What should the agent do?", singleLine = false, maxLines = 10, modifier = Modifier.fillMaxWidth())
                FieldLabel("Model")
                ZcTextField(d.model, { v -> set { it.copy(model = v) } }, "Agent default", mono = true, modifier = Modifier.fillMaxWidth())
                FieldLabel("Session")
                ZcSegmented(listOf("Isolated", "Main"), if (d.sessionMain) 1 else 0, { i -> set { it.copy(sessionMain = i == 1) } })
                FieldLabel("Allowed tools")
                ZcTextField(d.allowedTools, { v -> set { it.copy(allowedTools = v) } }, "All the agent's tools (comma-separated to limit)", mono = true, modifier = Modifier.fillMaxWidth())
                SwitchLine("Use memory", "Recall and store memories during the run.", d.usesMemory) { v -> set { it.copy(usesMemory = v) } }
            } else {
                FieldLabel("Command")
                ZcTextField(d.command, { v -> set { it.copy(command = v) } }, "echo hello", mono = true, singleLine = false, maxLines = 8, modifier = Modifier.fillMaxWidth())
            }

            SectionHeader("Delivery", Modifier.padding(top = 16.dp))
            ZcSegmented(listOf("Keep in history", "Send to a channel"), if (d.announce) 1 else 0, { i -> set { it.copy(announce = i == 1) } })
            if (d.announce) {
                val channels = s.agents.firstOrNull { it.alias == d.agent }?.channels.orEmpty()
                FieldLabel("Channel")
                if (channels.isEmpty()) {
                    ZcTextField(d.channel, { v -> set { it.copy(channel = v) } }, "telegram.main", mono = true, modifier = Modifier.fillMaxWidth())
                } else {
                    Picker(d.channel.ifEmpty { "Pick a channel" }, channels.map { it to it }, { c -> set { it.copy(channel = c) } }, Modifier.fillMaxWidth())
                }
                FieldLabel("To")
                ZcTextField(d.to, { v -> set { it.copy(to = v) } }, "Chat or user id", mono = true, modifier = Modifier.fillMaxWidth())
                SwitchLine("Best effort", "A failed delivery doesn't fail the run.", d.bestEffort) { v -> set { it.copy(bestEffort = v) } }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        }
    }
    if (discardAsk) {
        ConfirmDialog(
            "Discard changes?", "Your edits to this job will be lost.", "Discard", danger = true,
            onConfirm = { discardAsk = false; onBack() }, onDismiss = { discardAsk = false },
        )
    }
}

private fun Modifier.clickableChip(onClick: () -> Unit) = this.then(
    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).clickable(onClick = onClick),
)

@Composable
private fun SwitchLine(title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ZcType.body)
            Text(caption, style = ZcType.caption)
        }
        ZcSwitch(checked, onChange)
    }
}

