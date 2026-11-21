package pt.tiago.zerodroid.data

import pt.tiago.zerodroid.data.config.humanLabel
import pt.tiago.zerodroid.rpc.proto.AgentEntry
import pt.tiago.zerodroid.rpc.proto.SessionEntry

/**
 * The daemon's `session/list { query }` searches message content only, so a renamed session
 * would never match its own name. Match name, agent and id here and merge with the content hits.
 */
fun mergeSessionSearch(all: List<SessionEntry>, contentHits: List<SessionEntry>, query: String): List<SessionEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return all
    val local = all.filter { e ->
        listOfNotNull(e.name, e.agentAlias, e.sessionId).any { it.lowercase().contains(q) }
    }
    return (local + contentHits).distinctBy { it.sessionId }.sortedByDescending { it.lastActivity }
}

fun filterAgents(agents: List<AgentEntry>, query: String, pins: List<String>): List<AgentEntry> {
    val q = query.trim().lowercase()
    val matching = if (q.isEmpty()) agents else agents.filter { it.alias.lowercase().contains(q) }
    return orderPinned(matching, pins) { it.alias }
}

private val TABLE_REF = Regex("""\s*\(`\[[^\]]*]`\)""")

/**
 * Drops the doc comment's TOML table references, e.g. " (`[notion]`)", wherever they are. A single
 * sentence loses its final period. Never returns blank.
 */
fun cleanDescription(raw: String, key: String): String {
    var cleaned = raw.replace(TABLE_REF, "").trim()
    if (cleaned.count { it == '.' } == 1 && cleaned.endsWith('.')) cleaned = cleaned.dropLast(1)
    return cleaned.ifEmpty { humanLabel(key, key) }
}

/** One-line summary for lists: the first paragraph of the cleaned description, without markdown backticks. */
fun summaryOf(raw: String, key: String): String =
    cleanDescription(raw, key).split("\n\n").first().lineSequence().joinToString(" ") { it.trim() }
        .trim().removeSuffix(".").replace("`", "")

/** Summary of a provider/channel type row: how many are set up, else its description, else "Not configured". */
fun typeSummary(configured: Int, description: String, path: String): String = when {
    configured > 0 -> "$configured configured"
    description.isBlank() -> "Not configured"
    else -> summaryOf(description, path.substringAfterLast('.'))
}
