package pt.tiago.zerodroid.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.data.ApprovalStatus
import pt.tiago.zerodroid.data.ChatItem
import pt.tiago.zerodroid.data.ToolStatus
import pt.tiago.zerodroid.ui.chat.ApprovalCard
import pt.tiago.zerodroid.ui.chat.ToolChip
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Debug-only showroom of every kit component in its states (long-press the Home logo). */
@Composable
fun KitGallery(onClose: () -> Unit) {
    var seg by remember { mutableIntStateOf(1) }
    var sw by remember { mutableStateOf(true) }
    var tab by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ZcTopBar("Kit gallery", navigation = { ZcIconButton(ZcIcons.Back, "Close", onClick = onClose) })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ZcTokens.gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("Buttons")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZcPrimaryButton("Primary", Modifier.weight(1f)) {}
                ZcGhostButton("Ghost", Modifier.weight(1f)) {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZcDangerButton("Danger", Modifier.weight(1f)) {}
                ZcPrimaryButton("Disabled", Modifier.weight(1f), enabled = false) {}
            }
            SectionHeader("Pills")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill("live", Tone.Live, pulse = true)
                StatusPill("needs you", Tone.Attention)
                StatusPill("ok", Tone.Success)
                StatusPill("failed", Tone.Danger)
                StatusPill("idle", Tone.Neutral)
            }
            SectionHeader("Attention")
            AttentionCard("2 approvals waiting", "researcher · shell, +1") {}
            SectionHeader("Tiles")
            Row(horizontalArrangement = Arrangement.spacedBy(ZcTokens.gap)) {
                ZcTile("Cost today", "$1.84", Modifier.weight(1f), suffix = "/ $10")
                ZcTile("Uptime", null, Modifier.weight(1f), loading = true)
            }
            SectionHeader("Agents")
            AgentRow("researcher", "opus · running shell", AgentState.NeedsYou)
            AgentRow("coder", "sonnet · 3 sessions", AgentState.Working)
            AgentRow("scribe", "ready", AgentState.Idle)
            SectionHeader("Controls")
            ZcSegmented(listOf("Read-only", "Supervised", "Full"), seg, { seg = it })
            Row { Text("Approve shell commands", style = ZcType.body, modifier = Modifier.weight(1f)); ZcSwitch(sw, { sw = it }) }
            ZcTextField(text, { text = it }, "Search 412 settings", leadingIcon = ZcIcons.Search, modifier = Modifier.fillMaxWidth())
            SectionHeader("Chat")
            ToolChip(ChatItem.Tool("t1", "c1", "shell", "df -h /", ToolStatus.Ok, "Filesystem  Size  Used\n/dev/root   50G   43G", 0, 400))
            ToolChip(ChatItem.Tool("t2", "c2", "web_fetch", "https://example.com", ToolStatus.Running, null, 0))
            ApprovalCard("shell", "docker image prune -a --filter \"until=720h\"", System.currentTimeMillis() + 90_000, ApprovalStatus.Pending, null, {})
            ApprovalCard("shell", "rm -rf /tmp/x", 0, ApprovalStatus.Approved, System.currentTimeMillis(), {})
            MarkdownText("Disk is at **87%** on `/`. Most of it is unused Docker images:\n\n- images older than 30 days\n- 14.2 GB in total\n\n```\ndocker system df\n```")
            SectionHeader("Loading")
            SkeletonLines(3)
            ConnectionBanner(true, "Daemon unreachable · retrying in 4s", Tone.Danger, action = "Retry now", onAction = {})
            EmptyState(ZcIcons.Inbox, "Nothing waiting on you", "Approvals from desktop or Telegram sessions appear in those clients.")
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        }
        ZcTabBar(
            listOf(ZcTab("Home", ZcIcons.Home), ZcTab("Chats", ZcIcons.Chats), ZcTab("Inbox", ZcIcons.Inbox, 2), ZcTab("Config", ZcIcons.Config)),
            tab, { tab = it },
        )
    }
}
