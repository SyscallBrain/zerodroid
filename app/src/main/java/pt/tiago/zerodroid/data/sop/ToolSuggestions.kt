package pt.tiago.zerodroid.data.sop

/**
 * Tool names offered while typing a step's tools. The daemon has no tool-list RPC and the owner
 * chose not to patch it, so suggestions are the tools already used in any SOP plus the tools seen
 * in this app's chats. Anything else can still be typed; the daemon's validation catches typos.
 */
object ToolSuggestions {
    fun merge(sopTools: Set<String>, chatTools: Set<String>): List<String> =
        (sopTools + chatTools).filter { it.isNotBlank() }.distinct().sorted()

    fun filter(all: List<String>, query: String, chosen: Set<String>): List<String> {
        val q = query.trim()
        return all.filter { it !in chosen && (q.isEmpty() || it.contains(q, ignoreCase = true)) }
    }
}
