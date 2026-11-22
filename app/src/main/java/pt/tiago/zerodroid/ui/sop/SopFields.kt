package pt.tiago.zerodroid.ui.sop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import pt.tiago.zerodroid.data.sop.ToolSuggestions
import pt.tiago.zerodroid.data.sop.buildCondition
import pt.tiago.zerodroid.data.sop.parseCondition
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.clickableNoRipple
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = ZcType.caption, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A dropdown that shows [label] and lists [options] (value to label). */
@Composable
fun Picker(label: String, options: List<Pair<String, String>>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(ZcTokens.inputRadius))
                .background(ZcTokens.raised).border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(ZcTokens.inputRadius))
                .clickable { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = ZcType.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(ZcIcons.ChevronDown, null, tint = ZcTokens.muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = ZcTokens.raised) {
            options.forEach { (v, l) -> DropdownMenuItem({ Text(l, style = ZcType.body) }, onClick = { open = false; onPick(v) }) }
        }
    }
}

/**
 * Guided condition editor: JSON path, operator, value. Falls back to raw text when the stored
 * condition doesn't parse, or on request. [onChange] gets null when the condition is cleared.
 */
@Composable
fun ConditionField(value: String?, operators: List<String>, onChange: (String?) -> Unit, placeholderPath: String = "value") {
    val ops = operators.ifEmpty { listOf("==", "!=", ">", ">=", "<", "<=", "contains") }
    val parsed = parseCondition(value, ops)
    var raw by remember { mutableStateOf(parsed.op.isEmpty() && !value.isNullOrBlank()) }
    // Local text per part: rebuilding the parts from the trimmed condition on every keystroke would
    // eat spaces as they are typed. Resync only when the condition changes from outside (Undo).
    var path by remember { mutableStateOf(parsed.path.orEmpty()) }
    var op by remember { mutableStateOf(parsed.op) }
    var cmp by remember { mutableStateOf(parsed.value) }
    LaunchedEffect(value) {
        if (buildCondition(path, op, cmp) != value) {
            val p = parseCondition(value, ops)
            path = p.path.orEmpty(); op = p.op; cmp = p.value
        }
    }
    val emit = { onChange(buildCondition(path, op.ifEmpty { if (path.isNotBlank() || cmp.isNotBlank()) ops.first() else "" }, cmp)) }
    if (raw) {
        ZcTextField(value.orEmpty(), { onChange(it.ifBlank { null }) }, "$.$placeholderPath > 1", mono = true, modifier = Modifier.fillMaxWidth())
        Text("Use the builder", style = ZcType.caption.copy(color = ZcTokens.accentStart), modifier = Modifier.padding(top = 6.dp).clickableNoRipple { raw = false })
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ZcTextField(path, { path = it; emit() }, placeholderPath, mono = true, modifier = Modifier.weight(1.2f))
        Picker(op.ifEmpty { "any" }, listOf("" to "any (no condition)") + ops.map { it to it }, { op = it; emit() }, Modifier.width(86.dp))
        ZcTextField(cmp, { cmp = it; emit() }, "value", mono = true, modifier = Modifier.weight(1f))
    }
    Text("Edit as text", style = ZcType.caption.copy(color = ZcTokens.accentStart), modifier = Modifier.padding(top = 6.dp).clickableNoRipple { raw = true })
}

/** Tool chips with remove, plus a field that suggests known tools and accepts any typed name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolField(chosen: List<String>, suggestions: List<String>, onChange: (List<String>) -> Unit) {
    var query by remember { mutableStateOf("") }
    if (chosen.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chosen.forEach { t ->
                Row(
                    Modifier.clip(RoundedCornerShape(ZcTokens.chipRadius)).background(ZcTokens.accentStart.copy(alpha = 0.10f))
                        .border(1.dp, ZcTokens.accentStart.copy(alpha = 0.3f), RoundedCornerShape(ZcTokens.chipRadius))
                        .clickable { onChange(chosen - t) }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t, style = ZcType.monoSmall.copy(color = ZcTokens.accentStart))
                    Spacer(Modifier.width(6.dp))
                    Icon(ZcIcons.Close, "Remove $t", tint = ZcTokens.accentStart, modifier = Modifier.size(13.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    val add = { name: String ->
        val n = name.trim()
        if (n.isNotEmpty() && n !in chosen) onChange(chosen + n)
        query = ""
    }
    ZcTextField(
        query, { query = it }, "Add a tool (e.g. shell)", leadingIcon = ZcIcons.Plus, mono = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add(query) }),
    )
    if (query.isNotBlank()) {
        val matches = ToolSuggestions.filter(suggestions, query, chosen.toSet()).take(6)
        Column(Modifier.padding(top = 4.dp)) {
            matches.forEach { m -> SuggestionRow(m) { add(m) } }
            if (matches.none { it.equals(query.trim(), ignoreCase = true) }) SuggestionRow("Add \"${query.trim()}\"", accent = true) { add(query) }
        }
    } else if (chosen.isEmpty() && suggestions.isNotEmpty()) {
        Text("Known: ${suggestions.take(8).joinToString(", ")}", style = ZcType.caption, modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SuggestionRow(text: String, accent: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = (if (accent) ZcType.body.copy(color = ZcTokens.accentStart) else ZcType.mono),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

/**
 * Raw JSON editor for fields the app doesn't model in detail. Keeps the text locally and only
 * reports parsed JSON; blank reports null.
 */
@Composable
fun JsonField(initial: JsonElement?, placeholder: String, onValid: (JsonElement?) -> Unit) {
    var text by remember { mutableStateOf(initial?.let { ZdJson.encodeToString(JsonElement.serializer(), it) }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    // Resync when the value changes from outside (Undo, a list item shifting into this slot).
    LaunchedEffect(initial) {
        val mine = if (text.isBlank()) null else runCatching { ZdJson.parseToJsonElement(text) }.getOrNull()
        if (mine != initial && !(mine == null && error != null)) {
            text = initial?.let { ZdJson.encodeToString(JsonElement.serializer(), it) }.orEmpty(); error = null
        }
    }
    ZcTextField(
        text,
        {
            text = it
            if (it.isBlank()) { error = null; onValid(null) } else {
                runCatching { ZdJson.parseToJsonElement(it) }
                    .onSuccess { j -> error = null; onValid(j) }
                    .onFailure { error = "Not valid JSON yet" }
            }
        },
        placeholder, singleLine = false, maxLines = 8, mono = true, modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, style = ZcType.caption.copy(color = ZcTokens.danger), modifier = Modifier.padding(top = 4.dp)) }
}

/** A short list of choices in a dialog. */
@Composable
fun ChoiceDialog(title: String, choices: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZcTokens.raised,
        title = { Text(title, style = ZcType.heading) },
        text = {
            Column {
                choices.forEach { (label, action) ->
                    Text(label, style = ZcType.body, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onDismiss(); action() }.padding(vertical = 14.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
    )
}
