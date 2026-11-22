package pt.tiago.zerodroid.ui.doctor

import pt.tiago.zerodroid.rpc.proto.DiagResult
import pt.tiago.zerodroid.ui.config.ConfigRoute

/** Where "Fix" takes a finding: one config entry (or one field in it), or the Config home. */
sealed interface DoctorFix {
    data class Open(val route: ConfigRoute) : DoctorFix
    data object ConfigHome : DoctorFix
}

private const val ID = "[A-Za-z0-9_-]+"

/** A full config path: `channels.<type>.<alias>[.<field>]` or `providers.<kind>.<type>.<alias>[.<field>]`. */
private val FULL = Regex("""(?<![\w.])(channels|providers\.[a-z_]+)\.($ID)\.($ID)(?:\.([a-z][a-z0-9_]*))?""")

/** A bare `<type>.<alias>` label at the start, optionally after `model_provider "`: `openai.ss: no model configured`. */
private val SHORT = Regex("""^\s*(?:model_provider\s+)?["\[]?([a-z][a-z0-9_-]*)\.($ID)\b""", RegexOption.IGNORE_CASE)

private val CHANNEL_WORD = Regex("""\bchannel\b""", RegexOption.IGNORE_CASE)
private val MODEL_WORDS = Regex("""\bmodel\b|api[\s_-]?key|\bprovider\b|model_provider""", RegexOption.IGNORE_CASE)

/**
 * The config screen that fixes [r], read from its message the way the web dashboard's Doctor does.
 * OK findings and findings that name nothing editable outside config/workspace get null.
 */
fun doctorFix(r: DiagResult): DoctorFix? {
    if (r.severity == "ok") return null
    FULL.find(r.message)?.let { m ->
        val (root, type, alias, field) = m.destructured
        // Config's sections are `channels`, `providers.models`, `providers.tts`, …: the root is the section.
        val section = root
        val prefix = "$root.$type.$alias"
        val route = if (field.isNotEmpty()) ConfigRoute.Field(section, prefix, "$prefix.$field") else ConfigRoute.Entry(section, prefix, alias)
        return DoctorFix.Open(route)
    }
    SHORT.find(r.message)?.let { m ->
        val (type, alias) = m.destructured
        // Channel first: a channel finding can mention "provider" or "model" in its prose.
        val prefix = when {
            CHANNEL_WORD.containsMatchIn(r.message) -> "channels.$type.$alias"
            MODEL_WORDS.containsMatchIn(r.message) -> "providers.models.$type.$alias"
            else -> null
        }
        if (prefix != null) return DoctorFix.Open(ConfigRoute.Entry(prefix.substringBeforeLast('.').substringBeforeLast('.'), prefix, alias))
    }
    return if (r.category == "config" || r.category == "workspace") DoctorFix.ConfigHome else null
}

/** Findings grouped by category in the daemon's order, filtered to the [shown] severities. */
fun groupFindings(results: List<DiagResult>, shown: Set<String>): List<Pair<String, List<DiagResult>>> =
    results.filter { it.severity in shown }.groupBy { it.category.ifBlank { "other" } }.toList()

/** What the Home tile says about the last run. [value] null with [loading] shows a skeleton. */
data class DoctorTile(
    val value: String?,
    val suffix: String?,
    val caption: String,
    val loading: Boolean = false,
    val pill: Pair<String, pt.tiago.zerodroid.ui.kit.Tone>? = null,
    /** A run is going: the tile shows a pulsing "Running checks" pill instead of [pill]. */
    val running: Boolean = false,
)

fun doctorTile(state: pt.tiago.zerodroid.data.doctor.DoctorState, daemon: String?, time: (Long) -> String): DoctorTile {
    val r = state.resultFor(daemon)
    val checked = state.at?.let { "Checked ${time(it)}" }
    return when {
        r == null && state.running -> DoctorTile(null, null, "Checking config, channels, tools and models", loading = true, running = true)
        r == null -> DoctorTile("—", null, "Tap to check the daemon")
        r.summary.errors > 0 -> DoctorTile(
            r.summary.errors.toString(), if (r.summary.errors == 1) "error" else "errors",
            listOfNotNull(r.summary.warnings.takeIf { it > 0 }?.let { "$it warning${if (it == 1) "" else "s"}" }, checked).joinToString(" · "),
            pill = "Errors" to pt.tiago.zerodroid.ui.kit.Tone.Danger,
        )
        r.summary.warnings > 0 -> DoctorTile(
            r.summary.warnings.toString(), if (r.summary.warnings == 1) "warning" else "warnings", checked.orEmpty(),
            pill = "Warnings" to pt.tiago.zerodroid.ui.kit.Tone.Attention,
        )
        else -> DoctorTile("OK", null, listOfNotNull("${r.summary.ok} checks passed", checked).joinToString(" · "))
    }.let { if (state.running) it.copy(running = true) else it }
}
