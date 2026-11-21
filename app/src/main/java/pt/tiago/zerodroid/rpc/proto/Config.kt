package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/** Decodes a wire string to an enum, mapping anything unknown to [fallback]. Never throws. */
open class WireEnumSerializer<T : Enum<T>>(
    name: String,
    private val byWire: Map<String, T>,
    private val fallback: T,
) : KSerializer<T> {
    private val toWire = byWire.entries.associate { (k, v) -> v to k }
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): T = byWire[runCatching { decoder.decodeString() }.getOrNull()] ?: fallback
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(toWire[value] ?: value.name)
}

// Source: SectionShape — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable(with = SectionShapeSerializer::class)
enum class SectionShape { DirectForm, OneTierAliasMap, TypedFamilyMap, BackendPicker, Unknown }

object SectionShapeSerializer : WireEnumSerializer<SectionShape>(
    "SectionShape",
    mapOf(
        "direct_form" to SectionShape.DirectForm,
        "one_tier_alias_map" to SectionShape.OneTierAliasMap,
        "typed_family_map" to SectionShape.TypedFamilyMap,
        "backend_picker" to SectionShape.BackendPicker,
    ),
    SectionShape.Unknown,
)

// Source: ConfigSectionEntry — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class ConfigSection(
    val key: String,
    val label: String,
    val help: String = "",
    val group: String? = null,
    @SerialName("group_key") val groupKey: String? = null,
    val shape: SectionShape = SectionShape.Unknown,
    @SerialName("has_picker") val hasPicker: Boolean = false,
    val completed: Boolean = false,
    val ready: Boolean? = null,
)

@Serializable
data class ConfigSectionsResult(val sections: List<ConfigSection> = emptyList())

// Source: PropKind — crates/zeroclaw-config/src/traits.rs (rename_all = "snake_case")
@Serializable(with = PropKindSerializer::class)
enum class PropKind { String, Bool, Integer, Float, Enum, AliasRef, StringArray, ObjectArray, Object, SecretMap, Unknown }

object PropKindSerializer : WireEnumSerializer<PropKind>(
    "PropKind",
    mapOf(
        "string" to PropKind.String, "bool" to PropKind.Bool, "integer" to PropKind.Integer,
        "float" to PropKind.Float, "enum" to PropKind.Enum, "alias_ref" to PropKind.AliasRef,
        "string_array" to PropKind.StringArray, "object_array" to PropKind.ObjectArray,
        "object" to PropKind.Object, "secret_map" to PropKind.SecretMap,
    ),
    PropKind.Unknown,
)

// Source: ConfigFieldEntry — crates/zeroclaw-config/src/traits.rs
@Serializable
data class ConfigField(
    val path: String,
    val category: String = "",
    val kind: PropKind = PropKind.Unknown,
    @SerialName("type_hint") val typeHint: String = "",
    /** Display value; always absent for secret and secret-derived fields. */
    val value: JsonElement? = null,
    val populated: Boolean = false,
    @SerialName("is_secret") val isSecret: Boolean = false,
    @SerialName("is_env_overridden") val isEnvOverridden: Boolean = false,
    @SerialName("enum_variants") val enumVariants: List<String> = emptyList(),
    val description: String = "",
    val section: String? = null,
    /** ConfigTab serializes PascalCase (e.g. "Connection"); "None" means no tab. */
    val tab: String? = null,
    @SerialName("alias_source") val aliasSource: String? = null,
    val multiline: Boolean = false,
    /** A present `setup` marks a required field. */
    val setup: JsonElement? = null,
) {
    val required: Boolean get() = setup != null
    val tabOrNull: String? get() = tab?.takeIf { it.isNotBlank() && it != "None" }

    override fun toString() = "ConfigField(path=$path, kind=$kind, value=${if (isSecret) "•••" else value})"
}

@Serializable
data class ConfigListResult(val entries: List<ConfigField> = emptyList())

@Serializable
data class MapKeysResult(val path: String = "", val keys: List<String> = emptyList())

// Source: ConfigTemplateEntry — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class ConfigTemplate(
    val path: String,
    val kind: String = "map",
    @SerialName("value_type") val valueType: String = "",
    val description: String = "",
)

@Serializable
data class ConfigTemplatesResult(val templates: List<ConfigTemplate> = emptyList())

// Source: ConfigResolveAliasSourceResult
@Serializable
data class AliasValuesResult(val source: String = "", val values: List<String> = emptyList())

// Source: ConfigSetParams — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class ConfigSetEntry(
    val prop: String,
    val value: JsonElement,
    val comment: String? = null,
) {
    @Transient private var secret: Boolean = false

    override fun toString() = "ConfigSetEntry(prop=$prop, value=${if (secret) "•••" else value})"

    companion object {
        fun secret(prop: String, value: String, comment: String? = null) =
            ConfigSetEntry(prop, kotlinx.serialization.json.JsonPrimitive(value), comment).also { it.secret = true }
    }
}

@Serializable
data class ConfigSetManyParams(val sets: List<ConfigSetEntry>)

@Serializable
data class ConfigSetManyResult(val props: List<String> = emptyList(), val set: Boolean = false)

// Source: ConfigApplicationOutcome
@Serializable(with = ApplyOutcomeSerializer::class)
enum class ApplyOutcome { Pending, AppliedLive, QueuedForReload, Rejected, Unknown }

object ApplyOutcomeSerializer : WireEnumSerializer<ApplyOutcome>(
    "ApplyOutcome",
    mapOf(
        "pending" to ApplyOutcome.Pending, "applied_live" to ApplyOutcome.AppliedLive,
        "queued_for_reload" to ApplyOutcome.QueuedForReload, "rejected" to ApplyOutcome.Rejected,
    ),
    ApplyOutcome.Unknown,
)

// Source: ConfigApplicationRecord
@Serializable
data class ApplicationRecord(
    val path: List<String> = emptyList(),
    val outcome: ApplyOutcome = ApplyOutcome.Unknown,
    val reason: String? = null,
)

@Serializable
data class ApplicationStatus(val records: List<ApplicationRecord> = emptyList())

// Source: ConfigStatusResult
@Serializable
data class ConfigStatusResult(val application: ApplicationStatus? = null)

// Source: AgentDeletePreviewResult
@Serializable
data class AgentDeletePreview(
    val alias: String,
    val allowed: Boolean = false,
    val blockers: List<String> = emptyList(),
    val scrubs: List<String> = emptyList(),
    @SerialName("owned_state") val ownedState: List<String> = emptyList(),
)

// Source: AgentDeleteResult
@Serializable
data class AgentDeleteResult(
    val alias: String,
    val deleted: Boolean = false,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
)
