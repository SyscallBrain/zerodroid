package pt.tiago.zerodroid.ui.chats

import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tiago.zerodroid.ui.kit.Avatar
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.NoSheetInsets
import pt.tiago.zerodroid.ui.kit.rememberFullSheetState
import pt.tiago.zerodroid.ui.kit.SheetBody
import pt.tiago.zerodroid.ui.kit.Skeleton
import pt.tiago.zerodroid.ui.kit.StatusDot
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(viewModel: ChatsViewModel, active: Boolean = true, onOpen: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val m by viewModel.model.collectAsStateWithLifecycle()
    pt.tiago.zerodroid.RecomposeProbe("ChatsScreen")
    var picker by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel, active) {
        if (active) viewModel.refresh()
        onPauseOrDispose { }
    }

    Column(modifier.fillMaxSize()) {
        ZcTopBar(
            "Chats",
            actions = {
                ZcPrimaryButton("New", icon = ZcIcons.Plus, compact = true, modifier = Modifier.padding(end = 12.dp)) {
                    viewModel.loadAgents()
                    picker = true
                }
            },
        )
        ZcTextField(
            value = m.query,
            onValueChange = viewModel::onQuery,
            placeholder = "Search messages",
            leadingIcon = ZcIcons.Search,
            modifier = Modifier.fillMaxWidth().padding(horizontal = ZcTokens.gutter),
        )
        ConnectionBanner(m.error != null, m.error.orEmpty(), Tone.Danger, action = "Dismiss", onAction = viewModel::dismissError)
        PullToRefreshBox(isRefreshing = false, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                when {
                    m.loading && m.sessions.isEmpty() -> items(5, key = { "sk$it" }) { SkeletonRow() }
                    m.sessions.isEmpty() -> item("empty") {
                        EmptyState(
                            ZcIcons.Chats,
                            if (m.query.isBlank()) "No chats yet" else "Nothing matches",
                            if (m.query.isBlank()) {
                                "Chats you start on this phone live on the daemon and show up here. Desktop and Telegram chats stay in those clients."
                            } else "No chat on this phone mentions \"${m.query}\".",
                            action = if (m.query.isBlank()) {
                                { ZcPrimaryButton("Start a chat", icon = ZcIcons.Plus) { viewModel.loadAgents(); picker = true } }
                            } else null,
                        )
                    }
                    else -> items(m.sessions, key = { it.id }) { row ->
                        SessionRowView(
                            row,
                            removing = row.id in m.removing,
                            modifier = Modifier.animateItem(),
                            onLongClick = { viewModel.askDelete(row) },
                        ) { viewModel.open(row, onOpen) }
                    }
                }
            }
        }
    }

    if (picker) {
        val sheet = rememberFullSheetState()
        ModalBottomSheet(
            onDismissRequest = { picker = false },
            sheetState = sheet,
            containerColor = ZcTokens.surface,
            scrimColor = ZcTokens.scrim,
            dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).background(ZcTokens.hairlineStrong, RoundedCornerShape(2.dp))) },
            contentWindowInsets = NoSheetInsets,
        ) {
            SheetBody {
                Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                    Text("New chat", style = ZcType.title, modifier = Modifier.padding(horizontal = ZcTokens.gutter, vertical = 12.dp))
                    var agentQuery by remember { mutableStateOf("") }
                    val pins by pt.tiago.zerodroid.data.ZeroDroidConnection.pins.pins.collectAsStateWithLifecycle()
                    ZcTextField(
                        agentQuery, { agentQuery = it }, "Search agents", leadingIcon = ZcIcons.Search,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = ZcTokens.gutter),
                    )
                    Spacer(Modifier.height(8.dp))
                    val shownAgents = pt.tiago.zerodroid.data.filterAgents(m.agents, agentQuery, pins)
                    if (m.agentsLoading) {
                        repeat(4) { SkeletonRow() }
                    } else if (shownAgents.isEmpty()) {
                        Text(if (m.agents.isEmpty()) "No enabled agents on this daemon." else "No agent matches \"$agentQuery\".", style = ZcType.small, modifier = Modifier.padding(ZcTokens.gutter))
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                            items(shownAgents, key = { it.alias }) { a ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = m.creating == null) {
                                            viewModel.newSession(a.alias) { id, alias ->
                                                picker = false
                                                onOpen(id, alias)
                                            }
                                        }
                                        .padding(horizontal = ZcTokens.gutter, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Avatar(a.alias, size = 36.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(a.alias, style = ZcType.bodyStrong)
                                        if (a.channels.isNotEmpty()) Text(a.channels.joinToString(", "), style = ZcType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (a.alias in pins) Icon(ZcIcons.StarFilled, null, tint = ZcTokens.attention, modifier = Modifier.padding(end = 8.dp).size(14.dp))
                                    if (m.creating == a.alias) Skeleton(Modifier.size(56.dp, 12.dp))
                                    else Icon(ZcIcons.Chevron, null, tint = ZcTokens.faint, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    m.deleting?.let { row ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            containerColor = ZcTokens.raised,
            title = { Text("Delete this chat?", style = ZcType.heading) },
            text = {
                Text(
                    "\"${row.title}\" and its messages are removed from the daemon. This cannot be undone.",
                    style = ZcType.body.copy(color = ZcTokens.muted),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text("Delete", style = ZcType.button.copy(color = ZcTokens.danger)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
        )
    }

    m.takeover?.let { row ->
        AlertDialog(
            onDismissRequest = viewModel::dismissTakeover,
            containerColor = ZcTokens.raised,
            title = { Text("Take over this chat?", style = ZcType.heading) },
            text = { Text("This session is open in another client. Taking it over will disconnect it there.", style = ZcType.body.copy(color = ZcTokens.muted)) },
            confirmButton = { TextButton(onClick = { viewModel.takeOver(onOpen) }) { Text("Take over", style = ZcType.button.copy(color = ZcTokens.accentStart)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissTakeover) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
        )
    }
}

@Composable
private fun SessionRowView(
    row: SessionRow,
    removing: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    // Long press offers deletion; a row being deleted fades and ignores taps.
    Column(
        modifier
            .then(if (removing) Modifier.alpha(0.4f) else Modifier)
            .combinedClickable(enabled = !removing, onLongClickLabel = "Delete chat", onLongClick = onLongClick, onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ZcTokens.gutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Avatar(row.alias, size = 42.dp)
                if (row.running) {
                    Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(RoundedCornerShape(6.dp)).background(ZcTokens.background), contentAlignment = Alignment.Center) {
                        StatusDot(Tone.Live, pulse = true, size = 8.dp)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, style = ZcType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (row.running) "${row.alias} is working…" else row.meta,
                    style = ZcType.caption.copy(color = if (row.running) ZcTokens.accentStart else ZcTokens.muted),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(row.time, style = ZcType.caption.copy(color = ZcTokens.faint))
        }
        HorizontalDivider(Modifier.padding(start = ZcTokens.gutter + 54.dp), color = ZcTokens.hairline)
    }
}

@Composable
private fun SkeletonRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = ZcTokens.gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Skeleton(Modifier.size(42.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Skeleton(Modifier.fillMaxWidth(0.55f).height(13.dp))
            Skeleton(Modifier.fillMaxWidth(0.35f).height(11.dp))
        }
    }
}
