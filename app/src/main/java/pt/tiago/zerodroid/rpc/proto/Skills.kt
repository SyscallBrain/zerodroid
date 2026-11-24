package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Source: SkillSlashChoice — crates/zeroclaw-runtime/src/skills/mod.rs
@Serializable
data class SkillSlashChoice(val name: String, val value: String)

// Source: SkillSlashOption — crates/zeroclaw-runtime/src/skills/mod.rs
@Serializable
data class SkillSlashOption(
    val name: String,
    val description: String = "",
    @SerialName("description_localizations") val descriptionLocalizations: Map<String, String> = emptyMap(),
    @SerialName("type") val kind: String = "string",
    val required: Boolean = false,
    val choices: List<SkillSlashChoice> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    @SerialName("min_length") val minLength: Int? = null,
    @SerialName("max_length") val maxLength: Int? = null,
)

// Source: SkillFrontmatter — crates/zeroclaw-runtime/src/skills/frontmatter.rs
@Serializable
data class SkillFrontmatter(
    val name: String,
    val description: String = "",
    val license: String? = null,
    val author: String? = null,
    val version: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val always: Boolean = false,
    @SerialName("slash_options") val slashOptions: List<SkillSlashOption> = emptyList(),
)

// Source: SkillBundleEntry — crates/zeroclaw-rpc-proto/src/types.rs
@Serializable
data class SkillBundleEntry(
    val alias: String,
    val directory: String = "",
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
)

// Source: SkillsBundlesResult — crates/zeroclaw-rpc-proto/src/types.rs
@Serializable
data class SkillsBundlesResult(val bundles: List<SkillBundleEntry> = emptyList())

// Source: SkillsListParams — crates/zeroclaw-rpc-proto/src/types.rs
@Serializable
data class SkillsListParams(val bundle: String? = null)

// Source: SkillListEntry — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SkillListEntry(
    val bundle: String = "",
    val name: String,
    val directory: String = "",
    val frontmatter: SkillFrontmatter,
)

// Source: SkillsListResult — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SkillsListResult(val skills: List<SkillListEntry> = emptyList())

// Source: SkillsReadParams — crates/zeroclaw-rpc-proto/src/types.rs
@Serializable
data class SkillsReadParams(val bundle: String, val name: String)

// Source: SkillsReadResult — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SkillsReadResult(
    val bundle: String = "",
    val name: String = "",
    val frontmatter: SkillFrontmatter,
    val body: String = "",
)

// Source: SkillsWriteParams — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SkillsWriteParams(val bundle: String, val name: String, val frontmatter: SkillFrontmatter, val body: String = "")

// Source: SkillsWriteResult — crates/zeroclaw-rpc-proto/src/types.rs
@Serializable
data class SkillsWriteResult(val bundle: String = "", val name: String = "", val written: Boolean = false)

// Source: SkillsDeleteParams — crates/zeroclaw-rpc-proto/src/types.rs
@Serializable
data class SkillsDeleteParams(val bundle: String, val name: String)

// Source: SkillsDeleteResult — crates/zeroclaw-rpc-proto/src/types.rs
@Serializable
data class SkillsDeleteResult(val bundle: String = "", val name: String = "", val deleted: Boolean = false)
