package pt.tiago.zerodroid.ui.cron

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationListScreen(vm: AutomationListViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Runs every time the list comes back on top (after a detail or an edit).
    LaunchedEffect(Unit) { vm.refresh() }
    var settingsOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Column(Modifier.fillMaxSize()) {
            ZcTopBar(
                "Automation",
                navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) },
                actions = { ZcIconButton(ZcIcons.Config, "Scheduler settings", onClick = { settingsOpen = true }) },
            )
            ConnectionBanner(s.error != null, s.error ?: "", Tone.Danger, action = "Retry", onAction = { vm.consumeError(); vm.refresh() })
            if (s.settings?.enabled == false) {
                ConnectionBanner(true, "The scheduler is off: jobs only run when you start them.", Tone.Attention)
            }
            PullToRefreshBox(isRefreshing = false, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = ZcTokens.gutter, end = ZcTokens.gutter, top = 6.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(ZcTokens.gap),
                ) {
                    when {
                        s.loading -> item("sk") { SkeletonLines(4) }
                        s.jobs.isEmpty() -> item("empty") {
                            EmptyState(
                                ZcIcons.Clock, "No scheduled jobs", "A job runs a command or asks an agent something on a schedule.",
                                action = { ZcPrimaryButton("New job", icon = ZcIcons.Plus, onClick = onNew) },
                            )
                        }
                        else -> items(s.jobs, key = { it.id }) { j -> JobRow(j, onClick = { onOpen(j.id) }) }
                    }
                }
            }
        }
        if (!s.loading && s.jobs.isNotEmpty()) {
            ZcPrimaryButton(
                "New job", icon = ZcIcons.Plus,
                modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBars).padding(ZcTokens.gutter),
                onClick = onNew,
            )
        }
    }
    if (settingsOpen) {
        SchedulerSheet(s.settings, forbidden = s.settingsForbidden, saving = s.savingSettings, onChange = vm::setSettings, onDismiss = { settingsOpen = false })
    }
}

@Composable
private fun JobRow(j: CronJob, onClick: () -> Unit) {
    ZcCard(Modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(jobTitle(j), style = ZcType.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(j.agentAlias, if (j.isAgent) "agent" else "shell").joinToString(" · "),
                    style = ZcType.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val (t, tone) = jobPill(j)
            StatusPill(t, tone)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(scheduleText(j), style = ZcType.mono.copy(color = ZcTokens.muted), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (j.enabled) Text(untilText(j.nextRun), style = ZcType.caption.copy(color = ZcTokens.accentStart))
        }
    }
}
