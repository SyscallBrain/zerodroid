package pt.tiago.zerodroid.ui.cron

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcDangerButton
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.sop.ConfirmDialog
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationDetailScreen(vm: AutomationDetailViewModel, onBack: () -> Unit, onEdit: () -> Unit, onOutput: (String, String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }
    var deleteAsk by remember { mutableStateOf(false) }
    val job = s.job
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ZcTopBar(job?.let(::jobTitle) ?: "Job", navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) })
        ConnectionBanner(s.error != null, s.error ?: "", Tone.Danger, action = "OK", onAction = vm::consumeError)
        if (job == null) {
            SkeletonLines(4, Modifier.padding(ZcTokens.gutter))
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ZcTokens.gutter, end = ZcTokens.gutter, top = 6.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(ZcTokens.gap),
        ) {
            item("facts") { Facts(job) }
            item("actions") {
                val editable = s.readOnlyReason == null
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A declarative job can still be run by hand; a forbidden agent job can't.
                    val canRun = s.readOnlyReason != NEEDS_ADMIN_NOTE
                    ZcPrimaryButton(if (s.running) "Running…" else "Run now", icon = ZcIcons.Play, enabled = canRun && !s.running, compact = true, onClick = vm::runNow)
                    if (editable) {
                        ZcGhostButton(if (job.enabled) "Pause" else "Resume", enabled = !s.busy, compact = true) { vm.setEnabled(!job.enabled) }
                        ZcGhostButton("Edit", icon = ZcIcons.Wrench, compact = true, onClick = onEdit)
                        ZcDangerButton("Delete", icon = ZcIcons.Trash, enabled = !s.busy, compact = true) { deleteAsk = true }
                    }
                }
                s.readOnlyReason?.let { Note(it) }
            }
            if (s.running || s.lastTrigger != null) item("trigger") {
                ZcCard(Modifier.fillMaxWidth(), onClick = s.lastTrigger?.let { t -> { onOutput("Run output", t.output) } }, padding = PaddingValues(14.dp)) {
                    val t = s.lastTrigger
                    if (s.running || t == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ZcTokens.accentStart)
                            Spacer(Modifier.width(10.dp))
                            Text("Running on the daemon. You can leave this screen.", style = ZcType.small)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (label, tone) = statusPill(t.status)
                            StatusPill(label, tone)
                            Spacer(Modifier.width(8.dp))
                            Text(durationText(t.durationMs), style = ZcType.caption)
                        }
                        Text(
                            t.output.lineSequence().take(6).joinToString("\n").ifEmpty { "(no output)" },
                            style = ZcType.mono.copy(color = ZcTokens.muted), modifier = Modifier.padding(top = 8.dp),
                            maxLines = 6, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item("history-h") { SectionHeader("History", Modifier.padding(top = 8.dp)) }
            if (s.runs.isEmpty()) item("history-empty") { Text("No runs yet.", style = ZcType.small) }
            items(s.runs, key = { "r-${it.id}" }) { r ->
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOutput(localTime(r.startedAt), r.output.orEmpty()) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(localTime(r.startedAt), style = ZcType.body, modifier = Modifier.weight(1f))
                        Text(durationText(r.durationMs), style = ZcType.caption)
                        Spacer(Modifier.width(10.dp))
                        val (label, tone) = statusPill(r.status)
                        StatusPill(label, tone)
                    }
                    HorizontalDivider(color = ZcTokens.hairline)
                }
            }
            if (s.canLoadMore) item("more") { ZcGhostButton("Show older runs", compact = true, onClick = vm::loadMore) }
        }
    }
    if (deleteAsk) {
        ConfirmDialog(
            "Delete this job?", "It stops running and its history is removed. This can't be undone.", "Delete", danger = true,
            onConfirm = { deleteAsk = false; vm.delete() }, onDismiss = { deleteAsk = false },
        )
    }
}

@Composable
private fun Facts(j: CronJob) {
    ZcCard(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (j.isAgent) "Agent job" else "Shell job", style = ZcType.caption, modifier = Modifier.weight(1f))
            val (t, tone) = jobPill(j)
            StatusPill(t, tone)
        }
        Spacer(Modifier.height(8.dp))
        Fact("Agent", j.agentAlias ?: "—")
        Fact("Schedule", scheduleText(j))
        if (j.enabled) Fact("Next run", "${localTime(j.nextRun)} (${untilText(j.nextRun)})")
        Fact("Last run", localTime(j.lastRun))
        if (j.isAgent) {
            Fact("Model", j.model ?: "agent default")
            Fact("Session", if (j.sessionTarget == "main") "Main" else "Isolated")
            j.allowedTools?.let { Fact("Tools", it.joinToString(", ")) }
        }
        if (j.delivery.mode.equals("announce", true)) Fact("Delivery", "${j.delivery.channel} → ${j.delivery.to}")
        Text(
            (if (j.isAgent) j.prompt.orEmpty() else j.command).ifEmpty { "—" },
            style = ZcType.mono.copy(color = ZcTokens.muted), modifier = Modifier.padding(top = 10.dp), maxLines = 8, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = ZcType.caption, modifier = Modifier.width(88.dp))
        Text(value, style = ZcType.small.copy(color = ZcTokens.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Note(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ZcIcons.Shield, null, tint = ZcTokens.faint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = ZcType.caption.copy(color = ZcTokens.faint))
    }
}
