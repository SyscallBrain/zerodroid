package pt.tiago.zerodroid.ui.sop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tiago.zerodroid.rpc.proto.SopRunDto
import pt.tiago.zerodroid.ui.chats.relativeTime
import pt.tiago.zerodroid.ui.kit.Avatar
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

const val ENGINE_OFF_TEXT = "Workflows can't run yet. Enable the SOP engine to run them."

@Composable
fun SopListScreen(
    vm: SopListViewModel,
    onBack: () -> Unit,
    onOpen: (String?) -> Unit,
    onRun: (String) -> Unit,
    onOpenRun: (String, String) -> Unit,
    onEnable: () -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Column(Modifier.fillMaxSize()) {
            ZcTopBar("Workflows", navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) })
            ConnectionBanner(s.engineOff, ENGINE_OFF_TEXT, Tone.Attention, action = "Enable", onAction = onEnable)
            ConnectionBanner(s.error != null, s.error ?: "", Tone.Danger, action = "Retry", onAction = { vm.consumeError(); vm.refresh() })
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ZcTokens.gutter, end = ZcTokens.gutter, top = 6.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(ZcTokens.gap),
            ) {
                if (s.cards.size > 3 || s.query.isNotEmpty()) {
                    item("search") {
                        ZcTextField(s.query, vm::setQuery, "Search workflows", leadingIcon = ZcIcons.Search, modifier = Modifier.fillMaxWidth())
                    }
                }
                when {
                    s.loading -> item("sk") { SkeletonLines(4) }
                    s.cards.isEmpty() -> item("empty") {
                        EmptyState(
                            ZcIcons.Bolt, "No workflows yet", "A workflow is a list of steps your agent follows, wired like a flowchart.",
                            action = { ZcPrimaryButton("New workflow", icon = ZcIcons.Plus) { onOpen(null) } },
                        )
                    }
                    else -> items(s.visible, key = { it.name }) { c ->
                        SopCard(c, onOpen = { onOpen(c.name) }, onRun = { onRun(c.name) }, onOpenRun = { r -> onOpenRun(c.name, r) })
                    }
                }
            }
        }
        if (!s.loading && s.cards.isNotEmpty()) {
            ZcPrimaryButton(
                "New workflow",
                icon = ZcIcons.Plus,
                modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBars).padding(ZcTokens.gutter),
            ) { onOpen(null) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SopCard(c: SopCardModel, onOpen: () -> Unit, onRun: () -> Unit, onOpenRun: (String) -> Unit) {
    ZcCard(Modifier.fillMaxWidth(), onClick = onOpen, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Avatar(c.name, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name.trim(), style = ZcType.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.description.isNotBlank()) {
                    Text(c.description, style = ZcType.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            c.last?.let { r -> val (t, tone) = runPill(r); Spacer(Modifier.width(8.dp)); StatusPill(t, tone) }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            c.triggers.forEach { StatusPill(triggerTypeLabel(it), Tone.Live, dot = false) }
            StatusPill("${c.steps} step${if (c.steps == 1) "" else "s"}", Tone.Neutral, dot = false)
            Spacer(Modifier.weight(1f))
            val live = c.last?.takeIf { !isTerminalRun(it.status) }
            when {
                live != null -> ZcGhostButton("View run", compact = true) { onOpenRun(live.runId) }
                c.canRun -> ZcGhostButton("Run", icon = ZcIcons.Send, compact = true, onClick = onRun)
            }
        }
    }
}

fun isTerminalRun(status: String) = status in setOf("completed", "failed", "cancelled")

fun triggerTypeLabel(type: String): String = when (type) {
    "manual" -> "Manual"
    "cron" -> "Schedule"
    "channel" -> "Channel"
    "mqtt" -> "MQTT"
    "amqp" -> "AMQP"
    else -> type.replaceFirstChar { it.uppercase() }
}

/** Text and tone for a run's status pill. */
fun runPill(r: SopRunDto): Pair<String, Tone> = when (r.status) {
    "waiting_approval", "paused_checkpoint" -> "Needs approval" to Tone.Attention
    "running", "pending" -> "Running" to Tone.Live
    "failed" -> "Failed" to Tone.Danger
    "cancelled" -> "Cancelled" to Tone.Neutral
    "completed" -> (r.completedAt?.let { relativeTime(it) }?.takeIf { it.isNotEmpty() }?.let { "Done · $it" } ?: "Done") to Tone.Success
    else -> r.status.replace('_', ' ') to Tone.Neutral
}
