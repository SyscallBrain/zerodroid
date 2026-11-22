package pt.tiago.zerodroid.ui.config

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.config.ConfigEditor
import pt.tiago.zerodroid.data.config.SearchHit
import pt.tiago.zerodroid.data.config.displayValue
import pt.tiago.zerodroid.data.config.humanLabel
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.ConfigSection
import pt.tiago.zerodroid.rpc.proto.SectionShape
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.Skeleton
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** A pushed screen inside the Config tab. Serializable so the back stack survives recreation. */
@Serializable
sealed interface ConfigRoute {
    @Serializable data object Phone : ConfigRoute
    @Serializable data class Section(val key: String) : ConfigRoute
    @Serializable data class Family(val sectionKey: String, val typePath: String) : ConfigRoute
    @Serializable data class Entry(val sectionKey: String, val prefix: String, val title: String) : ConfigRoute
    @Serializable data class Field(val sectionKey: String, val prefix: String, val path: String) : ConfigRoute
}

private val stackSaver = Saver<List<ConfigRoute>, String>(
    save = { ZdJson.encodeToString(ListSerializer(ConfigRoute.serializer()), it) },
    restore = { runCatching { ZdJson.decodeFromString(ListSerializer(ConfigRoute.serializer()), it) }.getOrDefault(emptyList()) },
)

/** The Config tab: home plus a back stack of config screens, the review sheet and structural dialogs. */
@Composable
fun ConfigTab(debug: Boolean, active: Boolean = true, jump: ConfigRoute? = null, onJumpConsumed: () -> Unit = {}) {
    var stack by rememberSaveable(stateSaver = stackSaver) { mutableStateOf(emptyList<ConfigRoute>()) }
    LaunchedEffect(jump) {
        if (jump != null) {
            stack = listOf(ConfigRoute.Section(jump.let { (it as? ConfigRoute.Entry)?.sectionKey ?: "agents" }), jump)
            onJumpConsumed()
        }
    }
    var review by rememberSaveable { mutableStateOf(false) }
    var structural by remember { mutableStateOf<StructuralRequest?>(null) }
    val push: (ConfigRoute) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    // Only while Config is the visible tab: it stays composed under the others.
    BackHandler(enabled = active && stack.isNotEmpty()) { pop() }

    AnimatedContent(
        targetState = stack.lastOrNull(),
        transitionSpec = { (fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 6 }) togetherWith fadeOut(tween(120)) },
        label = "config-nav",
    ) { top ->
        when (top) {
            null -> ConfigHomeScreen(active = active, onNavigate = push, onReview = { review = true })
            ConfigRoute.Phone -> PhoneScreen(debug, onBack = pop)
            else -> ConfigRouteScreen(
                route = top,
                onNavigate = push,
                onBack = pop,
                onReview = { review = true },
                onStructural = { structural = it },
            )
        }
    }
    if (review) ReviewSheet(onDismiss = { review = false })
    structural?.let { req ->
        StructuralDialog(
            request = req,
            onDismiss = { structural = null },
            onCreated = { route -> structural = null; push(route) },
            onDeleted = { structural = null; if (stack.lastOrNull() is ConfigRoute.Entry) pop() },
        )
    }
}

private fun groupIcon(groupKey: String?): ImageVector = when (groupKey) {
    "foundation" -> ZcIcons.Sparkle
    "reach", "channels" -> ZcIcons.Chats
    "safety", "security" -> ZcIcons.Shield
    "runtime" -> ZcIcons.Bolt
    "automation" -> ZcIcons.Clock
    "tools" -> ZcIcons.Wrench
    else -> ZcIcons.Config
}

@Composable
fun ConfigHomeScreen(active: Boolean = true, onNavigate: (ConfigRoute) -> Unit, onReview: () -> Unit) {
    val editor = ZeroDroidConnection.config
    val sections by editor.sections.collectAsStateWithLifecycle()
    val staged by editor.draft.entries.collectAsStateWithLifecycle()
    val readOnly by editor.readOnly.collectAsStateWithLifecycle()
    val error by editor.error.collectAsStateWithLifecycle()
    val conn by ZeroDroidConnection.state.collectAsStateWithLifecycle()
    val restarting by ZeroDroidConnection.restarting.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var index by remember { mutableStateOf<List<ConfigField>?>(null) }
    var summaries by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val ready = conn is ConnState.Ready

    // Reloads each time Config is shown, as when the tab was recomposed on every visit.
    LaunchedEffect(ready, active) {
        if (!ready || !active) return@LaunchedEffect
        editor.loadSections()
        index = runCatching { editor.searchIndex() }.getOrNull()
        val templates = runCatching { editor.templates() }.getOrDefault(emptyList())
        // One config/map-keys per family type (over a hundred): in parallel, not one round trip after another.
        val gate = kotlinx.coroutines.sync.Semaphore(16) // keep the daemon's queue short
        summaries = kotlinx.coroutines.coroutineScope {
            editor.sections.value.map { s ->
                async {
                    s.key to when (s.shape) {
                        SectionShape.OneTierAliasMap -> runCatching { editor.mapKeys(s.key).size }.getOrNull()?.let { "$it" } ?: ""
                        SectionShape.TypedFamilyMap -> {
                            val types = pt.tiago.zerodroid.data.config.familyTypes(s.key, templates)
                            val configured = types.map { t -> async { gate.withPermit { runCatching { editor.mapKeys(t.path).isNotEmpty() }.getOrDefault(false) } } }
                                .awaitAll().count { it }
                            if (configured > 0) "$configured of ${types.size}" else ""
                        }
                        else -> ""
                    }
                }
            }.awaitAll().toMap()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ZcTopBar(
            "Config",
            actions = {
                if (staged.isNotEmpty()) {
                    ZcPrimaryButton("Review ${staged.size}", compact = true, modifier = Modifier.padding(end = 12.dp), onClick = onReview)
                }
            },
        )
        ZcTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = index?.let { "Search ${it.size} settings" } ?: "Search settings",
            leadingIcon = ZcIcons.Search,
            modifier = Modifier.fillMaxWidth().padding(horizontal = ZcTokens.gutter),
        )
        ConnectionBanner(readOnly, "This device can view settings but not change them. Grant config write to its token on the daemon.", Tone.Attention)
        ConnectionBanner(!ready && restarting, "ZeroClaw is restarting to apply your changes. Reconnecting…", Tone.Live)
        ConnectionBanner(!ready && !restarting, "Offline. Showing the last loaded settings; changes wait in the draft.", Tone.Danger)
        ConnectionBanner(ready && error != null && sections.isEmpty(), "Could not load settings: ${error.orEmpty()}", Tone.Danger)

        LazyColumn(
            Modifier.fillMaxSize().alpha(if (ready) 1f else 0.6f),
            contentPadding = PaddingValues(start = ZcTokens.gutter, end = ZcTokens.gutter, top = 8.dp, bottom = 24.dp),
        ) {
            if (query.isNotBlank()) {
                val hits = ConfigEditor.search(query, index.orEmpty(), sections)
                if (hits.isEmpty()) item("none") { EmptyState(ZcIcons.Search, "No setting matches", "Try a key name like model, port or token.") }
                items(hits, key = { "h-" + it.field.path }) { hit -> SearchRow(hit, staged.any { it.path == hit.field.path }) { openHit(hit, onNavigate) } }
                return@LazyColumn
            }
            if (sections.isEmpty()) {
                items(6, key = { "sk$it" }) { Skeleton(Modifier.fillMaxWidth().height(52.dp).padding(vertical = 4.dp)) }
            } else {
                sections.groupBy { it.group ?: "Other" }.forEach { (group, list) ->
                    item("g-$group") { SectionHeader(group, Modifier.padding(top = 12.dp)) }
                    items(list, key = { "s-" + it.key }) { s ->
                        SectionRow(
                            icon = groupIcon(s.groupKey),
                            title = s.label,
                            summary = summaries[s.key].orEmpty().ifEmpty { plainSummary(s.help, s.key) },
                            changed = staged.any { it.sectionKey == s.key },
                            onClick = { onNavigate(ConfigRoute.Section(s.key)) },
                        )
                    }
                }
            }
            item("phone-h") { SectionHeader("This phone", Modifier.padding(top = 12.dp)) }
            item("phone") { SectionRow(ZcIcons.Terminal, "ZeroDroid & pairing", "Daemon, Home, background, diagnostics, unpair", false) { onNavigate(ConfigRoute.Phone) } }
        }
    }
}

/** The form prefix is resolved by the route screen (it needs templates), so search passes "". */
private fun openHit(hit: SearchHit, onNavigate: (ConfigRoute) -> Unit) {
    onNavigate(ConfigRoute.Field(hit.sectionKey, "", hit.field.path))
}

@Composable
internal fun SectionRow(icon: ImageVector, title: String, summary: String, changed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(ZcTokens.surface, RoundedCornerShape(10.dp)).border(1.dp, ZcTokens.hairline, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = ZcTokens.accentStart, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ZcType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary.isNotBlank()) Text(summary, style = ZcType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (changed) {
            Box(Modifier.size(8.dp).background(ZcTokens.accentStart, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(8.dp))
        }
        Icon(ZcIcons.Chevron, null, tint = ZcTokens.faint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SearchRow(hit: SearchHit, changed: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(humanLabel(hit.field.path, hit.sectionKey), style = ZcType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${hit.sectionLabel} · ${hit.field.path}", style = ZcType.monoSmall.copy(color = ZcTokens.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(displayValue(hit.field), style = ZcType.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
        if (changed) Box(Modifier.padding(start = 6.dp).size(8.dp).background(ZcTokens.accentStart, RoundedCornerShape(4.dp)))
    }
}

/** Section lookup used by pushed screens. */
internal fun sectionOf(key: String): ConfigSection? = ZeroDroidConnection.config.sections.value.firstOrNull { it.key == key }

/** One-line summary: the cleaned description without markdown backticks. */
internal fun plainSummary(raw: String, key: String): String =
    pt.tiago.zerodroid.data.summaryOf(raw, key.substringAfterLast('.'))
