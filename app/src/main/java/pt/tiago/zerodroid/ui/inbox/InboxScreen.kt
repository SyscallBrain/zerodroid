package pt.tiago.zerodroid.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pt.tiago.zerodroid.data.ApprovalStatus
import pt.tiago.zerodroid.data.SopGate
import pt.tiago.zerodroid.data.SopGatesResult
import pt.tiago.zerodroid.ui.chat.ApprovalCard
import pt.tiago.zerodroid.ui.chat.approvalOutcome
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.Skeleton
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.clickableNoRipple
import pt.tiago.zerodroid.ui.kit.ZcDangerButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    active: Boolean = true,
    onOpenChat: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the live run of a SOP gate: (workflow name, run id). */
    onOpenRun: (String, String) -> Unit = { _, _ -> },
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val m by viewModel.model.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel, active) {
        if (active) viewModel.onVisible()
        onPauseOrDispose { viewModel.onHidden() }
    }
    LaunchedEffect(m.message) { if (m.message != null) { delay(4_000); viewModel.consumeMessage() } }
    val gates = (m.gates as? SopGatesResult.Ok)?.gates.orEmpty()
    val nothing = pending.isEmpty() && gates.isEmpty()

    Column(modifier.fillMaxSize()) {
        ZcTopBar("Inbox")
        ConnectionBanner(m.message != null, m.message.orEmpty(), Tone.Neutral)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ZcTokens.gutter, end = ZcTokens.gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ZcTokens.gap),
        ) {
            if (nothing && m.gates != null) {
                item("empty") {
                    EmptyState(
                        ZcIcons.Inbox,
                        "Nothing waiting on you",
                        "Approvals from desktop or Telegram sessions appear in those clients.",
                    )
                }
            }
            if (pending.isNotEmpty()) {
                item("needs-h") { SectionHeader("Needs you · ${pending.size}") }
                items(pending, key = { "p-" + it.requestId }) { a ->
                    ApprovalCard(
                        toolName = a.toolName,
                        summary = a.summary,
                        deadlineAt = a.deadlineAt,
                        status = a.status,
                        answeredAt = a.answeredAt,
                        agentAlias = a.agentAlias,
                        onDecide = { d -> viewModel.decideApproval(a.requestId, d) },
                        onOpen = { onOpenChat(a.sessionId, a.agentAlias) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            when (val g = m.gates) {
                null -> item("gates-loading") {
                    Column {
                        SectionHeader("SOP decisions")
                        Skeleton(Modifier.fillMaxWidth().height(72.dp))
                    }
                }
                is SopGatesResult.Ok -> if (g.gates.isNotEmpty()) {
                    item("gates-h") { SectionHeader("SOP decisions · ${g.gates.size}") }
                    items(g.gates, key = { "g-" + it.runId }) { gate ->
                        GateCard(gate, deciding = gate.runId in m.deciding, onDecide = { viewModel.decideGate(gate, it) }, onOpen = { onOpenRun(gate.sopName, gate.runId) }, modifier = Modifier.animateItem())
                    }
                }
                SopGatesResult.Forbidden -> item("gates-forbidden") {
                    Note("SOP decisions need admin access for this device.")
                }
                SopGatesResult.Unavailable -> Unit
                is SopGatesResult.Error -> item("gates-err") { Note("SOP decisions unavailable: ${g.message}") }
            }
            if (recent.isNotEmpty()) {
                item("recent-h") { SectionHeader("Recently answered", Modifier.padding(top = 8.dp)) }
                items(recent, key = { "r-" + it.requestId }) { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).animateItem(), verticalAlignment = Alignment.CenterVertically) {
                        val good = a.status == ApprovalStatus.Approved || a.status == ApprovalStatus.AlwaysApproved
                        Icon(
                            if (good) ZcIcons.Check else ZcIcons.Close, null,
                            tint = if (good) ZcTokens.success else if (a.status == ApprovalStatus.Denied) ZcTokens.danger else ZcTokens.faint,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${a.agentAlias} · ${a.toolName}", style = ZcType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(approvalOutcome(a.status), style = ZcType.caption)
                        }
                        a.answeredAt?.let {
                            Text(HM.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())), style = ZcType.caption.copy(color = ZcTokens.faint))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GateCard(gate: SopGate, deciding: Boolean, onDecide: (Boolean) -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    ZcCard(modifier.fillMaxWidth(), border = ZcTokens.attention.copy(alpha = 0.25f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(gate.sopName, style = ZcType.bodyStrong, modifier = Modifier.weight(1f))
            Text("Open run", style = ZcType.caption.copy(color = ZcTokens.accentStart), modifier = Modifier.clickableNoRipple(onOpen))
        }
        Text(
            listOfNotNull(if (gate.checkpoint) "Checkpoint" else "Approval gate", gate.stepLabel).joinToString(" · "),
            style = ZcType.caption.copy(color = ZcTokens.attention),
        )
        Text("Run ${gate.runId.take(12)}", style = ZcType.monoSmall.copy(color = ZcTokens.faint), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZcDangerButton("Deny", enabled = !deciding, compact = true, modifier = Modifier.weight(1f)) { onDecide(false) }
            ZcPrimaryButton("Approve", enabled = !deciding, compact = true, modifier = Modifier.weight(1f)) { onDecide(true) }
        }
    }
}

@Composable
private fun Note(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ZcIcons.Shield, null, tint = ZcTokens.faint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = ZcType.caption.copy(color = ZcTokens.faint))
    }
}
