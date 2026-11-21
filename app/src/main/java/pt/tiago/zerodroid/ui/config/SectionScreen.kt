package pt.tiago.zerodroid.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.config.belongsToForm
import pt.tiago.zerodroid.data.config.familyTypes
import pt.tiago.zerodroid.data.config.formPrefixFor
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.ConfigTemplate
import pt.tiago.zerodroid.rpc.proto.PropKind
import pt.tiago.zerodroid.rpc.proto.SectionShape
import pt.tiago.zerodroid.rpc.userMessage
import pt.tiago.zerodroid.ui.kit.Avatar
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcSegmented
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** A confirmed structural edit the Config tab hosts as a dialog. */
sealed interface StructuralRequest {
    data class Add(val sectionKey: String, val mapPath: String, val existing: List<String>) : StructuralRequest
    data class Rename(val mapPath: String, val from: String, val existing: List<String>) : StructuralRequest
    data class Delete(val mapPath: String, val key: String, val isAgent: Boolean) : StructuralRequest
    data class ClearSecret(val path: String, val label: String) : StructuralRequest
}

@Composable
fun ConfigRouteScreen(
    route: ConfigRoute,
    onNavigate: (ConfigRoute) -> Unit,
    onBack: () -> Unit,
    onReview: () -> Unit,
    onStructural: (StructuralRequest) -> Unit,
) {
    val staged by ZeroDroidConnection.config.draft.entries.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        val title = when (route) {
            is ConfigRoute.Section -> sectionOf(route.key)?.label ?: route.key
            is ConfigRoute.Family -> route.typePath.substringAfterLast('.')
            is ConfigRoute.Entry -> route.title
            is ConfigRoute.Field -> sectionOf(route.sectionKey)?.label ?: route.sectionKey
            ConfigRoute.Phone -> ""
        }
        ZcTopBar(
            title,
            navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) },
            actions = {
                if (staged.isNotEmpty()) ZcPrimaryButton("Review ${staged.size}", compact = true, modifier = Modifier.padding(end = 12.dp), onClick = onReview)
            },
        )
        HorizontalDivider(color = ZcTokens.hairline)
        when (route) {
            is ConfigRoute.Section -> SectionBody(route.key, onNavigate, onStructural)
            is ConfigRoute.Family -> AliasList(route.sectionKey, route.typePath, onNavigate, onStructural)
            is ConfigRoute.Entry -> FormBody(route.sectionKey, route.prefix, null)
            is ConfigRoute.Field -> FieldRouteBody(route)
            ConfigRoute.Phone -> Unit
        }
    }
}

@Composable
private fun SectionBody(key: String, onNavigate: (ConfigRoute) -> Unit, onStructural: (StructuralRequest) -> Unit) {
    when (sectionOf(key)?.shape) {
        SectionShape.OneTierAliasMap -> AliasList(key, key, onNavigate, onStructural)
        SectionShape.TypedFamilyMap -> TypeList(key, onNavigate)
        else -> FormBody(key, key, null)
    }
}

@Composable
private fun FieldRouteBody(route: ConfigRoute.Field) {
    var prefix by remember(route) { mutableStateOf(route.prefix.ifEmpty { null }) }
    LaunchedEffect(route) {
        if (prefix == null) {
            val templates = runCatching { ZeroDroidConnection.config.templates() }.getOrDefault(emptyList())
            prefix = formPrefixFor(route.path, route.sectionKey, templates)
        }
    }
    prefix?.let { FormBody(route.sectionKey, it, route.path) } ?: Loading()
}

@Composable
private fun Loading() = Column(Modifier.padding(ZcTokens.gutter)) { SkeletonLines(4) }

@Composable
private fun TypeList(sectionKey: String, onNavigate: (ConfigRoute) -> Unit) {
    val editor = ZeroDroidConnection.config
    var types by remember { mutableStateOf<List<Pair<ConfigTemplate, Int>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sectionKey) {
        runCatching {
            familyTypes(sectionKey, editor.templates()).map { t -> t to runCatching { editor.mapKeys(t.path).size }.getOrDefault(0) }
        }.onSuccess { types = it }.onFailure { error = it.userMessage() }
    }
    ConnectionBanner(error != null, error.orEmpty(), Tone.Danger)
    val list = types ?: return Loading()
    LazyColumn(contentPadding = PaddingValues(horizontal = ZcTokens.gutter, vertical = 8.dp)) {
        if (sectionKey == "channels") {
            item("root") { SectionRow(ZcIcons.Config, "Channel settings", "Shared options for every channel", false) { onNavigate(ConfigRoute.Entry(sectionKey, sectionKey, "Channel settings")) } }
        }
        val (configured, rest) = list.partition { it.second > 0 }
        if (configured.isNotEmpty()) item("c-h") { pt.tiago.zerodroid.ui.kit.SectionHeader("Configured") }
        items(configured, key = { it.first.path }) { (t, n) -> TypeRow(t, n) { onNavigate(ConfigRoute.Family(sectionKey, t.path)) } }
        if (rest.isNotEmpty()) item("r-h") { pt.tiago.zerodroid.ui.kit.SectionHeader("Available", Modifier.padding(top = 8.dp)) }
        items(rest, key = { it.first.path }) { (t, n) -> TypeRow(t, n) { onNavigate(ConfigRoute.Family(sectionKey, t.path)) } }
    }
}

@Composable
private fun TypeRow(t: ConfigTemplate, count: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(t.path.substringAfterLast('.'), size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.path.substringAfterLast('.'), style = ZcType.bodyStrong)
            Text(pt.tiago.zerodroid.data.typeSummary(count, t.description, t.path), style = ZcType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(ZcIcons.Chevron, null, tint = ZcTokens.faint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun AliasList(sectionKey: String, mapPath: String, onNavigate: (ConfigRoute) -> Unit, onStructural: (StructuralRequest) -> Unit) {
    val editor = ZeroDroidConnection.config
    val readOnly by editor.readOnly.collectAsStateWithLifecycle()
    val staged by editor.draft.entries.collectAsStateWithLifecycle()
    var keys by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val version by editor.structureVersion.collectAsStateWithLifecycle()
    LaunchedEffect(mapPath, version) {
        runCatching { editor.mapKeys(mapPath) }.onSuccess { keys = it }.onFailure { error = it.userMessage() }
    }
    ConnectionBanner(error != null, error.orEmpty(), Tone.Danger)
    val list = keys ?: return Loading()
    val isAgents = mapPath == "agents"
    LazyColumn(contentPadding = PaddingValues(horizontal = ZcTokens.gutter, vertical = 8.dp)) {
        item("add") {
            ZcGhostButton(
                "Add ${mapPath.substringAfterLast('.').removeSuffix("s")}",
                icon = ZcIcons.Plus, enabled = !readOnly, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) { onStructural(StructuralRequest.Add(sectionKey, mapPath, list)) }
        }
        if (list.isEmpty()) item("empty") { EmptyState(ZcIcons.Plus, "Nothing here yet", "Add one to configure it.") }
        items(list, key = { it }) { alias ->
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable { onNavigate(ConfigRoute.Entry(sectionKey, "$mapPath.$alias", alias)) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(alias, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Text(alias, style = ZcType.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (staged.any { it.path.startsWith("$mapPath.$alias.") }) {
                    Box(Modifier.size(8.dp).background(ZcTokens.accentStart, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(6.dp))
                }
                Box {
                    ZcIconButton(ZcIcons.More, "More for $alias", enabled = !readOnly) { menu = true }
                    DropdownMenu(menu, { menu = false }, containerColor = ZcTokens.raised) {
                        DropdownMenuItem(text = { Text("Rename", style = ZcType.body) }, onClick = {
                            menu = false; onStructural(StructuralRequest.Rename(mapPath, alias, list))
                        })
                        DropdownMenuItem(text = { Text("Delete", style = ZcType.body.copy(color = ZcTokens.danger)) }, onClick = {
                            menu = false; onStructural(StructuralRequest.Delete(mapPath, alias, isAgents))
                        })
                    }
                }
            }
        }
    }
}

/** A form of every field under [prefix], split by tab, optionally scrolled to [focusPath]. */
@Composable
private fun FormBody(sectionKey: String, prefix: String, focusPath: String?) {
    val editor = ZeroDroidConnection.config
    var fields by remember { mutableStateOf<List<ConfigField>?>(null) }
    var rows by remember { mutableStateOf<List<ConfigField>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val version by editor.structureVersion.collectAsStateWithLifecycle()
    LaunchedEffect(prefix, version) {
        runCatching {
            val templates = editor.templates()
            val all = editor.fields(prefix, refresh = version > 0).also { rows = it }
                .filter { belongsToForm(it.path, prefix, templates) }
                .filterNot { pt.tiago.zerodroid.data.config.isSecretMapChild(it, rows) }
            // Backend pickers: the section's own choice comes first.
            if (sectionOf(sectionKey)?.shape == SectionShape.BackendPicker) {
                all.sortedBy { f -> if (f.kind == PropKind.Enum && f.path.removePrefix("$prefix.").count { it == '.' } == 0) 0 else 1 }
            } else all
        }.onSuccess { fields = it }.onFailure { error = it.userMessage() }
    }
    ConnectionBanner(error != null, error.orEmpty(), Tone.Danger)
    val list = fields ?: return Loading()
    val tabs = list.mapNotNull { it.tabOrNull }.distinct()
    var tab by rememberSaveable(prefix) { mutableIntStateOf(0) }
    LaunchedEffect(focusPath, tabs) {
        val t = list.firstOrNull { it.path == focusPath }?.tabOrNull
        if (t != null) tab = tabs.indexOf(t).coerceAtLeast(0)
    }
    val shown = if (tabs.size > 1) list.filter { (it.tabOrNull ?: tabs.first()) == tabs[tab.coerceIn(0, tabs.lastIndex)] } else list
    val state = rememberLazyListState()
    LaunchedEffect(focusPath, shown) {
        val i = shown.indexOfFirst { it.path == focusPath }
        if (i >= 0) state.animateScrollToItem(i + 1)
    }
    LazyColumn(state = state, contentPadding = PaddingValues(start = ZcTokens.gutter, end = ZcTokens.gutter, top = 8.dp, bottom = 32.dp)) {
        item("tabs") {
            if (tabs.size > 1) ZcSegmented(tabs, tab.coerceIn(0, tabs.lastIndex), { tab = it }, Modifier.padding(bottom = 8.dp))
            if (list.isEmpty()) EmptyState(ZcIcons.Config, "No settings here", "This entry has no editable fields.")
        }
        items(shown, key = { it.path }) { f ->
            FieldRow(f, sectionKey, prefix, rows, highlight = f.path == focusPath)
            HorizontalDivider(color = ZcTokens.hairline)
        }
        item("end") { Spacer(Modifier.height(24.dp)) }
    }
}
