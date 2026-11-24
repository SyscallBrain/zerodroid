package pt.tiago.zerodroid.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tiago.zerodroid.data.skills.AgentSkill
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcCard
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import pt.tiago.zerodroid.rpc.proto.AgentEntry
import pt.tiago.zerodroid.ui.kit.Avatar
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsListScreen(vm: SkillsListViewModel, onBack: () -> Unit, onOpen: (bundle: String, name: String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Runs every time the list comes back on top (after an edit or a delete).
    LaunchedEffect(Unit) { vm.refresh() }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val filtered = s.skills.filter {
        q.isEmpty() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q) || (it.bundle ?: "").lowercase().contains(q)
    }
    val byBundle = filtered.filter { it.bundle != null }.groupBy { it.bundle!! }.toSortedMap()
    val workspace = filtered.filter { it.bundle == null }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ZcTopBar(
            "Skills",
            subtitle = {
                if (!s.loading && s.selectedAgent != null) {
                    Text(
                        "${filtered.size} of ${s.skills.size} skills · ${s.selectedAgent}",
                        style = ZcType.caption.copy(color = ZcTokens.faint), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) },
            actions = { ZcIconButton(ZcIcons.Refresh, "Reload", onClick = vm::refresh) },
        )
        ConnectionBanner(s.error != null, s.error ?: "", Tone.Danger, action = "Retry", onAction = { vm.consumeError(); vm.refresh() })
        if (s.forbidden) {
            ConnectionBanner(true, "This device can't read skill bundles. Ask an operator to grant skills access.", Tone.Attention)
        }
        if (s.agents.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ZcTokens.gutter, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentChip(
                    selected = s.selectedAgent,
                    agents = s.agents,
                    onSelect = vm::selectAgent,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                ZcTextField(
                    query, { query = it }, "Search skills", leadingIcon = ZcIcons.Search,
                    modifier = Modifier.weight(1f),
                    trailing = if (query.isNotEmpty()) {
                        { ZcIconButton(ZcIcons.Close, "Clear search", modifier = Modifier.size(28.dp), onClick = { query = "" }) }
                    } else null,
                )
            }
        }
        if (!s.loading && s.selectedAgent != null && s.bundleSkills.isEmpty() && s.error == null && !s.forbidden) {
            val agent = s.selectedAgent!!
            Text(
                if (s.agentBundles.isEmpty()) {
                    val db = if (s.daemonBundles.isEmpty()) "none configured" else s.daemonBundles.joinToString()
                    "No bundles assigned to $agent (agents.$agent.skill_bundles) · daemon bundles: $db"
                } else {
                    "Bundles ${s.agentBundles.joinToString()} assigned to $agent, but no skills listed in them"
                },
                style = ZcType.caption.copy(color = ZcTokens.faint),
                modifier = Modifier.padding(horizontal = ZcTokens.gutter, vertical = 6.dp),
            )
        }
        PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = vm::pullToRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ZcTokens.gutter, end = ZcTokens.gutter, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(ZcTokens.gap),
            ) {
                when {
                    s.loading -> item("sk") { SkeletonLines(4) }
                    s.agents.isEmpty() -> item("empty") {
                        EmptyState(ZcIcons.Book, "No agents", "This daemon has no agents configured, or this device may not list them.")
                    }
                    filtered.isEmpty() -> item("empty") {
                        EmptyState(ZcIcons.Book, "No skills", if (q.isEmpty()) "${s.selectedAgent} has no skills in its bundles or workspace." else "Nothing matches \"$query\".")
                    }
                    else -> {
                        byBundle.forEach { (bundle, skills) ->
                            item("h-$bundle") { SectionHeader("$bundle · ${skills.size}", Modifier.padding(top = 10.dp)) }
                            items(skills, key = { "b-$bundle-${it.name}" }) { skill -> SkillRow(skill, onClick = { onOpen(bundle, skill.name) }) }
                        }
                        if (workspace.isNotEmpty()) {
                            item("h-ws") { SectionHeader("Workspace · ${workspace.size} · read-only", Modifier.padding(top = 10.dp)) }
                            items(workspace, key = { "w-${it.name}" }) { SkillRow(it, onClick = null) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The agent selector as a chip next to the search field: the same monogram avatar the
 * agent rows use, the alias, and a chevron. The dropdown is as wide as the chip and
 * scrolls when the daemon has many agents.
 */
@Composable
private fun AgentChip(selected: String?, agents: List<AgentEntry>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier.onSizeChanged { widthPx = it.width }) {
        Row(
            Modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(ZcTokens.inputRadius))
                .background(ZcTokens.raised).border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(ZcTokens.inputRadius))
                .clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(selected ?: "?", size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                selected ?: "Agent",
                style = ZcType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            Icon(ZcIcons.ChevronDown, null, tint = ZcTokens.muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            open, { open = false }, containerColor = ZcTokens.raised,
            modifier = Modifier.width(with(density) { widthPx.toDp() }.coerceAtLeast(200.dp)).heightIn(max = 380.dp),
        ) {
            agents.forEach { a ->
                DropdownMenuItem(
                    text = { Text(if (a.enabled) a.alias else "${a.alias} (off)", style = ZcType.body, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Avatar(a.alias, size = 24.dp) },
                    onClick = { open = false; onSelect(a.alias) },
                )
            }
        }
    }
}

/** Stable per-bundle hue, same palette the agent avatars use. */
private val bundleHues = listOf(ZcTokens.accentStart, ZcTokens.accentEnd, ZcTokens.success, ZcTokens.attention, ZcTokens.danger)

private fun bundleHue(alias: String): androidx.compose.ui.graphics.Color =
    bundleHues[(alias.hashCode() and 0x7fffffff) % bundleHues.size]

/** Bundle chip tinted with the bundle's own stable hue instead of flat grey. */
@Composable
private fun BundlePill(bundle: String, modifier: Modifier = Modifier) {
    val c = bundleHue(bundle)
    val shape = RoundedCornerShape(50)
    Row(
        modifier.background(c.copy(alpha = 0.12f), shape).border(1.dp, c.copy(alpha = 0.22f), shape).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(c, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(bundle, style = ZcType.caption.copy(color = c), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SkillRow(skill: AgentSkill, onClick: (() -> Unit)?) {
    ZcCard(Modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(skill.name, style = ZcType.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (skill.description.isNotEmpty()) {
                    Text(skill.description, style = ZcType.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (skill.editable) BundlePill(skill.bundle!!) else StatusPill(skill.origin, Tone.Attention)
            if (skill.editable) {
                Spacer(Modifier.width(6.dp))
                Icon(ZcIcons.Chevron, null, tint = ZcTokens.faint, modifier = Modifier.size(14.dp).align(Alignment.CenterVertically))
            }
        }
    }
}
