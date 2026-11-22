package pt.tiago.zerodroid.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.canvas.StoredFrame
import pt.tiago.zerodroid.ui.chats.relativeTime
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.MarkdownText
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

private const val BACKGROUND_CSS = "#07080B"

@Composable
fun CanvasViewScreen(
    canvasId: String,
    frameId: String?,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onOpenChat: (sessionId: String, agentAlias: String) -> Unit,
) {
    val archive = ZeroDroidConnection.canvas
    val canvases by archive.canvases.collectAsStateWithLifecycle()
    val summary = canvases.firstOrNull { it.canvasId == canvasId }
    val scope = rememberCoroutineScope()
    var frames by remember { mutableStateOf<List<StoredFrame>?>(null) }
    var selected by rememberSaveable(canvasId) { mutableStateOf(frameId) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Reload when a new frame lands in this canvas while it is open.
    LaunchedEffect(canvasId, summary?.frameCount, summary?.updatedAt) { frames = archive.frames(canvasId) }
    val shown = frames?.let { list -> list.firstOrNull { it.id == selected } ?: list.lastOrNull() }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ZcTopBar(
            summary?.name ?: canvasId,
            navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) },
            actions = {
                shown?.let { f -> ZcIconButton(ZcIcons.Chats, "Open chat", onClick = { onOpenChat(f.sessionId, f.agentAlias) }) }
                if (summary != null) {
                    ZcIconButton(ZcIcons.Wrench, "Rename", onClick = { renaming = true })
                    ZcIconButton(ZcIcons.Trash, "Delete", tint = ZcTokens.danger, onClick = { deleting = true })
                }
            },
        )
        val list = frames
        if (list != null && list.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list.asReversed(), key = { it.id }) { f ->
                    val on = f.id == shown?.id
                    val shape = RoundedCornerShape(ZcTokens.chipRadius)
                    Text(
                        "${relativeTime(isoOf(f.at))} · ${f.contentType}",
                        style = ZcType.monoSmall.copy(color = if (on) ZcTokens.text else ZcTokens.muted),
                        modifier = Modifier.clip(shape)
                            .background(if (on) ZcTokens.raised else ZcTokens.surface)
                            .border(1.dp, if (on) ZcTokens.accentStart else ZcTokens.hairline, shape)
                            .clickable { selected = f.id }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            when {
                list == null -> Unit
                shown == null -> EmptyState(ZcIcons.Image, "This canvas is gone", "It was deleted from this phone.")
                else -> FrameContent(shown)
            }
        }
    }

    if (renaming && summary != null) {
        RenameCanvasDialog(summary.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch { archive.rename(canvasId, name) }
        }
    }
    if (deleting && summary != null) {
        DeleteCanvasDialog(summary.name, onDismiss = { deleting = false }) {
            deleting = false
            scope.launch {
                archive.delete(canvasId)
                onDeleted()
            }
        }
    }
}

@Composable
private fun FrameContent(f: StoredFrame) {
    val doc = frameDocument(f.contentType, f.content, BACKGROUND_CSS)
    when {
        doc != null -> CanvasWebView(doc, Modifier.fillMaxSize(), light = f.contentType == "html" && isFullDocument(f.content))
        f.contentType == "markdown" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            MarkdownText(f.content)
        }
        else -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
            Text(f.content, style = ZcType.monoSmall.copy(color = ZcTokens.text), overflow = TextOverflow.Clip)
        }
    }
}
