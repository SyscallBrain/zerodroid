package pt.tiago.zerodroid.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.ui.kit.clickableNoRipple
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pt.tiago.zerodroid.data.ApprovalStatus
import pt.tiago.zerodroid.data.ChatItem
import pt.tiago.zerodroid.data.NoticeKind
import pt.tiago.zerodroid.data.ToolStatus
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.ui.kit.Avatar
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.MarkdownText
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.kit.color
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onFirstSend: () -> Unit = {},
    onOpenChat: (String, String) -> Unit = { _, _ -> },
    onOpenAgent: (String) -> Unit = {},
    onOpenCanvas: (canvasId: String, frameId: String) -> Unit = { _, _ -> },
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    // Read so canvas chips get their Open button once the archive has written the frame.
    val canvases by pt.tiago.zerodroid.data.ZeroDroidConnection.canvas.canvases.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val conn by viewModel.connection.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val model by viewModel.model.collectAsStateWithLifecycle()
    pt.tiago.zerodroid.RecomposeProbe("ChatScreen")
    val ready = conn is ConnState.Ready
    val list = rememberLazyListState()
    val keepScreenOn by ZeroDroidConnection.screen.keepScreenOn.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Items render newest-first in a reversed list: the view stays pinned to the bottom while
    // text streams in, and does not move if the user has scrolled up to read.
    val reversed = remember(s.items) { s.items.asReversed() }
    val atTop by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == list.layoutInfo.totalItemsCount - 1 } }
    LaunchedEffect(atTop, s.hasOlder) { if (atTop && s.hasOlder) viewModel.loadOlder() }
    LaunchedEffect(Unit) {
        snapshotFlow { s.items.lastOrNull()?.key }.collect { if (list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0) }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(3_500); viewModel.consumeToast() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(ZcTokens.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        ZcTopBar(
            title = if (viewModel.isCode) folder?.let { pt.tiago.zerodroid.data.folderName(it) } ?: "Code · ${viewModel.agentAlias}" else title ?: viewModel.agentAlias,
            modifier = Modifier.clickableNoRipple { sheet = true },
            navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) },
            leading = { Avatar(viewModel.agentAlias, size = 34.dp) },
            subtitle = { LiveLine(s.turnRunning, s.items.lastOrNull(), conn) },
            actions = { ZcIconButton(ZcIcons.More, "Session") { sheet = true } },
        )
        HorizontalDivider(color = ZcTokens.hairline)
        ConnectionBanner(
            visible = pt.tiago.zerodroid.ui.home.chatConnectionBannerVisible(conn),
            text = when (conn) {
                is ConnState.Backoff -> "Reconnecting… messages will send once the daemon is back."
                is ConnState.AuthFailed -> pt.tiago.zerodroid.ui.home.authFailedBanner((conn as ConnState.AuthFailed).message)
                else -> "Connecting to the daemon…"
            },
            tone = if (conn is ConnState.Backoff || conn is ConnState.AuthFailed) Tone.Danger else Tone.Live,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && s.items.isEmpty() -> Column(Modifier.padding(ZcTokens.gutter)) {
                    SkeletonLines(2, Modifier.fillMaxWidth(0.6f).align(Alignment.End))
                    Spacer(Modifier.height(24.dp))
                    SkeletonLines(4)
                }
                s.items.isEmpty() -> EmptyState(
                    ZcIcons.Chats,
                    "Say hello to ${viewModel.agentAlias}",
                    "Messages go to the agent on the daemon. Tool calls and approvals show up here as they happen.",
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    state = list,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                ) {
                    if (s.turnRunning && s.items.lastOrNull().let { it !is ChatItem.Agent || !it.streaming }) {
                        item("typing") { TypingDots() }
                    }
                    items(reversed, key = { it.key }, contentType = { it::class }) { item ->
                        val canvasTarget = if (item is ChatItem.Tool && item.name == "canvas") {
                            canvases.size
                            pt.tiago.zerodroid.data.ZeroDroidConnection.canvas.locate(viewModel.sessionId, item.callId)
                        } else null
                        ChatRow(
                            item, viewModel, Modifier.animateItem(fadeInSpec = tween(180), placementSpec = null, fadeOutSpec = null),
                            onOpenCanvas = canvasTarget?.let { (c, f) -> { onOpenCanvas(c, f) } },
                        )
                    }
                    if (s.hasOlder) item("older") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            Text("Loading earlier messages…", style = ZcType.caption.copy(color = ZcTokens.faint))
                        }
                    }
                }
            }
            if (toast != null) {
                Text(
                    toast!!,
                    style = ZcType.caption.copy(color = ZcTokens.text),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .background(ZcTokens.raised, RoundedCornerShape(10.dp))
                        .border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        Composer(
            agentAlias = viewModel.agentAlias,
            running = s.turnRunning,
            enabled = ready,
            draftText = viewModel.session.draftText,
            draftImages = viewModel.session.draftImages,
            onSend = { text, images -> onFirstSend(); viewModel.send(text, images) },
            onStop = viewModel::stop,
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).imePadding(),
            model = model,
            contextFraction = contextFraction(s.contextUsed, s.contextMax),
            onModelClick = { sheet = true },
        )
    }

    if (sheet) {
        SessionSheet(
            vm = viewModel,
            onDismiss = { sheet = false },
            onOpenChat = onOpenChat,
            onOpenAgent = onOpenAgent,
            onRename = { renaming = true },
            onDelete = { confirmDelete = true },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = ZcTokens.raised,
            title = { Text(if (viewModel.isCode) "Delete this session?" else "Delete this chat?", style = ZcType.heading) },
            text = {
                Text(
                    if (viewModel.isCode) "This session and its transcript are removed from the daemon. Files in the folder are not touched."
                    else "This chat and its messages are removed from the daemon. This cannot be undone.",
                    style = ZcType.body.copy(color = ZcTokens.muted),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) {
                    Text("Delete", style = ZcType.button.copy(color = ZcTokens.danger))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
        )
    }

    if (renaming) {
        var name by remember { mutableStateOf(title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = ZcTokens.raised,
            title = { Text("Rename chat", style = ZcType.heading) },
            text = { ZcTextField(name, { name = it }, "Name", modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { viewModel.rename(name.trim()); renaming = false }) {
                    Text("Save", style = ZcType.button.copy(color = ZcTokens.accentStart))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
        )
    }
}


/** The header's status line: what the agent is doing, or idle/offline. Model, effort and context live by the composer. */
@Composable
private fun LiveLine(running: Boolean, last: ChatItem?, conn: ConnState) {
    val live: String? = when {
        conn !is ConnState.Ready -> null
        running && last is ChatItem.Tool && last.status == ToolStatus.Running -> "running ${last.name}"
        running && last is ChatItem.Approval && last.status == ApprovalStatus.Pending -> "waiting for you"
        running && last is ChatItem.Thought -> "thinking"
        running -> "working"
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            conn !is ConnState.Ready -> Text("offline", style = ZcType.caption.copy(color = ZcTokens.faint))
            live != null -> {
                pt.tiago.zerodroid.ui.kit.StatusDot(Tone.Live, pulse = true, size = 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(live, style = ZcType.caption.copy(color = ZcTokens.accentStart), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            else -> Text("idle", style = ZcType.caption.copy(color = ZcTokens.faint))
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem, vm: ChatViewModel, modifier: Modifier, onOpenCanvas: (() -> Unit)? = null) {
    pt.tiago.zerodroid.RecomposeProbe("ChatRow")
    when (item) {
        is ChatItem.User -> UserBubble(item, modifier)
        is ChatItem.Agent -> Column(modifier.fillMaxWidth()) {
            MarkdownText(item.text)
        }
        is ChatItem.Thought -> ThoughtRow(item, modifier)
        is ChatItem.Tool -> ToolChip(item, modifier, onOpenCanvas)
        is ChatItem.Approval -> ApprovalCard(
            toolName = item.toolName,
            summary = item.summary,
            deadlineAt = item.deadlineAt,
            status = item.status,
            answeredAt = item.answeredAt,
            onDecide = { vm.decide(item.requestId, it) },
            modifier = modifier,
        )
        is ChatItem.Notice -> NoticeRow(item, vm, modifier)
    }
}

@Composable
private fun UserBubble(item: ChatItem.User, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(ZcTokens.raised, shape)
                .border(1.dp, ZcTokens.accentEnd.copy(alpha = 0.22f), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (item.steer) Text("STEER", style = ZcType.label.copy(color = ZcTokens.accentStart))
            if (item.images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = if (item.text == "(image)") 0.dp else 6.dp)) {
                    item.images.forEach { sha -> SentImage(sha) }
                }
            }
            if (item.text != "(image)" || item.images.isEmpty()) SelectionContainer { Text(item.text, style = ZcType.body) }
        }
    }
}

@Composable
private fun NoticeRow(item: ChatItem.Notice, vm: ChatViewModel, modifier: Modifier) {
    val tone = when (item.kind) {
        NoticeKind.TurnCancelled, NoticeKind.TurnFailed, NoticeKind.Error -> Tone.Danger
        NoticeKind.BudgetWarning -> Tone.Attention
        NoticeKind.HistoryTrimmed -> Tone.Neutral
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(tone.colorAlpha(0.07f), RoundedCornerShape(ZcTokens.chipRadius))
            .border(1.dp, tone.colorAlpha(0.18f), RoundedCornerShape(ZcTokens.chipRadius))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ZcIcons.Alert, null, tint = tone.colorAlpha(1f), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(item.text, style = ZcType.caption.copy(color = ZcTokens.text), modifier = Modifier.weight(1f))
        if (item.retry) {
            Spacer(Modifier.width(8.dp))
            ZcGhostButton("Retry", compact = true, icon = ZcIcons.Refresh, onClick = vm::retryLast)
        }
    }
}

private fun Tone.colorAlpha(a: Float) = color().copy(alpha = a)

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(7.dp).alpha(a).background(ZcTokens.accentStart, RoundedCornerShape(4.dp)))
        }
    }
}

/** A sent image from the phone's cache; an "Image" chip when it was not sent from this phone. */
@Composable
private fun SentImage(sha: String) {
    val bmp by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, sha) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ZeroDroidConnection.chats.imageCache?.file(sha) }
            ?.let { thumbnail(it, 320) }
    }
    val shape = RoundedCornerShape(12.dp)
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bmp!!.asImageBitmap(), "Sent image",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(132.dp).clip(shape).border(1.dp, ZcTokens.hairline, shape),
        )
    } else {
        Row(
            Modifier.background(ZcTokens.surface, RoundedCornerShape(ZcTokens.chipRadius)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ZcIcons.Image, null, tint = ZcTokens.muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Image", style = ZcType.caption)
        }
    }
}
