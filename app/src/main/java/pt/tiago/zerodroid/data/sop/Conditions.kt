package pt.tiago.zerodroid.data.sop

/**
 * A condition string split into what the guided builder edits: an optional JSON path (absent for
 * direct scalar payloads), an operator token and a raw comparand. [raw] keeps the original so a
 * value the builder cannot parse round-trips untouched. Port of `parseCondition`/`buildCondition`
 * in the web dashboard (`web/src/lib/sops.ts`).
 */
data class ParsedCondition(val path: String?, val op: String, val value: String, val raw: String)

/** Operators are matched longest token first so `>=` wins over `>`. Unparseable input yields `op = ""`. */
fun parseCondition(condition: String?, operators: List<String>): ParsedCondition {
    val raw = condition ?: ""
    val trimmed = raw.trim()
    val hasPath = trimmed.startsWith("$")
    val scanFrom = if (hasPath) trimmed.removePrefix("$").removePrefix(".") else trimmed
    for (token in operators.sortedByDescending { it.length }) {
        val at = scanFrom.indexOf(token)
        if (at < 0) continue
        return ParsedCondition(
            path = if (hasPath) scanFrom.substring(0, at).trim() else null,
            op = token,
            value = scanFrom.substring(at + token.length).trim(),
            raw = raw,
        )
    }
    return ParsedCondition(if (hasPath) scanFrom.trim() else null, "", "", raw)
}

/** `$.<path> <op> <value>` with a path, `<op> <value>` without; an empty operator means no condition. */
fun buildCondition(path: String?, op: String, value: String): String? {
    if (op.isEmpty()) return null
    val rhs = "$op $value".trim()
    val p = path?.trim().orEmpty()
    return if (p.isNotEmpty()) "$.$p $rhs" else rhs
}
