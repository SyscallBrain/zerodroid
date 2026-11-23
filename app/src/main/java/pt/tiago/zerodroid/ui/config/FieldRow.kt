package pt.tiago.zerodroid.ui.config

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.config.Control
import pt.tiago.zerodroid.data.config.DraftValue
import pt.tiago.zerodroid.data.config.checkValue
import pt.tiago.zerodroid.data.config.controlFor
import pt.tiago.zerodroid.data.config.arrayDraft
import pt.tiago.zerodroid.data.config.arrayItems
import pt.tiago.zerodroid.data.config.displayValue
import pt.tiago.zerodroid.data.config.editText
import pt.tiago.zerodroid.data.config.secretMapKeys
import pt.tiago.zerodroid.data.config.humanLabel
import pt.tiago.zerodroid.data.config.toDraftValue
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.PropKind
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcSegmented
import pt.tiago.zerodroid.ui.kit.ZcSwitch
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

private fun plainText(v: DraftValue?, f: ConfigField): String = when (v) {
    is DraftValue.Plain -> (v.json as? JsonPrimitive)?.content ?: v.json.toString()
    else -> editText(f)
}

private fun currentItems(v: DraftValue?, f: ConfigField): List<String> = when (v) {
    is DraftValue.Plain -> (v.json as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: arrayItems(f)
    else -> arrayItems(f)
}

/** One setting: label, help, badges and the control that fits its kind. Edits stage into the draft. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldRow(f: ConfigField, sectionKey: String, prefix: String, rows: List<ConfigField>, highlight: Boolean = false) {
    val editor = ZeroDroidConnection.config
    val staged by editor.draft.entries.collectAsStateWithLifecycle()
    val readOnly by editor.readOnly.collectAsStateWithLifecycle()
    val entry = staged.firstOrNull { it.path == f.path }
    val control = controlFor(f)
    val enabled = !readOnly && control != Control.ReadOnly
    val current = plainText(entry?.value, f)
    fun stage(input: String) = editor.draft.stage(f, sectionKey, toDraftValue(f, input))

    var flash by remember { mutableStateOf(highlight) }
    LaunchedEffect(highlight) { if (highlight) { delay(1_600); flash = false } }
    val bg by animateColorAsState(if (flash) ZcTokens.accentStart.copy(alpha = 0.10f) else Color.Transparent, tween(600), label = "hl")

    Column(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(humanLabel(f.path, prefix), style = ZcType.bodyStrong)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                    if (entry != null) StatusPill("changed", Tone.Live, dot = false)
                    if (f.required) StatusPill("required", Tone.Attention, dot = false)
                    if (f.isEnvOverridden) StatusPill("env", Tone.Neutral, dot = false)
                }
            }
            if (control == Control.Switch) {
                ZcSwitch(current.equals("true", ignoreCase = true), { stage(it.toString()) }, enabled = enabled)
            }
        }
        if (f.description.isNotBlank()) {
            Text(
                pt.tiago.zerodroid.ui.kit.inlineMarkdown(pt.tiago.zerodroid.data.cleanDescription(f.description, f.path.substringAfterLast('.'))),
                style = ZcType.small, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        when (control) {
            Control.Switch -> Unit
            Control.Segmented -> ZcSegmented(f.enumVariants, f.enumVariants.indexOf(current).coerceAtLeast(-1), { if (enabled) stage(f.enumVariants[it]) })
            Control.Picker -> ChoiceRow(current, enabled, f.enumVariants, ::stage)
            Control.AliasPicker -> {
                var aliases by remember { mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(f.aliasSource) { f.aliasSource?.let { s -> aliases = runCatching { editor.aliasValues(s) }.getOrDefault(emptyList()) } }
                ChoiceRow(current, enabled, aliases, ::stage)
            }
            Control.Number, Control.Text, Control.MultilineText -> TextEditor(f, current, enabled, control) { stage(it) }
            Control.Chips -> ChipsEditor(currentItems(entry?.value, f), enabled) { editor.draft.stage(f, sectionKey, arrayDraft(it)) }
            Control.Secret -> SecretEditor(f, sectionKey, entry != null, enabled)
            Control.SecretMap -> SecretMapEditor(f, sectionKey, secretMapKeys(f, rows), enabled)
            Control.ReadOnly -> {
                Text(displayValue(f), style = ZcType.mono.copy(color = ZcTokens.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        f.isEnvOverridden -> "Set by an environment variable on the daemon."
                        else -> "Edit on desktop."
                    },
                    style = ZcType.caption.copy(color = ZcTokens.faint),
                )
            }
        }
    }
}

/** A value with a dropdown of choices. */
@Composable
private fun ChoiceRow(current: String, enabled: Boolean, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(ZcTokens.surface, RoundedCornerShape(ZcTokens.inputRadius))
                .border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(ZcTokens.inputRadius))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current.ifEmpty { "Choose…" }, style = ZcType.body.copy(color = if (current.isEmpty()) ZcTokens.faint else ZcTokens.text), modifier = Modifier.weight(1f))
            Icon(ZcIcons.ChevronDown, null, tint = ZcTokens.faint, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = ZcTokens.raised) {
            val list = options
            if (list.isEmpty()) DropdownMenuItem(text = { Text("No choices available", style = ZcType.small) }, onClick = { open = false }, enabled = false)
            list.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o, style = ZcType.body.copy(color = if (o == current) ZcTokens.accentStart else ZcTokens.text)) },
                    onClick = { open = false; onPick(o) },
                )
            }
        }
    }
}

@Composable
private fun TextEditor(f: ConfigField, current: String, enabled: Boolean, control: Control, onCommit: (String) -> Unit) {
    var text by remember(f.path, current) { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        if (!enabled || text == current) return
        error = checkValue(f, text)
        if (error == null) onCommit(text)
    }
    ZcTextField(
        value = text,
        onValueChange = { text = it; error = null },
        placeholder = if (f.required) "Required" else "Not set",
        singleLine = control != Control.MultilineText,
        mono = f.kind != PropKind.String || control == Control.Number,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (control == Control.Number) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = if (control == Control.MultilineText) ImeAction.Default else ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        onImeAction = if (control == Control.MultilineText) null else ({ commit() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (focused && !it.isFocused) commit()
            focused = it.isFocused
        },
        enabled = enabled,
    )
    if (!enabled) Text("Read-only", style = ZcType.caption.copy(color = ZcTokens.faint))
    error?.let { Text(it, style = ZcType.caption.copy(color = ZcTokens.danger), modifier = Modifier.padding(top = 4.dp)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsEditor(items: List<String>, enabled: Boolean, onCommit: (List<String>) -> Unit) {
    var adding by remember { mutableStateOf("") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(
                Modifier.background(ZcTokens.raised, RoundedCornerShape(ZcTokens.chipRadius)).border(1.dp, ZcTokens.hairline, RoundedCornerShape(ZcTokens.chipRadius))
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item, style = ZcType.monoSmall)
                Icon(
                    ZcIcons.Close, "Remove $item", tint = ZcTokens.faint,
                    modifier = Modifier.padding(start = 4.dp).size(16.dp).clickable(enabled = enabled) { onCommit(items - item) },
                )
            }
        }
    }
    if (enabled) {
        Spacer(Modifier.height(8.dp))
        ZcTextField(
            adding, { adding = it }, "Add…", mono = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            onImeAction = { if (adding.isNotBlank()) { onCommit(items + adding.trim()); adding = "" } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SecretEditor(f: ConfigField, sectionKey: String, staged: Boolean, enabled: Boolean) {
    val editor = ZeroDroidConnection.config
    var replacing by remember { mutableStateOf(false) }
    val value = rememberTextFieldState()
    var clearAsk by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(ZcIcons.Shield, null, tint = if (f.populated || staged) ZcTokens.success else ZcTokens.faint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                staged -> "New value staged"
                f.populated -> "Set"
                else -> "Not set"
            },
            style = ZcType.body, modifier = Modifier.weight(1f),
        )
        if (enabled && !replacing) {
            ZcGhostButton(if (f.populated) "Replace" else "Set", compact = true) { replacing = true }
            if (f.populated) {
                Spacer(Modifier.width(6.dp))
                ZcGhostButton("Clear", compact = true) { clearAsk = true }
            }
        }
    }
    if (replacing) {
        Spacer(Modifier.height(8.dp))
        SecretInput(value, "New value")
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZcGhostButton("Cancel", compact = true) { replacing = false; value.clearText() }
            ZcPrimaryButton("Stage", compact = true, enabled = value.text.isNotEmpty()) {
                editor.draft.stage(f, sectionKey, DraftValue.Secret(value.text.toString()))
                value.clearText()
                replacing = false
            }
        }
    }
    if (clearAsk) {
        StructuralDialog(
            request = StructuralRequest.ClearSecret(f.path, humanLabel(f.path, f.path.substringBeforeLast('.'))),
            onDismiss = { clearAsk = false }, onCreated = { clearAsk = false }, onDeleted = { clearAsk = false },
        )
    }
}

@Composable
private fun SecretMapEditor(f: ConfigField, sectionKey: String, keys: List<String>, enabled: Boolean) {
    val editor = ZeroDroidConnection.config
    val staged by editor.draft.entries.collectAsStateWithLifecycle()
    val pendingKeys = staged.filter { it.path.startsWith("${f.path}.") }.map { it.path.removePrefix("${f.path}.") }
    var key by remember { mutableStateOf("") }
    val value = rememberTextFieldState()
    var remove by remember { mutableStateOf<String?>(null) }
    (keys + pendingKeys).distinct().forEach { k ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(k, style = ZcType.mono, modifier = Modifier.weight(1f))
            Text(if (k in pendingKeys) "staged" else "••••", style = ZcType.caption.copy(color = ZcTokens.faint))
            if (enabled && k in keys) {
                Spacer(Modifier.width(6.dp))
                Icon(ZcIcons.Close, "Remove $k", tint = ZcTokens.danger, modifier = Modifier.size(18.dp).clickable { remove = k })
            }
        }
    }
    if (enabled) {
        Spacer(Modifier.height(8.dp))
        ZcTextField(key, { key = it }, "Key", mono = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        SecretInput(value, "Value")
        ZcGhostButton("Stage", compact = true, enabled = key.isNotBlank() && value.text.isNotEmpty() && '.' !in key, modifier = Modifier.padding(top = 8.dp)) {
            val synthetic = ConfigField(path = "${f.path}.${key.trim()}", kind = PropKind.String, isSecret = true)
            editor.draft.stage(synthetic, sectionKey, DraftValue.Secret(value.text.toString()))
            key = ""
            value.clearText()
        }
    }
    remove?.let { k ->
        StructuralDialog(
            request = StructuralRequest.ClearSecret("${f.path}.$k", k),
            onDismiss = { remove = null }, onCreated = { remove = null }, onDeleted = { remove = null },
        )
    }
}

@Composable
private fun SecretInput(state: TextFieldState, placeholder: String) {
    // Write-only: the field masks what is typed and is never prefilled.
    BasicSecureTextField(
        state = state,
        textStyle = ZcType.mono,
        cursorBrush = SolidColor(ZcTokens.accentStart),
        modifier = Modifier
            .fillMaxWidth()
            .background(ZcTokens.surface, RoundedCornerShape(ZcTokens.inputRadius))
            .border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(ZcTokens.inputRadius))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorator = { inner ->
            Box {
                if (state.text.isEmpty()) Text(placeholder, style = ZcType.mono.copy(color = ZcTokens.faint))
                inner()
            }
        },
    )
}
