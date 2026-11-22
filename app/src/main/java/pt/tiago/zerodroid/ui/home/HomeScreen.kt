package pt.tiago.zerodroid.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import pt.tiago.zerodroid.ui.kit.StatusDot
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.clickableNoRipple
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tiago.zerodroid.ui.kit.AgentRow
import pt.tiago.zerodroid.ui.kit.AttentionCard
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.Skeleton
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcTile
import pt.tiago.zerodroid.ui.kit.color
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType
import java.time.LocalTime

private const val AGENTS_FOLDED = 6

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    /** False while another tab or a chat covers Home: polling pauses. */
    active: Boolean = true,
    onOpenInbox: () -> Unit,
    onBrandLongPress: () -> Unit,
    onOpenAgent: (String) -> Unit = {},
    onOpenWorkflows: () -> Unit = {},
    /** SOP runs waiting for a decision: tints the Workflows tile. */
    workflowsWaiting: Int = 0,
    modifier: Modifier = Modifier,
) {
    val workflows by viewModel.workflows.collectAsStateWithLifecycle()
    val m by viewModel.model.collectAsStateWithLifecycle()
    pt.tiago.zerodroid.RecomposeProbe("HomeScreen")
    pt.tiago.zerodroid.Perf.mark("home.firstComposition")
    if (m.loaded) pt.tiago.zerodroid.Perf.mark("home.loaded")
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var showAll by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel, active) {
        if (active) viewModel.onVisible()
        onPauseOrDispose { viewModel.onHidden() }
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::pullToRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ZcTokens.gutter, end = ZcTokens.gutter, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ZcTokens.gap),
        ) {
            item("top") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(ZcTokens.accent)
                            .combinedClickable(onClick = {}, onLongClick = onBrandLongPress),
                        contentAlignment = Alignment.Center,
                    ) { Icon(ZcIcons.Bolt, "ZeroDroid", tint = ZcTokens.onAccent, modifier = Modifier.size(19.dp)) }
                    Spacer(Modifier.weight(1f))
                    StatusPill(m.connection.label, m.connection.tone, pulse = m.connection.pulse)
                }
            }
            item("headline") {
                Spacer(Modifier.height(14.dp))
                Text(greeting(), style = ZcType.small)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildAnnotatedString {
                        if (m.headlineLead.isNotEmpty()) append(m.headlineLead + " ")
                        if (m.headlineTone == Tone.Live) {
                            withStyle(SpanStyle(brush = ZcTokens.accentHorizontal)) { append(m.headlineAccent) }
                        } else {
                            withStyle(SpanStyle(color = if (m.headlineTone == Tone.Neutral) ZcTokens.text else m.headlineTone.color())) { append(m.headlineAccent) }
                        }
                    },
                    style = ZcType.display,
                )
                if (m.daemonVersion != null) {
                    Text("ZeroClaw ${m.daemonVersion}", style = ZcType.caption.copy(color = ZcTokens.faint), modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (m.hint != null) {
                item("hint") {
                    ConnectionBanner(
                        visible = true,
                        text = if (m.stale) "${m.hint}\nShowing the last known values." else m.hint!!,
                        tone = Tone.Danger,
                        modifier = Modifier.padding(horizontal = 0.dp),
                        action = "Retry now",
                        onAction = viewModel::pullToRefresh,
                    )
                }
            }
            if (m.attention > 0) {
                item("attention") {
                    AttentionCard(
                        title = "${m.attention} approval${if (m.attention == 1) "" else "s"} waiting",
                        subtitle = m.attentionSubtitle ?: "Open the Inbox to answer",
                        onClick = onOpenInbox,
                    )
                }
            }
            item("tiles") {
                val loading = !m.loaded
                Column2x2(
                    a = {
                        ZcTile(
                            "Memory", m.memory.value, caption = m.memory.caption, stale = m.stale, loading = loading, modifier = it,
                            footer = m.memory.fraction?.let { f -> { BudgetBar(f) } },
                        )
                    },
                    b = {
                        ZcTile(
                            "CPU", m.cpu.value, caption = m.cpu.suffix, stale = m.stale, loading = loading, modifier = it,
                            footer = m.cpu.fraction?.let { f -> { BudgetBar(f) } },
                        )
                    },
                    c = { ZcTile("Uptime", m.uptimeText, stale = m.stale, loading = loading, modifier = it) },
                    d = { ZcTile("Sessions", m.activeSessions?.toString(), suffix = "active", stale = m.stale, loading = loading, modifier = it) },
                )
            }
            item("workflows") {
                ZcTile(
                    "Workflows",
                    workflows?.toString(),
                    suffix = "saved",
                    caption = if (workflowsWaiting > 0) "$workflowsWaiting waiting for you" else "Step-by-step procedures",
                    loading = !m.loaded,
                    onClick = onOpenWorkflows,
                    modifier = Modifier.fillMaxWidth(),
                    footer = if (workflowsWaiting > 0) { { pt.tiago.zerodroid.ui.kit.StatusPill("Needs approval", Tone.Attention) } } else null,
                )
            }
            if (m.core.isNotEmpty() || m.channels.isNotEmpty()) {
                item("system") { SystemCard(m, Modifier.alpha(if (m.stale) 0.6f else 1f)) }
            }
            if (m.upNext.isNotEmpty()) {
                item("next-h") { SectionHeader("Up next", Modifier.padding(top = 10.dp)) }
                item("next") {
                    ZcCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        m.upNext.forEachIndexed { i, (time, label) ->
                            if (i > 0) HorizontalDivider(color = ZcTokens.hairline)
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(ZcIcons.Clock, null, tint = ZcTokens.muted, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(label, style = ZcType.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(time, style = ZcType.mono.copy(color = ZcTokens.accentStart))
                            }
                        }
                    }
                }
            }
            if (m.pinned.isNotEmpty()) {
                item("pinned-h") { SectionHeader("Pinned", Modifier.padding(top = 10.dp)) }
                items(m.pinned, key = { "p-" + it.alias }) { a -> HomeAgentRow(a, true, m.stale, viewModel, onOpenAgent, Modifier.animateItem()) }
            }
            item("agents-h") {
                SectionHeader(
                    if (m.agents.isEmpty()) "Agents" else "Agents · ${m.agents.size}",
                    Modifier.padding(top = 10.dp),
                    action = if (m.agents.size > AGENTS_FOLDED) (if (showAll) "Show less" else "See all") else null,
                    onAction = { showAll = !showAll },
                )
            }
            if (!m.loaded) {
                items(3, key = { "sk$it" }) {
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Skeleton(Modifier.size(38.dp))
                        Spacer(Modifier.size(12.dp))
                        Skeleton(Modifier.fillMaxWidth(0.6f).height(14.dp))
                    }
                }
            } else if (m.agents.isEmpty() && m.pinned.isEmpty()) {
                item("no-agents") {
                    EmptyState(ZcIcons.Sparkle, "No agents", "This daemon has no agents configured, or this device may not list them.")
                }
            } else {
                val shown = if (showAll) m.agents else m.agents.take(AGENTS_FOLDED)
                items(shown, key = { "a-" + it.alias }) { a -> HomeAgentRow(a, false, m.stale, viewModel, onOpenAgent, Modifier.animateItem()) }
            }
        }
    }
}

@Composable
private fun HomeAgentRow(a: AgentRowModel, pinned: Boolean, stale: Boolean, vm: HomeViewModel, onOpen: (String) -> Unit, modifier: Modifier) {
    AgentRow(
        a.alias, a.subtitle, a.state,
        modifier = modifier.alpha(if (stale) 0.5f else 1f),
        onClick = { onOpen(a.alias) },
        trailing = {
            ZcIconButton(
                if (pinned) ZcIcons.StarFilled else ZcIcons.Star,
                if (pinned) "Unpin ${a.alias}" else "Pin ${a.alias}",
                tint = if (pinned) ZcTokens.attention else ZcTokens.faint,
            ) { vm.togglePin(a.alias) }
        },
    )
}

private fun Health.tone(): Tone = when (this) {
    Health.Ok -> Tone.Success
    Health.Warn -> Tone.Attention
    Health.Error -> Tone.Danger
    Health.Unknown -> Tone.Neutral
}

/** Component health: core chips, channel rows, and the spend footer. Tap a failing row for its error. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemCard(m: HomeModel, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier) {
        SectionHeader("System", Modifier.padding(top = 10.dp))
        ZcCard(Modifier.fillMaxWidth()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                m.core.forEach { c ->
                    Box(Modifier.clickableNoRipple { if (c.detail != null) open = if (open == c.key) null else c.key }) {
                        StatusPill(c.label, c.health.tone())
                    }
                }
            }
            m.core.firstOrNull { it.key == open }?.detail?.let {
                Text(it, style = ZcType.caption.copy(color = ZcTokens.danger), modifier = Modifier.padding(top = 8.dp))
            }
            if (m.channels.isNotEmpty()) {
                val folded = foldChannels(m.channels)
                var showAllChannels by rememberSaveable { mutableStateOf(false) }
                Spacer(Modifier.height(12.dp))
                Text("CHANNELS", style = ZcType.label)
                val rows = if (showAllChannels) m.channels else folded.problems
                rows.forEach { c ->
                    Column(Modifier.fillMaxWidth().clickableNoRipple { if (c.detail != null) open = if (open == c.key) null else c.key }.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(c.health.tone())
                            Spacer(Modifier.width(10.dp))
                            Text(c.label, style = ZcType.body, modifier = Modifier.weight(1f))
                            if (c.detail != null) Icon(ZcIcons.ChevronDown, null, tint = ZcTokens.faint, modifier = Modifier.size(14.dp))
                        }
                        if (open == c.key && c.detail != null) {
                            Text(c.detail, style = ZcType.caption.copy(color = ZcTokens.danger), modifier = Modifier.padding(start = 17.dp, top = 2.dp))
                        }
                    }
                }
                if (folded.healthy > 0) {
                    Row(
                        Modifier.fillMaxWidth().clickableNoRipple { showAllChannels = !showAllChannels }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(Tone.Success)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (showAllChannels) "Show only problems" else "${folded.healthy} channel${if (folded.healthy == 1) "" else "s"} running",
                            style = ZcType.body.copy(color = if (showAllChannels) ZcTokens.accentStart else ZcTokens.text),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(ZcIcons.ChevronDown, null, tint = ZcTokens.faint, modifier = Modifier.size(14.dp).rotate(if (showAllChannels) 180f else 0f))
                    }
                }
            }
            if (m.costToday != null || m.costMonth != null) {
                HorizontalDivider(color = ZcTokens.hairline, modifier = Modifier.padding(vertical = 10.dp))
                Text(
                    listOfNotNull(m.costToday?.let { "Spend today $it" }, m.costMonth?.let { "this month $it" }).joinToString(" · "),
                    style = ZcType.caption,
                )
            }
        }
    }
}

@Composable
private fun Column2x2(
    a: @Composable (Modifier) -> Unit,
    b: @Composable (Modifier) -> Unit,
    c: @Composable (Modifier) -> Unit,
    d: @Composable (Modifier) -> Unit,
) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(ZcTokens.gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZcTokens.gap)) {
            a(Modifier.weight(1f))
            b(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZcTokens.gap)) {
            c(Modifier.weight(1f))
            d(Modifier.weight(1f))
        }
    }
}

@Composable
private fun BudgetBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ZcTokens.raised)) {
        Box(
            Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (fraction > 0.85f) androidx.compose.ui.graphics.SolidColor(ZcTokens.attention) else ZcTokens.accentHorizontal),
        )
    }
}

private fun greeting(): String {
    val h = LocalTime.now().hour
    return when {
        h < 5 -> "Good night"
        h < 12 -> "Good morning"
        h < 18 -> "Good afternoon"
        else -> "Good evening"
    }
}
