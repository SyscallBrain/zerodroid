package pt.tiago.zerodroid.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A file change an agent asked for, kept structured so the tool card can show it as a diff.
 * [old] is null for a whole-file write: the daemon sends only the new content, so there is no "before".
 */
data class FileEdit(val path: String, val old: String?, val new: String)

/** Larger than this, either side is left as plain arguments: a diff that size helps no one on a phone. */
private const val MAX_EDIT_CHARS = 200_000

private fun JsonObject.str(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content }

/**
 * Reads a file edit or write from a tool call's raw input. ZeroClaw's `file_edit` sends
 * `path`, `old_string`, `new_string`; `file_write` sends `path`, `content`. The `file_path`
 * spelling and ACP's `oldText`/`newText` are accepted too, so other runners' edit tools work.
 */
fun fileEditOf(name: String, raw: JsonElement?): FileEdit? {
    val o = raw as? JsonObject ?: return null
    val path = o.str("path", "file_path") ?: return null
    val old = o.str("old_string", "oldText")
    val new = o.str("new_string", "newText")
    val edit = when {
        old != null && new != null -> FileEdit(path, old, new)
        name.endsWith("write", ignoreCase = true) -> o.str("content", "newText")?.let { FileEdit(path, null, it) }
        else -> null
    } ?: return null
    if (edit.new.length > MAX_EDIT_CHARS || (edit.old?.length ?: 0) > MAX_EDIT_CHARS) return null
    return edit
}

enum class DiffKind { Same, Removed, Added }

data class DiffLine(val kind: DiffKind, val text: String)

/** Cells (changed old lines × changed new lines) the LCS table may hold, about 1 MB, before falling back to "all removed, then all added". */
private const val MAX_DIFF_CELLS = 250_000

private fun String.diffLines(): List<String> = if (isEmpty()) emptyList() else removeSuffix("\n").split("\n")

/**
 * Line diff of [old] → [new] by longest common subsequence: unchanged lines as [DiffKind.Same],
 * and within each change, removals before additions (as in a unified diff).
 * A whole-file write ([old] null) is every line added.
 */
fun lineDiff(old: String?, new: String): List<DiffLine> {
    val b = new.diffLines()
    if (old == null) return b.map { DiffLine(DiffKind.Added, it) }
    val a = old.diffLines()
    // Common head and tail cost nothing and keep the table small for typical edits.
    var head = 0
    while (head < a.size && head < b.size && a[head] == b[head]) head++
    var tail = 0
    while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
    val am = a.subList(head, a.size - tail)
    val bm = b.subList(head, b.size - tail)
    val out = ArrayList<DiffLine>(a.size + b.size)
    a.subList(0, head).forEach { out += DiffLine(DiffKind.Same, it) }
    if (am.size.toLong() * bm.size > MAX_DIFF_CELLS) {
        am.forEach { out += DiffLine(DiffKind.Removed, it) }
        bm.forEach { out += DiffLine(DiffKind.Added, it) }
    } else {
        val n = am.size
        val m = bm.size
        // lcs[i][j] = LCS length of am[i..] and bm[j..].
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (am[i] == bm[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        val removed = ArrayList<DiffLine>()
        val added = ArrayList<DiffLine>()
        fun flush() {
            out += removed; out += added; removed.clear(); added.clear()
        }
        while (i < n || j < m) {
            when {
                i < n && j < m && am[i] == bm[j] -> { flush(); out += DiffLine(DiffKind.Same, am[i]); i++; j++ }
                j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j]) -> { added += DiffLine(DiffKind.Added, bm[j]); j++ }
                else -> { removed += DiffLine(DiffKind.Removed, am[i]); i++ }
            }
        }
        flush()
    }
    a.subList(a.size - tail, a.size).forEach { out += DiffLine(DiffKind.Same, it) }
    return out
}

/** A row of the shown diff: a line, or a fold standing for [hidden] unchanged lines. */
sealed interface DiffRow {
    data class Line(val line: DiffLine) : DiffRow
    data class Fold(val hidden: Int) : DiffRow
}

/** Keeps [context] unchanged lines around each change and folds longer unchanged runs. */
fun foldDiff(lines: List<DiffLine>, context: Int = 3): List<DiffRow> {
    val keep = BooleanArray(lines.size)
    lines.forEachIndexed { i, l ->
        if (l.kind != DiffKind.Same) for (k in maxOf(0, i - context)..minOf(lines.size - 1, i + context)) keep[k] = true
    }
    val rows = ArrayList<DiffRow>()
    var hidden = 0
    lines.forEachIndexed { i, l ->
        if (keep[i]) {
            if (hidden > 0) { rows += DiffRow.Fold(hidden); hidden = 0 }
            rows += DiffRow.Line(l)
        } else hidden++
    }
    if (hidden > 0) rows += DiffRow.Fold(hidden)
    return rows
}
