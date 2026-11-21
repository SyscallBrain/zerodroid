package pt.tiago.zerodroid.data.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.PropKind

/** How a field is edited on the phone. */
enum class Control { Switch, Segmented, Picker, AliasPicker, Number, Text, MultilineText, Chips, Secret, SecretMap, ReadOnly }

fun controlFor(f: ConfigField): Control = when {
    f.isEnvOverridden -> Control.ReadOnly
    // The secret-map container is flagged secret too, but it is edited key by key.
    f.kind == PropKind.SecretMap -> Control.SecretMap
    f.isSecret -> Control.Secret
    else -> when (f.kind) {
        PropKind.Bool -> Control.Switch
        PropKind.Enum -> when {
            f.enumVariants.isEmpty() -> Control.Text
            f.enumVariants.size <= 3 -> Control.Segmented
            else -> Control.Picker
        }
        PropKind.AliasRef -> Control.AliasPicker
        PropKind.Integer, PropKind.Float -> Control.Number
        PropKind.String -> if (f.multiline) Control.MultilineText else Control.Text
        PropKind.StringArray -> Control.Chips
        PropKind.SecretMap -> Control.SecretMap
        PropKind.Object, PropKind.ObjectArray, PropKind.Unknown -> Control.ReadOnly
    }
}

private fun humanize(segment: String): String =
    segment.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }

/** "agents.coder.max_tool_iterations" under "agents.coder" → "Max tool iterations". */
fun humanLabel(path: String, prefix: String): String {
    val rest = if (path.startsWith("$prefix.")) path.removePrefix("$prefix.") else path.substringAfterLast('.')
    return rest.split('.').filter { it.isNotEmpty() }.joinToString(" · ") { humanize(it) }
}

/** Client-side check before staging; the daemon still has the final word on apply. */
fun checkValue(f: ConfigField, input: String, aliases: List<String> = emptyList()): String? {
    val v = input.trim()
    if (f.required && v.isEmpty()) return "Required"
    if (v.isEmpty()) return null
    return when (f.kind) {
        PropKind.Integer -> if (v.toLongOrNull() == null) "Must be a whole number" else null
        PropKind.Float -> if (v.toDoubleOrNull() == null) "Must be a number" else null
        PropKind.Enum -> if (f.enumVariants.isNotEmpty() && v !in f.enumVariants) "Must be one of ${f.enumVariants.joinToString(", ")}" else null
        PropKind.AliasRef -> if (aliases.isNotEmpty() && v !in aliases) "No such entry: $v" else null
        else -> null
    }
}

fun toDraftValue(f: ConfigField, input: String): DraftValue {
    if (f.isSecret) return DraftValue.Secret(input)
    val v = input.trim()
    return DraftValue.Plain(
        when (f.kind) {
            PropKind.Bool -> JsonPrimitive(v.equals("true", ignoreCase = true))
            PropKind.Integer -> v.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(v)
            PropKind.Float -> v.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(v)
            PropKind.StringArray -> JsonArray(v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) })
            else -> JsonPrimitive(input)
        },
    )
}

/** The daemon's display value for an unset field (`UNSET_DISPLAY` in zeroclaw-config). */
const val UNSET = "<unset>"

fun displayValue(f: ConfigField): String = when {
    f.isSecret -> if (f.populated) "Set" else "Not set"
    !f.populated || displayJson(f.value) == UNSET -> "—"
    f.kind == PropKind.StringArray -> arrayItems(f).joinToString(", ").ifEmpty { "—" }
    else -> displayJson(f.value)
}

/** Text to put in an edit box: empty for unset fields, never the `<unset>` marker. */
fun editText(f: ConfigField): String =
    if (f.isSecret || !f.populated || displayJson(f.value) == UNSET || displayJson(f.value) == "—") "" else displayJson(f.value)

/**
 * Items of a `string_array` field. The daemon sends them as a TOML array string
 * (`["a", "b"]`), which parses as JSON for string items, as zerocode does.
 */
fun arrayItems(f: ConfigField): List<String> {
    if (!f.populated) return emptyList()
    val v = f.value ?: return emptyList()
    if (v is JsonArray) return v.mapNotNull { (it as? JsonPrimitive)?.content }
    val raw = (v as? JsonPrimitive)?.content ?: return emptyList()
    if (raw == UNSET) return emptyList()
    return runCatching {
        (pt.tiago.zerodroid.rpc.ZdJson.parseToJsonElement(raw) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.content }
    }.getOrDefault(emptyList())
}

fun arrayDraft(items: List<String>): DraftValue = DraftValue.Plain(JsonArray(items.map { JsonPrimitive(it) }))

/** Key names of a secret map: its `<path>.<KEY>` child rows (values are never sent). */
fun secretMapKeys(container: ConfigField, rows: List<ConfigField>): List<String> =
    rows.filter { it.path.startsWith("${container.path}.") && !it.path.removePrefix("${container.path}.").contains('.') }
        .map { it.path.removePrefix("${container.path}.") }

/** True for a key row of a secret map; those are shown inside their container, not as fields. */
fun isSecretMapChild(f: ConfigField, rows: List<ConfigField>): Boolean =
    rows.any { it.kind == PropKind.SecretMap && f.path.startsWith("${it.path}.") }

/** The form a field lives in: `<map path>.<alias>` when it sits inside an alias map, else the section. */
fun formPrefixFor(path: String, sectionKey: String, templates: List<pt.tiago.zerodroid.rpc.proto.ConfigTemplate>): String {
    val map = templates.map { it.path }.filter { path.startsWith("$it.") }.maxByOrNull { it.length } ?: return sectionKey
    val alias = path.removePrefix("$map.").substringBefore('.')
    return if (path.removePrefix("$map.").contains('.')) "$map.$alias" else sectionKey
}

/** True when [path] is edited in the form at [prefix], i.e. not inside a deeper alias map. */
fun belongsToForm(path: String, prefix: String, templates: List<pt.tiago.zerodroid.rpc.proto.ConfigTemplate>): Boolean {
    if (!path.startsWith("$prefix.")) return false
    return templates.none { t -> t.path.startsWith("$prefix.") && path.startsWith("${t.path}.") }
}
