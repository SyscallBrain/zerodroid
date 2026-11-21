package pt.tiago.zerodroid.data.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.ConfigSetEntry

const val APPLY_COMMENT = "ZeroDroid"

/** A staged value. Secret text never leaves memory and never prints. */
sealed interface DraftValue {
    data class Plain(val json: JsonElement) : DraftValue

    class Secret(internal val value: String) : DraftValue {
        override fun toString() = "Secret(•••)"
        override fun equals(other: Any?) = other is Secret && other.value == value
        override fun hashCode() = value.hashCode()
    }
}

data class DraftEntry(
    val path: String,
    val sectionKey: String,
    val label: String,
    val original: ConfigField,
    val value: DraftValue,
)

/** Old/new text for the review screen. Secrets never show their value. */
fun diffLine(e: DraftEntry): Pair<String, String> = when (val v = e.value) {
    is DraftValue.Secret -> (if (e.original.populated) "••••" else "not set") to "new value"
    is DraftValue.Plain -> displayJson(e.original.value) to displayJson(v.json)
}

internal fun displayJson(j: JsonElement?): String = when (j) {
    null -> "—"
    is JsonPrimitive -> j.content.ifEmpty { "—" }
    is JsonArray -> j.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() }.ifEmpty { "—" }
    else -> j.toString()
}

/** Field edits waiting to be applied together with one `config/set-many`. */
class ConfigDraft {
    private val _entries = MutableStateFlow<List<DraftEntry>>(emptyList())
    val entries: StateFlow<List<DraftEntry>> = _entries.asStateFlow()

    var lostSecrets: Boolean = false
        private set

    fun stage(field: ConfigField, sectionKey: String, value: DraftValue) {
        val backToOriginal = value is DraftValue.Plain && !field.isSecret && sameValue(field.value, value.json)
        _entries.update { list ->
            val idx = list.indexOfFirst { it.path == field.path }
            when {
                backToOriginal -> list.filterNot { it.path == field.path }
                idx >= 0 -> list.toMutableList().also { it[idx] = it[idx].copy(value = value) }
                else -> list + DraftEntry(field.path, sectionKey, field.path.substringAfterLast('.'), field, value)
            }
        }
    }

    fun unstage(path: String) = _entries.update { l -> l.filterNot { it.path == path } }

    fun unstageUnder(prefix: String) = _entries.update { l -> l.filterNot { it.path == prefix || it.path.startsWith("$prefix.") } }

    fun clear() {
        _entries.value = emptyList()
        lostSecrets = false
    }

    fun isStaged(path: String) = _entries.value.any { it.path == path }

    fun toSetMany(): List<ConfigSetEntry> = _entries.value.map { e ->
        when (val v = e.value) {
            is DraftValue.Plain -> ConfigSetEntry(e.path, v.json, APPLY_COMMENT)
            is DraftValue.Secret -> ConfigSetEntry.secret(e.path, v.value, APPLY_COMMENT)
        }
    }

    /** Drafted paths whose value on the daemon changed since they were staged. */
    fun conflicts(fresh: List<ConfigField>): List<String> {
        val byPath = fresh.associateBy { it.path }
        return _entries.value.mapNotNull { e ->
            val now = byPath[e.path] ?: return@mapNotNull null
            val changed = if (e.original.isSecret) now.populated != e.original.populated
            else !sameValue(now.value, e.original.value ?: JsonPrimitive(""))
            e.path.takeIf { changed }
        }
    }

    @Serializable
    private data class Persisted(
        val path: String,
        val sectionKey: String,
        val value: JsonElement? = null,
        val secret: Boolean = false,
        /** The daemon's value when this was staged, so conflicts survive process death. */
        val original: JsonElement? = null,
        val originalPopulated: Boolean = true,
    )

    /** Non-secret entries as JSON; secret entries are recorded by path only. */
    fun persistable(): String = ZdJson.encodeToString(
        ListSerializer(Persisted.serializer()),
        _entries.value.map { e ->
            when (val v = e.value) {
                is DraftValue.Plain -> Persisted(e.path, e.sectionKey, v.json, original = e.original.value, originalPopulated = e.original.populated)
                is DraftValue.Secret -> Persisted(e.path, e.sectionKey, secret = true)
            }
        },
    )

    fun restore(json: String, fields: (String) -> ConfigField?) {
        val saved = runCatching { ZdJson.decodeFromString(ListSerializer(Persisted.serializer()), json) }.getOrNull() ?: return
        lostSecrets = saved.any { it.secret }
        _entries.value = saved.mapNotNull { p ->
            if (p.secret || p.value == null) return@mapNotNull null
            val f = fields(p.path) ?: return@mapNotNull null
            val original = f.copy(value = p.original, populated = p.originalPopulated)
            DraftEntry(p.path, p.sectionKey, p.path.substringAfterLast('.'), original, DraftValue.Plain(p.value))
        }
    }

    private fun sameValue(a: JsonElement?, b: JsonElement): Boolean = displayJson(a ?: JsonPrimitive("")) == displayJson(b)
}
