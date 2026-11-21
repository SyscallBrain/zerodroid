package pt.tiago.zerodroid.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.filterAgents
import pt.tiago.zerodroid.ui.kit.Avatar
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcSwitch
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

private enum class SheetStep { Main, Provider, Model, Agent }

/** Everything about this chat: the model answering, its context, and session actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onOpenChat: (String, String) -> Unit,
    onOpenAgent: (String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val model by vm.model.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val pins by ZeroDroidConnection.pins.pins.collectAsStateWithLifecycle()
    val keepScreenOn by ZeroDroidConnection.screen.keepScreenOn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(SheetStep.Main) }
    var provider by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<List<String>?>(null) }
    var custom by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(step, provider) {
        options = null
        options = when (step) {
            SheetStep.Provider -> vm.providers()
            SheetStep.Model -> provider?.let { vm.models(it) }.orEmpty()
            SheetStep.Agent -> runCatching { ZeroDroidConnection.sessions.agents().agents.filter { it.enabled }.map { it.alias } }.getOrDefault(emptyList())
            SheetStep.Main -> emptyList()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZcTokens.surface,
        scrimColor = ZcTokens.scrim,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).background(ZcTokens.hairlineStrong, RoundedCornerShape(2.dp))) },
    ) {
        // The main step is taller than short screens (more so with a Code session's folder card): it
        // scrolls inside the sheet. The pickers keep their own bounded lists instead.
        val mainScroll = androidx.compose.foundation.rememberScrollState()
        Column(
            Modifier
                .then(if (step == SheetStep.Main) Modifier.verticalScroll(mainScroll) else Modifier)
                .navigationBarsPadding()
                .padding(horizontal = ZcTokens.gutter)
                .padding(bottom = 16.dp),
        ) {
            when (step) {
                SheetStep.Main -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Avatar(vm.agentAlias, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(vm.agentAlias, style = ZcType.title)
                            Text("${if (vm.isCode) "Code session" else "Session"} ${vm.sessionId.take(8)}", style = ZcType.monoSmall.copy(color = ZcTokens.faint))
                        }
                    }
                    if (vm.isCode) {
                        val folder by vm.folder.collectAsStateWithLifecycle()
                        SectionHeader("Folder")
                        ZcCard(Modifier.fillMaxWidth()) {
                            Text(folder ?: "the agent's workspace", style = ZcType.mono.copy(color = ZcTokens.text))
                            Text(
                                "Works in this folder on the daemon's machine. No memory is shared with chats.",
                                style = ZcType.caption.copy(color = ZcTokens.muted), modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    SectionHeader("Model")
                    ZcCard(Modifier.fillMaxWidth()) {
                        Text(model?.model ?: "…", style = ZcType.mono.copy(color = ZcTokens.text))
                        Text(model?.provider ?: "agent default provider", style = ZcType.monoSmall.copy(color = ZcTokens.muted))
                        if (model?.overridden == true) {
                            Text(
                                "This chat uses its own model. Fresh start returns to the agent's defaults.",
                                style = ZcType.caption.copy(color = ZcTokens.accentStart), modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        model?.reasoning?.let { r ->
                            Text(
                                "Reasoning: " + (r.label?.removePrefix("reasoning ") ?: "provider default"),
                                style = ZcType.monoSmall.copy(color = ZcTokens.muted), modifier = Modifier.padding(top = 6.dp),
                            )
                            Text(
                                when {
                                    r.label == null -> "Not set on the daemon, so the provider's own default applies. Set runtime.reasoning_effort in Config to choose it."
                                    r.effort == null ->
                                        "runtime.reasoning_enabled only switches thinking ${if (r.enabled == true) "on" else "off"} on Ollama. Other providers keep their own default. Set runtime.reasoning_effort in Config to choose a level."
                                    r.effort != null && r.enabled != false && !r.passthrough ->
                                        "Global daemon setting. OpenAI-compatible providers only send it to OpenAI reasoning models unless the provider has reasoning_effort_passthrough on."
                                    else -> "Global daemon setting (runtime), the same for every agent."
                                },
                                style = ZcType.caption, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        ZcGhostButton("Change model", compact = true, enabled = !state.turnRunning) { step = SheetStep.Provider }
                        if (state.turnRunning) Text("Wait for the current turn to finish", style = ZcType.caption, modifier = Modifier.padding(top = 4.dp))
                    }
                    val used = state.contextUsed
                    val max = state.contextMax
                    if (used != null) {
                        SectionHeader("Context", Modifier.padding(top = 8.dp))
                        ZcCard(Modifier.fillMaxWidth()) {
                            val fraction = if (max != null && max > 0) (used.toFloat() / max).coerceIn(0f, 1f) else null
                            Text(
                                if (fraction != null) "${(fraction * 100).toInt()}% of ${max!! / 1000}k tokens" else "${used / 1000}k tokens",
                                style = ZcType.body,
                            )
                            if (fraction != null) {
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ZcTokens.raised)) {
                                    Box(
                                        Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp))
                                            .background(if (fraction > 0.85f) androidx.compose.ui.graphics.SolidColor(ZcTokens.attention) else ZcTokens.accentHorizontal),
                                    )
                                }
                            }
                        }
                    }
                    SectionHeader("Display", Modifier.padding(top = 8.dp))
                    ZcCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                        val toggle = { on: Boolean -> scope.launch { ZeroDroidConnection.screen.setKeepScreenOn(on) }; Unit }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { toggle(!keepScreenOn) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(ZcIcons.Sun, null, tint = ZcTokens.accentStart, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Keep screen on", style = ZcType.body)
                                Text("While a chat is open, for every chat", style = ZcType.caption)
                            }
                            ZcSwitch(keepScreenOn, toggle)
                        }
                    }
                    SectionHeader("Actions", Modifier.padding(top = 8.dp))
                    ZcCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                        ActionRow(
                            ZcIcons.Refresh, "Fresh start",
                            if (vm.isCode) "New session with ${vm.agentAlias} in this folder" else "New chat with ${vm.agentAlias}, clean context",
                        ) {
                            onDismiss(); vm.freshStart(onOpenChat)
                        }
                        HorizontalDivider(color = ZcTokens.hairline)
                        // The daemon cannot rename Code sessions; new ones start from the Code tab.
                        if (!vm.isCode) {
                            ActionRow(ZcIcons.Plus, "New chat…", "Pick an agent") { query = ""; step = SheetStep.Agent }
                            HorizontalDivider(color = ZcTokens.hairline)
                            ActionRow(ZcIcons.Link, "Rename", null) { onDismiss(); onRename() }
                            HorizontalDivider(color = ZcTokens.hairline)
                        }
                        ActionRow(ZcIcons.Sparkle, "Agent info", null) { onDismiss(); onOpenAgent(vm.agentAlias) }
                        HorizontalDivider(color = ZcTokens.hairline)
                        ActionRow(ZcIcons.Copy, "Copy session id", null) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("session id", vm.sessionId))
                            vm.showToast("Session id copied")
                            onDismiss()
                        }
                        HorizontalDivider(color = ZcTokens.hairline)
                        ActionRow(ZcIcons.Trash, if (vm.isCode) "Delete session" else "Delete chat", "Remove it and its messages from the daemon", danger = true) {
                            onDismiss(); onDelete()
                        }
                    }
                }
                SheetStep.Provider, SheetStep.Model, SheetStep.Agent -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        ZcIconButton(ZcIcons.Back, "Back") {
                            step = if (step == SheetStep.Model) SheetStep.Provider else SheetStep.Main
                        }
                        Text(
                            when (step) {
                                SheetStep.Provider -> "Choose a provider"
                                SheetStep.Model -> provider.orEmpty()
                                else -> "New chat"
                            },
                            style = ZcType.title,
                        )
                    }
                    if (step == SheetStep.Agent) {
                        ZcTextField(query, { query = it }, "Search agents", leadingIcon = ZcIcons.Search, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }
                    if (step == SheetStep.Model) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ZcTextField(custom, { custom = it }, "Model id", mono = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            ZcPrimaryButton("Use", compact = true, enabled = custom.isNotBlank()) {
                                vm.changeModel(provider!!, custom.trim()); onDismiss()
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        ZcGhostButton("Provider's default model", compact = true, modifier = Modifier.fillMaxWidth()) {
                            vm.changeModel(provider!!, null); onDismiss()
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    val list = options
                    if (list == null) SkeletonLines(4)
                    else {
                        val shown = if (step == SheetStep.Agent) {
                            filterAgents(list.map { pt.tiago.zerodroid.rpc.proto.AgentEntry(it, true, emptyList()) }, query, pins).map { it.alias }
                        } else list
                        if (shown.isEmpty()) Text(if (step == SheetStep.Model) "The daemon lists no models for this provider. Type one above." else "Nothing to show.", style = ZcType.small)
                        LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(shown, key = { it }) { item ->
                                val current = when (step) {
                                    SheetStep.Provider -> item == model?.provider
                                    SheetStep.Model -> item == model?.model
                                    else -> false
                                }
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        when (step) {
                                            SheetStep.Provider -> { provider = item; custom = ""; step = SheetStep.Model }
                                            SheetStep.Model -> { vm.changeModel(provider!!, item); onDismiss() }
                                            else -> { onDismiss(); vm.newChatWith(item, onOpenChat) }
                                        }
                                    }.padding(vertical = 11.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (step == SheetStep.Agent) {
                                        Avatar(item, size = 32.dp)
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Text(
                                        item,
                                        style = (if (step == SheetStep.Agent) ZcType.body else ZcType.mono).copy(color = if (current) ZcTokens.accentStart else ZcTokens.text),
                                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    if (step == SheetStep.Agent && item in pins) Icon(ZcIcons.StarFilled, null, tint = ZcTokens.attention, modifier = Modifier.size(14.dp))
                                    if (current) Icon(ZcIcons.Check, null, tint = ZcTokens.accentStart, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, hint: String?, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = if (danger) ZcTokens.danger else ZcTokens.accentStart, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = if (danger) ZcType.body.copy(color = ZcTokens.danger) else ZcType.body)
            if (hint != null) Text(hint, style = ZcType.caption)
        }
        Icon(ZcIcons.Chevron, null, tint = ZcTokens.faint, modifier = Modifier.size(14.dp))
    }
}
