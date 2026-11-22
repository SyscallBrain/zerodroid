package pt.tiago.zerodroid.ui.sop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pt.tiago.zerodroid.rpc.proto.TriggerSourceRegistryDto
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Trigger sources to offer: the bound/non-channel sources, then each inbound channel kind by name. */
fun triggerSourceOptions(r: TriggerSourceRegistryDto): List<Pair<String, String>> {
    val channels = r.channels.map { it.channel }
    val plain = r.sources.filter { it != "channel" && it !in channels }
    return plain.map { it to triggerTypeLabel(it) } + channels.map { it to it.replaceFirstChar { c -> c.uppercase() } }
}

/** A new trigger for [source]: `{"type": source}` plus an empty value per declared field; channels become `type = channel`. */
fun blankTrigger(source: String, registry: TriggerSourceRegistryDto): JsonObject {
    if (registry.channels.any { it.channel == source }) return buildJsonObject { put("type", "channel"); put("channel", source) }
    val fields = registry.bound.firstOrNull { it.source == source }?.fields.orEmpty()
    return buildJsonObject {
        put("type", source)
        // As the web's blankFieldValue: list → [], expression → unset (the condition builder owns it).
        fields.forEach { f ->
            when {
                f.kind == "expression" -> Unit
                f.kind == "list" || f.multi -> put(f.name, JsonArray(emptyList()))
                else -> put(f.name, "")
            }
        }
    }
}

/** One-line label: "Manual", "Schedule · 0 9 * * *", "Telegram · main", "Webhook · /hook". */
fun triggerLabel(t: JsonObject): String {
    val type = t.str("type") ?: return "Trigger"
    if (type == "channel") {
        val ch = t.str("channel")?.replaceFirstChar { it.uppercase() } ?: "Channel"
        return listOfNotNull(ch, t.str("alias")?.ifBlank { null }).joinToString(" · ")
    }
    val detail = when (type) {
        "cron" -> t.str("expression")
        "webhook", "filesystem" -> t.str("path")
        "mqtt" -> t.str("topic")
        "amqp" -> t.str("routing_key")
        else -> null
    }?.ifBlank { null }
    return listOfNotNull(triggerTypeLabel(type), detail).joinToString(" · ")
}

/** Editor for one trigger: the declared fields of its source, alias for channels, and a condition. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TriggerCard(t: JsonObject, registry: TriggerSourceRegistryDto?, ops: List<String>, onChange: (JsonObject) -> Unit, onRemove: () -> Unit) {
    val type = t.str("type") ?: ""
    val set = { k: String, v: kotlinx.serialization.json.JsonElement? -> onChange(JsonObject(t.toMutableMap().apply { if (v == null) remove(k) else put(k, v) })) }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
            .border(1.dp, ZcTokens.accentStart.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(triggerLabel(t), style = ZcType.heading.copy(color = ZcTokens.accentStart), modifier = Modifier.weight(1f))
            ZcIconButton(ZcIcons.Trash, "Remove trigger", tint = ZcTokens.danger, onClick = onRemove)
        }
        when (type) {
            "manual" -> Text("Runs when you tap Run, or when an agent starts it.", style = ZcType.small)
            "channel" -> {
                val kind = registry?.channels?.firstOrNull { it.channel == t.str("channel") }
                if (kind != null && kind.aliases.isNotEmpty()) {
                    Text("Instance", style = ZcType.caption, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val cur = t.str("alias")
                        (listOf<String?>(null) + kind.aliases.map { it.alias }).forEach { a ->
                            StatusPill(a ?: "Any", if (cur == a) Tone.Live else Tone.Neutral, dot = false, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { set("alias", a?.let(::JsonPrimitive)) })
                        }
                    }
                } else if (kind != null && !kind.configured) {
                    Text("This channel isn't configured on the daemon yet.", style = ZcType.small.copy(color = ZcTokens.attention))
                }
            }
            else -> registry?.bound?.firstOrNull { it.source == type }?.fields.orEmpty().filter { it.kind != "expression" }.forEach { f ->
                Text(f.name.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = ZcType.caption, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                when {
                    f.multi && f.options.isNotEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val cur = (t[f.name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                        f.options.forEach { o ->
                            val on = o in cur
                            StatusPill(o, if (on) Tone.Live else Tone.Neutral, dot = false, modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                                set(f.name, JsonArray((if (on) cur - o else cur + o).map(::JsonPrimitive)))
                            })
                        }
                    }
                    f.kind == "list" -> {
                        val cur = (t[f.name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                        ZcTextField(
                            cur.joinToString(", "),
                            { txt -> set(f.name, JsonArray(txt.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))) },
                            "${f.name.replace('_', ' ')}, separated by commas", modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    f.options.isNotEmpty() -> Picker(t.str(f.name)?.ifBlank { null } ?: "Choose…", f.options.map { it to it }, { set(f.name, JsonPrimitive(it)) })
                    else -> ZcTextField(
                        t.str(f.name).orEmpty(), { set(f.name, JsonPrimitive(it)) },
                        if (type == "cron") "0 9 * * *  (minute hour day month weekday)" else f.name,
                        mono = f.kind == "expression" || type == "cron", modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        val hasCondition = type == "channel" || registry?.bound?.firstOrNull { it.source == type }?.fields.orEmpty().any { it.kind == "expression" }
        if (hasCondition) {
            Spacer(Modifier.height(8.dp))
            Text("Only when", style = ZcType.caption)
            Spacer(Modifier.height(4.dp))
            ConditionField(t.str("condition"), ops, { set("condition", it?.let(::JsonPrimitive)) })
        }
    }
}
