package pt.tiago.zerodroid.ui.canvas

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.canvas.CanvasSummary
import pt.tiago.zerodroid.ui.chats.relativeTime
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

@Composable
fun CanvasListScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onlySession: String? = null) {
    val archive = ZeroDroidConnection.canvas
    val all by archive.canvases.collectAsStateWithLifecycle()
    val canvases = remember(all, onlySession) {
        if (onlySession == null) all else archive.canvasesFor(onlySession).toSet().let { ids -> all.filter { it.canvasId in ids } }
    }
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<CanvasSummary?>(null) }
    var renaming by remember { mutableStateOf<CanvasSummary?>(null) }
    var deleting by remember { mutableStateOf<CanvasSummary?>(null) }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Column(Modifier.fillMaxSize()) {
            ZcTopBar(if (onlySession == null) "Canvas" else "Canvas from this chat", navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) })
            if (canvases.isEmpty()) {
                EmptyState(
                    ZcIcons.Image,
                    "Nothing saved yet",
                    "Ask an agent to draw something on the canvas, for example a chart, and it shows up here.",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    items(canvases, key = { it.canvasId }) { c ->
                        CanvasRow(c, onClick = { onOpen(c.canvasId) }, onLongClick = { menuFor = c })
                    }
                }
            }
        }
    }

    menuFor?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = ZcTokens.raised,
            title = { Text(c.name, style = ZcType.heading, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {},
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { menuFor = null; renaming = c }) {
                    Text("Rename", style = ZcType.button.copy(color = ZcTokens.accentStart))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { menuFor = null; deleting = c }) {
                    Text("Delete", style = ZcType.button.copy(color = ZcTokens.danger))
                }
            },
        )
    }
    renaming?.let { c ->
        RenameCanvasDialog(c.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            scope.launch { archive.rename(c.canvasId, name) }
        }
    }
    deleting?.let { c ->
        DeleteCanvasDialog(c.name, onDismiss = { deleting = null }) {
            deleting = null
            scope.launch { archive.delete(c.canvasId) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CanvasRow(c: CanvasSummary, onClick: () -> Unit, onLongClick: () -> Unit) {
    ZcCard(
        Modifier.fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(ZcTokens.cardRadius))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, style = ZcType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${c.latestType} · ${framesLabel(c.frameCount)}", style = ZcType.monoSmall.copy(color = ZcTokens.muted))
        }
        Spacer(Modifier.height(4.dp))
        Text("${c.agentAlias} · ${relativeTime(isoOf(c.updatedAt))}", style = ZcType.caption)
    }
}
