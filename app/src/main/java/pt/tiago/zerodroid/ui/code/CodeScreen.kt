package pt.tiago.zerodroid.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.filterAgents
import pt.tiago.zerodroid.data.folderName
import pt.tiago.zerodroid.data.parentFolder
import pt.tiago.zerodroid.ui.kit.Avatar
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.NoSheetInsets
import pt.tiago.zerodroid.ui.kit.rememberFullSheetState
import pt.tiago.zerodroid.ui.kit.SheetBody
import pt.tiago.zerodroid.ui.kit.Skeleton
import pt.tiago.zerodroid.ui.kit.StatusDot
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/**
 * Code sessions: each is rooted at a folder on the daemon's machine and keeps no
 * persistent memory, like zerocode's Code pane. They are listed apart from chats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeScreen(viewModel: CodeViewModel, active: Boolean = true, onOpen: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val m by viewModel.model.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel, active) {
        if (active) viewModel.refresh()
        onPauseOrDispose { }
    }
    val startNew = viewModel::startNew

    Column(modifier.fillMaxSize()) {
        ZcTopBar(
            "Code",
            actions = { ZcPrimaryButton("New", icon = ZcIcons.Plus, compact = true, modifier = Modifier.padding(end = 12.dp), onClick = startNew) },
        )
        ConnectionBanner(m.error != null, m.error.orEmpty(), Tone.Danger, action = "Dismiss", onAction = viewModel::dismissError)
        PullToRefreshBox(isRefreshing = false, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                when {
                    m.loading && m.rows.isEmpty() -> items(4, key = { "sk$it" }) { SkeletonRow() }
                    m.rows.isEmpty() -> item("empty") {
                        EmptyState(
                            ZcIcons.Code,
                            "No code sessions yet",
                            "A code session works in one folder on the daemon's machine and starts with a clean context: no memory shared with your chats.",
                            action = { ZcPrimaryButton("Start a session", icon = ZcIcons.Plus, onClick = startNew) },
                        )
                    }
                    else -> items(m.rows, key = { it.id }) { row ->
                        CodeRowView(row, removing = row.id in m.removing, modifier = Modifier.animateItem(), onLongClick = { viewModel.askDelete(row) }) {
                            viewModel.open(row, onOpen)
                        }
                    }
                }
            }
        }
    }

    if (m.pickingAgent || m.browse != null) {
        // One sheet for both steps of New: a second sheet would add another window.
        ModalBottomSheet(
            onDismissRequest = viewModel::closeBrowse,
            sheetState = rememberFullSheetState(),
            containerColor = ZcTokens.surface,
            scrimColor = ZcTokens.scrim,
            dragHandle = { SheetHandle() },
            contentWindowInsets = NoSheetInsets,
        ) {
            SheetBody {
                val b = m.browse
                if (b != null) FolderBrowserContent(b, m.recent, creating = m.creating != null, viewModel = viewModel, onOpen = onOpen)
                else AgentPickerContent(m, viewModel)
            }
        }
    }

    m.deleting?.let { row ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            containerColor = ZcTokens.raised,
            title = { Text("Delete this session?", style = ZcType.heading) },
            text = {
                Text(
                    "\"${row.title}\" and its transcript are removed from the daemon. Files in the folder are not touched.",
                    style = ZcType.body.copy(color = ZcTokens.muted),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text("Delete", style = ZcType.button.copy(color = ZcTokens.danger)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
        )
    }
}

/** Step one of New: pick the agent. */
@Composable
private fun AgentPickerContent(m: CodeModel, viewModel: CodeViewModel) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
        Text("New code session", style = ZcType.title, modifier = Modifier.padding(horizontal = ZcTokens.gutter, vertical = 12.dp))
        var query by remember { mutableStateOf("") }
        val pins by ZeroDroidConnection.pins.pins.collectAsStateWithLifecycle()
        ZcTextField(query, { query = it }, "Search agents", leadingIcon = ZcIcons.Search, modifier = Modifier.fillMaxWidth().padding(horizontal = ZcTokens.gutter))
        Spacer(Modifier.height(8.dp))
        val shown = filterAgents(m.agents, query, pins)
        when {
            m.agentsLoading -> repeat(4) { SkeletonRow() }
            shown.isEmpty() -> Text(
                if (m.agents.isEmpty()) "No enabled agents on this daemon." else "No agent matches \"$query\".",
                style = ZcType.small, modifier = Modifier.padding(ZcTokens.gutter),
            )
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(shown, key = { it.alias }) { a ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { viewModel.pickFolderFor(a.alias) }
                            .padding(horizontal = ZcTokens.gutter, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(a.alias, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(a.alias, style = ZcType.bodyStrong, modifier = Modifier.weight(1f))
                        if (a.alias in pins) Icon(ZcIcons.StarFilled, null, tint = ZcTokens.attention, modifier = Modifier.padding(end = 8.dp).size(14.dp))
                        Icon(ZcIcons.Chevron, null, tint = ZcTokens.faint, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** Step two of New: browse the daemon's folders and pick the one the session works in. */
@Composable
private fun FolderBrowserContent(b: FolderBrowse, recent: List<String>, creating: Boolean, viewModel: CodeViewModel, onOpen: (String, String) -> Unit) {
    var typed by remember(b.path) { mutableStateOf(b.path) }
    // Scroll position per folder: entering one starts at the top, going back restores it.
    // A new list state per folder, created at its saved position, so the first frame is already right.
    val positions = remember { mutableMapOf<String, Pair<Int, Int>>() }
    val listState = remember(b.path) {
        val (index, offset) = positions[b.path] ?: (0 to 0)
        LazyListState(index, offset)
    }
    LaunchedEffect(listState) {
        val path = b.path
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { positions[path] = it }
    }
    Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding().imePadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ZcIconButton(ZcIcons.Back, "Parent folder", enabled = parentFolder(b.path) != null, onClick = viewModel::browseUp)
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text("Choose a folder", style = ZcType.title)
                Text("for ${b.alias}", style = ZcType.caption)
            }
            TextButton(onClick = viewModel::toggleHidden) {
                Text(if (b.showHidden) "Hide dotfiles" else "Dotfiles", style = ZcType.button.copy(color = ZcTokens.muted))
            }
        }
        ZcTextField(
            typed, { typed = it }, "/path/on/the/daemon", mono = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { viewModel.browseTo(typed) }),
            trailing = { if (typed != b.path) TextButton(onClick = { viewModel.browseTo(typed) }) { Text("Go", style = ZcType.button.copy(color = ZcTokens.accentStart)) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = ZcTokens.gutter),
        )
        val recentOthers = recent.filterNot { it == b.path }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
            // Shortcuts where browsing starts (the last folder, or the root) and when a listing is refused.
            val showRecent = recentOthers.isNotEmpty() && (b.error != null || b.path == "/" || b.path == recent.firstOrNull())
            if (showRecent) {
                item("recent-h") { Text("Recent", style = ZcType.caption, modifier = Modifier.padding(horizontal = ZcTokens.gutter, vertical = 6.dp)) }
                items(recentOthers, key = { "r:$it" }) { path -> FolderRow(folderName(path), path, ZcIcons.Clock) { viewModel.browseTo(path) } }
                item("recent-d") { HorizontalDivider(Modifier.padding(vertical = 6.dp), color = ZcTokens.hairline) }
            }
            when {
                b.showSkeleton -> items(6, key = { "sk$it" }) { SkeletonRow() }
                b.error != null -> item("err") {
                    Text(
                        "${b.error}\n\nThe daemon lists only folders that an agent this phone may use can read. Type another path, or use the agent's own workspace.",
                        style = ZcType.small.copy(color = ZcTokens.muted),
                        modifier = Modifier.padding(ZcTokens.gutter),
                    )
                }
                b.entries.isEmpty() && !b.loading -> item("none") {
                    Text("No folders inside.", style = ZcType.small.copy(color = ZcTokens.muted), modifier = Modifier.padding(ZcTokens.gutter))
                }
                else -> items(b.entries, key = { it.fullPath }) { e -> FolderRow(e.name, null, ZcIcons.Folder) { viewModel.browseTo(e.fullPath) } }
            }
        }
        HorizontalDivider(color = ZcTokens.hairline)
        Column(Modifier.padding(horizontal = ZcTokens.gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZcPrimaryButton(
                if (creating) "Starting…" else "Work in ${folderName(b.path)}",
                icon = ZcIcons.Code,
                enabled = !creating && b.startLooksEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { viewModel.start(b.path, onOpen) }
            ZcGhostButton("Use the agent's workspace", enabled = !creating, modifier = Modifier.fillMaxWidth()) { viewModel.start(null, onOpen) }
        }
    }
}

@Composable
private fun FolderRow(name: String, path: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = ZcTokens.gutter, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ZcTokens.accentStart, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = ZcType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (path != null) Text(path, style = ZcType.monoSmall.copy(color = ZcTokens.faint), maxLines = 1, overflow = TextOverflow.StartEllipsis)
        }
        Icon(ZcIcons.Chevron, null, tint = ZcTokens.faint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun CodeRowView(row: CodeRow, removing: Boolean, modifier: Modifier = Modifier, onLongClick: () -> Unit, onClick: () -> Unit) {
    Column(
        modifier.then(if (removing) Modifier.alpha(0.4f) else Modifier)
            .combinedClickable(enabled = !removing, onLongClickLabel = "Delete session", onLongClick = onLongClick, onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = ZcTokens.gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(ZcTokens.raised), contentAlignment = Alignment.Center) {
                Icon(ZcIcons.Folder, null, tint = ZcTokens.accentStart, modifier = Modifier.size(22.dp))
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
                    if (row.running) "${row.alias} is working…" else row.folder ?: "${row.messages} messages",
                    style = (if (row.running || row.folder == null) ZcType.caption else ZcType.monoSmall)
                        .copy(color = if (row.running) ZcTokens.accentStart else ZcTokens.muted),
                    maxLines = 1, overflow = TextOverflow.StartEllipsis,
                )
                if (!row.running) Text("${row.alias} · ${row.messages} message${if (row.messages == 1) "" else "s"}", style = ZcType.caption.copy(color = ZcTokens.faint), maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Text(row.time, style = ZcType.caption.copy(color = ZcTokens.faint))
        }
        HorizontalDivider(Modifier.padding(start = ZcTokens.gutter + 54.dp), color = ZcTokens.hairline)
    }
}

@Composable
private fun SheetHandle() {
    Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).background(ZcTokens.hairlineStrong, RoundedCornerShape(2.dp)))
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
