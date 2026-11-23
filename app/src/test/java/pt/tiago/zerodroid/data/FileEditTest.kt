package pt.tiago.zerodroid.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pt.tiago.zerodroid.data.DiffKind.Added
import pt.tiago.zerodroid.data.DiffKind.Removed
import pt.tiago.zerodroid.data.DiffKind.Same
import pt.tiago.zerodroid.rpc.proto.MessageEntry
import pt.tiago.zerodroid.rpc.proto.SessionUpdate

class FileEditTest {
    private fun obj(vararg kv: Pair<String, String>) = JsonObject(kv.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun kinds(d: List<DiffLine>) = d.joinToString("") {
        when (it.kind) { Same -> "="; Removed -> "-"; Added -> "+" } + it.text
    }

    @Test
    fun readsZeroClawFileEdit() {
        val e = fileEditOf("file_edit", obj("path" to "src/a.kt", "old_string" to "a", "new_string" to "b"))
        assertEquals(FileEdit("src/a.kt", "a", "b"), e)
    }

    @Test
    fun readsFileWriteAsWholeFile() {
        assertEquals(FileEdit("x.txt", null, "hi"), fileEditOf("file_write", obj("path" to "x.txt", "content" to "hi")))
    }

    @Test
    fun readsOtherSpellings() {
        assertEquals(FileEdit("p", "o", "n"), fileEditOf("Edit", obj("file_path" to "p", "old_string" to "o", "new_string" to "n")))
        assertEquals(FileEdit("p", "o", "n"), fileEditOf("edit", obj("path" to "p", "oldText" to "o", "newText" to "n")))
    }

    @Test
    fun otherToolsAreNotEdits() {
        assertNull(fileEditOf("shell", obj("command" to "ls")))
        assertNull(fileEditOf("file_read", obj("path" to "a")))
        assertNull(fileEditOf("file_edit", obj("old_string" to "a", "new_string" to "b")))
        assertNull(fileEditOf("file_edit", JsonPrimitive("x")))
        assertNull(fileEditOf("file_edit", null))
    }

    @Test
    fun hugeEditsStayPlainArguments() {
        assertNull(fileEditOf("file_write", obj("path" to "p", "content" to "x".repeat(200_001))))
    }

    @Test
    fun diffKeepsCommonLinesAndPutsRemovalsFirst() {
        val d = lineDiff("a\nb\nc\nd", "a\nB\nc\nd\ne")
        assertEquals("=a-b+B=c=d+e", kinds(d))
    }

    @Test
    fun diffOfInsertionInTheMiddle() {
        assertEquals("=one+two=three", kinds(lineDiff("one\nthree", "one\ntwo\nthree")))
    }

    @Test
    fun diffIgnoresTrailingNewlineAndHandlesEmptySides() {
        assertEquals("=a+b", kinds(lineDiff("a\n", "a\nb\n")))
        assertEquals("+a+b", kinds(lineDiff("", "a\nb")))
        assertEquals("-a", kinds(lineDiff("a", "")))
        assertEquals("+a+b", kinds(lineDiff(null, "a\nb")))
    }

    @Test
    fun diffOfIdenticalTextIsAllSame() {
        assertEquals("=x=y", kinds(lineDiff("x\ny", "x\ny")))
    }

    @Test
    fun hugeChangeFallsBackToRemoveThenAdd() {
        val old = (1..600).joinToString("\n") { "o$it" }
        val new = (1..600).joinToString("\n") { "n$it" }
        val d = lineDiff(old, new)
        assertEquals(600, d.count { it.kind == Removed })
        assertEquals(600, d.count { it.kind == Added })
        assertEquals(Removed, d.first().kind)
        assertEquals(Added, d.last().kind)
    }

    @Test
    fun foldKeepsThreeLinesOfContext() {
        val old = (1..20).joinToString("\n") { "l$it" }
        val new = old.replace("l10", "L10")
        val rows = foldDiff(lineDiff(old, new))
        assertEquals(DiffRow.Fold(6), rows.first())
        assertEquals(DiffRow.Fold(7), rows.last())
        assertEquals(3 + 2 + 3 + 2, rows.size)
    }

    @Test
    fun reducerKeepsTheEditOnLiveAndHistoryToolCalls() {
        val raw = obj("path" to "a.kt", "old_string" to "x", "new_string" to "y")
        val live = reduce(ChatState(), SessionUpdate.ToolCall("s", "c1", "file_edit", raw), 0)
        assertEquals(FileEdit("a.kt", "x", "y"), (live.items.single() as ChatItem.Tool).edit)
        val hist = fromHistory(listOf(MessageEntry(role = "assistant", content = "", kind = "tool_call", toolCallId = "c1", toolName = "file_edit", toolInput = raw)))
        assertEquals(FileEdit("a.kt", "x", "y"), (hist.items.single() as ChatItem.Tool).edit)
    }
}
