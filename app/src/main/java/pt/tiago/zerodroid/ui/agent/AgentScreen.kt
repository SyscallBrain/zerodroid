package pt.tiago.zerodroid.ui.agent

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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.tiago.zerodroid.ui.kit.AgentState
import pt.tiago.zerodroid.ui.kit.Avatar
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.kit.pill
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Read-only summary of one agent, with shortcuts to chat, pin and edit it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(
    alias: String,
    onBack: () -> Unit,
    onOpenChat: (String, String) -> Unit,
    onOpenConfig: (String) -> Unit,
) {
    val vm: AgentViewModel = viewModel(key = "agent-$alias") { AgentViewModel(alias) }
    // The VM outlives the overlay; reload every time the screen opens so the numbers are current.
    androidx.compose.runtime.LaunchedEffect(alias) { vm.refresh() }
    val d by vm.details.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    val pinned = alias in pins
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ZcTopBar(
            alias,
            navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) },
            leading = { Avatar(alias, size = 34.dp) },
            subtitle = {
                val state = when {
                    d.status?.enabled == false -> AgentState.Offline
                    (d.status?.liveSessions ?: 0) > 0 -> AgentState.Working
                    else -> AgentState.Idle
                }
                Text(state.pill().first, style = ZcType.caption)
            },
            actions = {
                ZcIconButton(if (pinned) ZcIcons.StarFilled else ZcIcons.Star, if (pinned) "Unpin" else "Pin", tint = if (pinned) ZcTokens.attention else ZcTokens.muted, onClick = vm::togglePin)
            },
        )
        HorizontalDivider(color = ZcTokens.hairline)
        ConnectionBanner(d.error != null, d.error.orEmpty(), Tone.Danger)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ZcTokens.gutter, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(ZcTokens.gap),
        ) {
            if (d.loading) {
                SkeletonLines(5)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZcPrimaryButton(if (d.creating) "Starting…" else "New chat", icon = ZcIcons.Chats, enabled = !d.creating && d.status?.enabled != false, modifier = Modifier.weight(1f)) {
                    vm.newChat(onOpenChat)
                }
                ZcGhostButton("Open in Config", icon = ZcIcons.Config, modifier = Modifier.weight(1f)) { onOpenConfig(alias) }
            }
            SectionHeader("Model")
            ZcCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                InfoLine("Model", d.model?.model, mono = true)
                HorizontalDivider(color = ZcTokens.hairline)
                InfoLine("Provider", d.model?.provider, mono = true)
                HorizontalDivider(color = ZcTokens.hairline)
                InfoLine("Runtime profile", d.runtimeProfile)
                HorizontalDivider(color = ZcTokens.hairline)
                InfoLine("Risk profile", d.riskProfile)
            }
            SectionHeader("Activity")
            ZcCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                InfoLine("Live sessions", d.status?.liveSessions?.toString())
                HorizontalDivider(color = ZcTokens.hairline)
                InfoLine("Saved sessions", d.status?.persistedSessions?.toString())
                HorizontalDivider(color = ZcTokens.hairline)
                InfoLine("Spend today", d.costToday ?: "$0.00")
                HorizontalDivider(color = ZcTokens.hairline)
                InfoLine("Next cron", d.nextJob?.let { "${it.first} · ${it.second}" } ?: "none")
            }
            val channels = d.status?.channels.orEmpty()
            if (channels.isNotEmpty()) {
                SectionHeader("Channels")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    channels.forEach { StatusPill(it, Tone.Live, dot = false) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = ZcType.body, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value ?: "—",
            style = if (mono) ZcType.monoSmall.copy(color = ZcTokens.muted) else ZcType.small,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
